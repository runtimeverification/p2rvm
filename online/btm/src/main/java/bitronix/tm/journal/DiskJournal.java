/*
 * Copyright (C) 2006-2013 Bitronix Software (http://www.bitronix.be)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package bitronix.tm.journal;

import bitronix.tm.BitronixXid;
import bitronix.tm.Configuration;
import bitronix.tm.TransactionManagerServices;
import bitronix.tm.utils.Decoder;
import bitronix.tm.utils.MonotonicClock;
import bitronix.tm.utils.Uid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.Status;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Simple implementation of a journal that writes on a two-files disk log.
 * <p>Files are pre-allocated in size, never grow and when the first one is full, dangling records are copied to the
 * second file and logging starts again on the latter.</p>
 * <p>This implementation is not highly efficient but quite robust and simple. It is based on one of the implementations
 * proposed by Mike Spille.</p>
 * <p>Configurable properties are all starting with <code>bitronix.tm.journal.disk</code>.</p>
 *
 * @see bitronix.tm.Configuration
 * @see <a href="http://jroller.com/page/pyrasun?entry=xa_exposed_part_iii_the">XA Exposed, Part III: The Implementor's Notebook</a>
 *
 * @author Ludovic Orban
 * @author Brett Wooldridge
 */
public class DiskJournal implements Journal, MigratableJournal, ReadableJournal {

    private final static Logger log = LoggerFactory.getLogger(DiskJournal.class);

    /**
     * The active log appender. This is exactly the same reference as tla1 or tla2 depending on which one is
     * currently active
     */
    private final AtomicReference<TransactionLogAppender> activeTla;

    /**
     * The transaction log appender writing on the 1st file
     */
    private TransactionLogAppender tla1;

    /**
     * The transaction log appender writing on the 2nd file
     */
    private TransactionLogAppender tla2;

	private final Lock conservativeJournalingLock = new ReentrantLock();
	private final ReadWriteLock swapForceLock = new ReentrantReadWriteLock(true);
	private final Object positionLock = new Object();
	private final AtomicBoolean needsForce;

	private final Configuration configuration;

    /**
     * Create an uninitialized disk journal. You must call open() prior you can use it.
     */
    public DiskJournal() {
    	configuration = TransactionManagerServices.getConfiguration();
    	needsForce = new AtomicBoolean();
    	activeTla = new AtomicReference<TransactionLogAppender>();
    }

    /**
     * Log a new transaction status to journal. Note that the DiskJournal will not check the flow of the transaction.
     * If you call this method with erroneous data, it will be added to the journal anyway.
     *
     * @param status transaction status to log. See {@link javax.transaction.Status} constants.
     * @param gtrid raw GTRID of the transaction.
     * @param uniqueNames unique names of the {@link bitronix.tm.resource.common.ResourceBean}s participating in
     * this transaction.
     * @throws java.io.IOException in case of disk IO failure or if the disk journal is not open.
     */
    @Override
    public void log(int status, Uid gtrid, Set<String> uniqueNames) throws IOException {
        if (activeTla.get() == null)
            throw new IOException("cannot write log, disk logger is not open");

        if (configuration.isFilterLogStatus()) {
            if (status != Status.STATUS_COMMITTING && status != Status.STATUS_COMMITTED && status != Status.STATUS_UNKNOWN) {
                if (log.isDebugEnabled()) { log.debug("filtered out write to log for status " + Decoder.decodeStatus(status)); }
                return;
            }
        }

        TransactionLogRecord tlog = new TransactionLogRecord(status, gtrid, uniqueNames);

        try {
        	if (configuration.isConservativeJournaling()) {
        		conservativeJournalingLock.lock();
        	}

	        synchronized (positionLock) {
	        	boolean rollover = activeTla.get().setPositionAndAdvance(tlog);
	            if (rollover) {
	                // time to swap log files
                    swapForceLock.writeLock().lock();
	                try {
	                	swapJournalFiles();
	                	activeTla.get().setPositionAndAdvance(tlog);
	                }
	                finally {
	                	swapForceLock.writeLock().unlock();
	                }
	            }

                // this read lock MUST be acquired under positionLock
	        	swapForceLock.readLock().lock();
	        }

	        try {
	        	activeTla.get().writeLog(tlog);
	        	needsForce.set(true);
	        }
	        finally {
	        	swapForceLock.readLock().unlock();
	        }
        }
        finally {
        	if (configuration.isConservativeJournaling()) {
        		conservativeJournalingLock.unlock();
        	}
        }
    }

    /**
     * Force active log file to synchronize with the underlying disk device.
     *
     * @throws java.io.IOException in case of disk IO failure or if the disk journal is not open.
     */
    @Override
    public void force() throws IOException {
        if (activeTla.get() == null)
            throw new IOException("cannot force log writing, disk logger is not open");

        if (needsForce.get() && configuration.isForcedWriteEnabled()) {
	        swapForceLock.writeLock().lock();
	        try {
	        	activeTla.get().force();
	        	needsForce.set(false);
	        }
	        finally {
	        	swapForceLock.writeLock().unlock();
	        }
        }
    }

    /**
     * Open the disk journal. Files are checked for integrity and DiskJournal will refuse to open corrupted log files.
     * If files are not present on disk, this method will create and pre-allocate them.
     *
     * @throws java.io.IOException in case of disk IO failure.
     */
    @Override
    public synchronized void open() throws IOException {
        if (activeTla.get() != null) {
            log.warn("disk journal already open");
            return;
        }

        File file1 = new File(configuration.getLogPart1Filename());
        File file2 = new File(configuration.getLogPart2Filename());

        if (!file1.exists() && !file2.exists()) {
            log.debug("creation of log files");
            createLogfile(file2, configuration.getMaxLogSizeInMb());

            // make the clock run a little before creating the 2nd log file to ensure the timestamp headers are not the same
            long before = MonotonicClock.currentTimeMillis();
            while (MonotonicClock.currentTimeMillis() < before + 100L) {
                try { Thread.sleep(100); } catch (InterruptedException ex) { /* ignore */ }
            }

            createLogfile(file1, configuration.getMaxLogSizeInMb());
        }

        if (file1.length() != file2.length()) {
            if (!configuration.isSkipCorruptedLogs())
                throw new IOException("transaction log files are not of the same length, assuming they're corrupt");
            log.error("transaction log files are not of the same length: corrupted files?");
        }

        long maxFileLength = Math.max(file1.length(), file2.length());
        if (log.isDebugEnabled()) { log.debug("disk journal files max length: " + maxFileLength); }

        tla1 = new TransactionLogAppender(file1, maxFileLength);
        tla2 = new TransactionLogAppender(file2, maxFileLength);

        byte cleanStatus = pickActiveJournalFile(tla1, tla2);
        if (cleanStatus != TransactionLogHeader.CLEAN_LOG_STATE) {
            log.warn("active log file is unclean, did you call BitronixTransactionManager.shutdown() at the end of the last run?");
        }

        if (log.isDebugEnabled()) { log.debug("disk journal opened"); }
    }

    /**
     * Close the disk journal and the underlying files.
     *
     * @throws java.io.IOException in case of disk IO failure.
     */
    @Override
    public synchronized void close() throws IOException {
        if (activeTla.get() == null) {
            return;
        }

        try {
            tla1.close();
        } catch (IOException ex) {
            log.error("cannot close " + tla1, ex);
        }
        tla1 = null;
        try {
            tla2.close();
        } catch (IOException ex) {
            log.error("cannot close " + tla2, ex);
        }
        tla2 = null;
        activeTla.set(null);

        if (log.isDebugEnabled()) { log.debug("disk journal closed"); }
    }

    @Override
    public void shutdown() {
        try {
            close();
        } catch (IOException ex) {
            log.error("error shutting down disk journal. Transaction log integrity could be compromised!", ex);
        }
    }

    /**
     * Collect all dangling records of the active log file.
     *
     * @return a Map using Uid objects GTRID as key and {@link TransactionLogRecord} as value
     * @throws java.io.IOException in case of disk IO failure or if the disk journal is not open.
     */
    @Override
    public Map<Uid, JournalRecord> collectDanglingRecords() throws IOException {
        if (activeTla.get() == null)
            throw new IOException("cannot collect dangling records, disk logger is not open");
        return collectDanglingRecords(activeTla.get());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void migrateTo(Journal other) throws IOException, IllegalArgumentException {
        if (other == this)
            throw new IllegalArgumentException("cannot migrate a journal to itself (this == otherJournal)");
        if (other == null)
            throw new IllegalArgumentException("the migration target journal cannot be null");

        for (Object record : collectDanglingRecords().values()) {
            JournalRecord jr = (JournalRecord) record;
            other.log(jr.getStatus(), jr.getGtrid(), jr.getUniqueNames());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void unsafeReadRecordsInto(Collection<JournalRecord> target, boolean includeInvalid) throws IOException {
        if (activeTla.get() == null)
            throw new IOException("cannot read records, disk logger is not open");

        for (Iterator<TransactionLogRecord> i = iterateRecords(activeTla.get(), includeInvalid); i.hasNext(); )
            target.add(i.next());
    }

    /*
     * Internal impl.
     */

    /**
     * Create a fresh log file on disk. If the specified file already exists it will be deleted then recreated.
     * @param logfile the file to create
     * @param maxLogSizeInMb the file size in megabytes to preallocate
     * @throws java.io.IOException in case of disk IO failure.
     */
    private static void createLogfile(File logfile, int maxLogSizeInMb) throws IOException {
        if (logfile.isDirectory())
            throw new IOException("log file is referring to a directory: " + logfile.getAbsolutePath());
        if (logfile.exists()) {
            boolean deleted = logfile.delete();
            if (!deleted)
                throw new IOException("log file exists but cannot be overwritten: " + logfile.getAbsolutePath());
        }
        if (logfile.getParentFile() != null) {
            logfile.getParentFile().mkdirs();
        }

        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(logfile, "rw");

            raf.seek(TransactionLogHeader.FORMAT_ID_HEADER);
            raf.writeInt(BitronixXid.FORMAT_ID);
            raf.writeLong(MonotonicClock.currentTimeMillis());
            raf.writeByte(TransactionLogHeader.CLEAN_LOG_STATE);
            raf.writeLong((long) TransactionLogHeader.HEADER_LENGTH);

            byte[] buffer = new byte[4096];
            int length = (maxLogSizeInMb *1024 *1024) /4096;
            for (int i = 0; i < length; i++) {
                raf.write(buffer);
            }
        } finally {
            if (raf != null) raf.close();
        }
    }

    /**
     * Initialize the activeTla member variable with the TransactionLogAppender object having the latest timestamp
     * header.
     *
     * @param tla1 the first of the two candidate active TransactionLogAppenders
     * @param tla2 the second of the two candidate active TransactionLogAppenders
     * @return the state of the designated active TransactionLogAppender as returned by TransactionLogHeader.getState()
     * @throws java.io.IOException in case of disk IO failure.
     * @see TransactionLogHeader
     */
    private synchronized byte pickActiveJournalFile(TransactionLogAppender tla1, TransactionLogAppender tla2) throws IOException {
        if (tla1.getTimestamp() > tla2.getTimestamp()) {
        	activeTla.set(tla1);
            if (log.isDebugEnabled()) { log.debug("logging to file 1: " + activeTla); }
        }
        else {
        	activeTla.set(tla2);
            if (log.isDebugEnabled()) { log.debug("logging to file 2: " + activeTla); }
        }

        byte cleanState = activeTla.get().getState();
        activeTla.get().setState(TransactionLogHeader.UNCLEAN_LOG_STATE);
        if (log.isDebugEnabled()) { log.debug("log file activated, forcing file state to disk"); }
        activeTla.get().force();
        return cleanState;
    }


    /**
     * <p>Swap the active and the passive journal files so that the active one becomes passive and the passive one
     * becomes active.</p>
     * List of actions taken by this method:
     * <ul>
     *   <li>ensure the all data has been forced to the active log file.</li>
     *   <li>copy dangling COMMITTING records to the passive log file.</li>
     *   <li>update header timestamp of passive log file (makes it become active).</li>
     *   <li>do a force on passive log file. It is now the active file.</li>
     *   <li>switch references of active/passive files.</li>
     * </ul>
     *
     * @throws java.io.IOException in case of disk IO failure.
     */
    private synchronized void swapJournalFiles() throws IOException {
        if (log.isDebugEnabled()) { log.debug("swapping journal log file to " + getPassiveTransactionLogAppender()); }

        //step 1
        activeTla.get().force();

        //step 2
        TransactionLogAppender passiveTla = getPassiveTransactionLogAppender();
        passiveTla.rewind();

        List<TransactionLogRecord> danglingLogs = activeTla.get().getDanglingLogs();
        for (TransactionLogRecord tlog : danglingLogs) {
            boolean rolloverError = passiveTla.setPositionAndAdvance(tlog);
            if (rolloverError) {
                throw new IOException("moving in-flight transactions the rollover log file would have resulted in an overflow of that file");
            }
            passiveTla.writeLog(tlog);
        }

        if (log.isDebugEnabled()) { log.debug(danglingLogs.size() + " dangling record(s) copied to passive log file"); }

        activeTla.get().clearDanglingLogs();

        //step 3
        passiveTla.setTimestamp(MonotonicClock.currentTimeMillis());

        //step 4
        passiveTla.force();

        //step 5
        activeTla.set(passiveTla);

        if (log.isDebugEnabled()) { log.debug("journal log files swapped"); }
    }

    /**
     * @return the TransactionFileAppender of the passive journal file.
     */
    private synchronized TransactionLogAppender getPassiveTransactionLogAppender() {
        return (tla1 == activeTla.get() ? tla2 : tla1);
    }

    /**
     * Create a Map of TransactionLogRecord with COMMITTING status objects using the GTRID byte[] as key that have
     * no corresponding COMMITTED record
     *
     * @param tla the TransactionLogAppender to scan
     * @return a Map using Uid objects GTRID as key and {@link TransactionLogRecord} as value
     * @throws java.io.IOException in case of disk IO failure.
     */
    private static Map<Uid, JournalRecord> collectDanglingRecords(TransactionLogAppender tla) throws IOException {
        Map<Uid, JournalRecord> danglingRecords = new HashMap<Uid, JournalRecord>(64);
        TransactionLogCursor tlc = tla.getCursor();

        try {
            int committing = 0;
            int committed = 0;

            while (true) {
                TransactionLogRecord tlog;
                try {
                    tlog = tlc.readLog();
                } catch (CorruptedTransactionLogException ex) {
                    if (TransactionManagerServices.getConfiguration().isSkipCorruptedLogs()) {
                        log.error("skipping corrupted log", ex);
                        continue;
                    }
                    throw ex;
                }

                if (tlog == null)
                    break;

                int status = tlog.getStatus();
                if (status == Status.STATUS_COMMITTING) {
                    danglingRecords.put(tlog.getGtrid(), tlog);
                    committing++;
                }

                // COMMITTED is when there was no problem in the transaction
                // UNKNOWN is when a 2PC transaction heuristically terminated
                // ROLLEDBACK is when a 1PC transaction rolled back during commit
                if (status == Status.STATUS_COMMITTED || status == Status.STATUS_UNKNOWN || status == Status.STATUS_ROLLEDBACK) {
                    JournalRecord rec = danglingRecords.get(tlog.getGtrid());
                    if (rec != null) {
                        Set<String> recUniqueNames = new HashSet<String>(rec.getUniqueNames());
                        recUniqueNames.removeAll(tlog.getUniqueNames());
                        if (recUniqueNames.isEmpty()) {
                            danglingRecords.remove(tlog.getGtrid());
                            committed++;
                        } else {
                            danglingRecords.put(tlog.getGtrid(), new TransactionLogRecord(rec.getStatus(), rec.getGtrid(), recUniqueNames));
                        }
                    }
                }
            }

            if (log.isDebugEnabled()) { log.debug("collected dangling records of " + tla + ", committing: " + committing + ", committed: " + committed + ", delta: " + danglingRecords.size()); }
        }
        finally {
            tlc.close();
        }
        return danglingRecords;
    }

    /**
     * Implements a low level iterator over all entries contained in the active TX log.
     *
     * @param tla          the TransactionLogAppender to scan
     * @param skipCrcCheck sets whether CRC checks are applied or not.
     * @return an iterator over all contained log records.
     * @throws java.io.IOException in case of the initial disk IO failed (subsequent errors are unchecked exceptions).
     */
    private static Iterator<TransactionLogRecord> iterateRecords(TransactionLogAppender tla, final boolean skipCrcCheck) throws IOException {
        final TransactionLogCursor tlc = tla.getCursor();
        final Iterator<TransactionLogRecord> it = new Iterator<TransactionLogRecord>() {
            TransactionLogRecord tlog;

            public boolean hasNext() {
                while (tlog == null) {
                    try {
                        try {
                            tlog = tlc.readLog(skipCrcCheck);
                            if (tlog == null)
                                break;
                        } catch (CorruptedTransactionLogException ex) {
                            if (TransactionManagerServices.getConfiguration().isSkipCorruptedLogs()) {
                                log.error("skipping corrupted log", ex);
                                continue;
                            }
                            throw ex;
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                return tlog != null;
            }

            public TransactionLogRecord next() {
                if (!hasNext())
                    throw new NoSuchElementException();
                try {
                    return tlog;
                } finally {
                    tlog = null;
                }
            }

            public void remove() {
                throw new UnsupportedOperationException();
            }
        };

        try {
            it.hasNext();
            return it;
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException)
                throw (IOException) ex.getCause();
            throw ex;
        }
    }
}
