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
package bitronix.tm;

import bitronix.tm.internal.BitronixSystemException;
import bitronix.tm.internal.ThreadContext;
import bitronix.tm.internal.XAResourceManager;
import bitronix.tm.utils.ClassLoaderUtils;
import bitronix.tm.utils.Decoder;
import bitronix.tm.utils.InitializationException;
import bitronix.tm.utils.MonotonicClock;
import bitronix.tm.utils.Scheduler;
import bitronix.tm.utils.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.naming.StringRefAddr;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.InvalidTransactionException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import javax.transaction.UserTransaction;
import javax.transaction.xa.XAException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.NoSuchElementException;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation of {@link TransactionManager} and {@link UserTransaction}.
 *
 * @author Ludovic Orban
 */
public class BitronixTransactionManager implements TransactionManager, UserTransaction, Referenceable, Service {

    private final static Logger log = LoggerFactory.getLogger(BitronixTransactionManager.class);
    private final static String MDC_GTRID_KEY = "btm-gtrid";

    private final SortedMap<BitronixTransaction, ClearContextSynchronization> inFlightTransactions;

    private volatile boolean shuttingDown;

    /**
     * Create the {@link BitronixTransactionManager}. Open the journal, load resources and perform recovery
     * synchronously. The recovery service then gets scheduled for background recovery.
     */
    public BitronixTransactionManager() {
        try {
            shuttingDown = false;
            logVersion();
            Configuration configuration = TransactionManagerServices.getConfiguration();
            configuration.buildServerIdArray(); // first call will initialize the ServerId

            if (log.isDebugEnabled()) { log.debug("starting BitronixTransactionManager using " + configuration); }
            TransactionManagerServices.getJournal().open();
            TransactionManagerServices.getResourceLoader().init();
            TransactionManagerServices.getRecoverer().run();

            int backgroundRecoveryInterval = TransactionManagerServices.getConfiguration().getBackgroundRecoveryIntervalSeconds();
            if (backgroundRecoveryInterval < 1) {
                throw new InitializationException("invalid configuration value for backgroundRecoveryIntervalSeconds, found '" + backgroundRecoveryInterval + "' but it must be greater than 0");
            }

            inFlightTransactions = createInFlightTransactionsMap();

            if (log.isDebugEnabled()) { log.debug("recovery will run in the background every " + backgroundRecoveryInterval + " second(s)"); }
            Date nextExecutionDate = new Date(MonotonicClock.currentTimeMillis() + (backgroundRecoveryInterval * 1000L));
            TransactionManagerServices.getTaskScheduler().scheduleRecovery(TransactionManagerServices.getRecoverer(), nextExecutionDate);
        } catch (IOException ex) {
            throw new InitializationException("cannot open disk journal", ex);
        } catch (Exception ex) {
            TransactionManagerServices.getJournal().shutdown();
            TransactionManagerServices.getResourceLoader().shutdown();
            throw new InitializationException("initialization failed, cannot safely start the transaction manager", ex);
        }
    }

    private SortedMap<BitronixTransaction, ClearContextSynchronization> createInFlightTransactionsMap()
            throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        final boolean debug = log.isDebugEnabled();
        if (debug) { log.debug("Creating sorted memory storage for inflight transactions."); }

        final Comparator<BitronixTransaction> timestampSortComparator = new Comparator<BitronixTransaction>() {
                @Override
                public int compare(BitronixTransaction t1, BitronixTransaction t2) {
                    Long timestamp1 = t1.getResourceManager().getGtrid().extractTimestamp();
                    Long timestamp2 = t2.getResourceManager().getGtrid().extractTimestamp();

                    int compareTo = timestamp1.compareTo(timestamp2);
                    if (compareTo == 0 && !t1.getResourceManager().getGtrid().equals(t2.getResourceManager().getGtrid())) {
                        // if timestamps are equal, use the Uid as the tie-breaker.  the !equals() check above avoids an expensive string compare() here.
                        return t1.getGtrid().compareTo(t2.getGtrid());
                    }
                    return compareTo;
                }
            };

        if (debug) { log.debug("Attempting to use a concurrent sorted map of type 'ConcurrentSkipListMap' (from jre6 or custom supplied backport)"); }
        try {
            @SuppressWarnings("unchecked")
            SortedMap<BitronixTransaction, ClearContextSynchronization> mapInstance = (SortedMap<BitronixTransaction, ClearContextSynchronization>)
                    ClassLoaderUtils.loadClass("java.util.concurrent.ConcurrentSkipListMap").
                            getConstructor(Comparator.class).newInstance(timestampSortComparator);
            return mapInstance;
        } catch (ClassNotFoundException e) {
            if (debug) { log.debug("Concurrent sorted map 'ConcurrentSkipListMap' is not available. Falling back to a synchronized TreeMap."); }
            return Collections.synchronizedSortedMap(
                    new TreeMap<BitronixTransaction, ClearContextSynchronization>(timestampSortComparator));
        }
    }

    /**
     * Start a new transaction and bind the context to the calling thread.
     * @throws NotSupportedException if a transaction is already bound to the calling thread.
     * @throws SystemException if the transaction manager is shutting down.
     */
    @Override
    public void begin() throws NotSupportedException, SystemException {
        if (log.isDebugEnabled()) { log.debug("beginning a new transaction"); }
        if (isShuttingDown())
            throw new BitronixSystemException("cannot start a new transaction, transaction manager is shutting down");

        if (log.isDebugEnabled()) {
        	dumpTransactionContexts();
        }

        BitronixTransaction currentTx = getCurrentTransaction();
        if (currentTx != null)
            throw new NotSupportedException("nested transactions not supported");
        currentTx = createTransaction();

        ThreadContext threadContext = ThreadContext.getThreadContext();
        ClearContextSynchronization clearContextSynchronization = new ClearContextSynchronization(currentTx, threadContext);
        try {
            currentTx.getSynchronizationScheduler().add(clearContextSynchronization, Scheduler.ALWAYS_LAST_POSITION -1);
            currentTx.setActive(threadContext.getTimeout());
            inFlightTransactions.put(currentTx, clearContextSynchronization);
            if (log.isDebugEnabled()) { log.debug("begun new transaction at " + new Date(currentTx.getResourceManager().getGtrid().extractTimestamp())); }
        } catch (RuntimeException ex) {
            clearContextSynchronization.afterCompletion(Status.STATUS_NO_TRANSACTION);
            throw ex;
        } catch (SystemException ex) {
            clearContextSynchronization.afterCompletion(Status.STATUS_NO_TRANSACTION);
            throw ex;
        }
    }

    @Override
    public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {
        BitronixTransaction currentTx = getCurrentTransaction();
        if (log.isDebugEnabled()) { log.debug("committing transaction " + currentTx); }
        if (currentTx == null)
            throw new IllegalStateException("no transaction started on this thread");

        currentTx.commit();
    }

    @Override
    public void rollback() throws IllegalStateException, SecurityException, SystemException {
        BitronixTransaction currentTx = getCurrentTransaction();
        if (log.isDebugEnabled()) { log.debug("rolling back transaction " + currentTx); }
        if (currentTx == null)
            throw new IllegalStateException("no transaction started on this thread");

        currentTx.rollback();
    }

    @Override
    public int getStatus() throws SystemException {
        BitronixTransaction currentTx = getCurrentTransaction();
        if (currentTx == null)
           return Status.STATUS_NO_TRANSACTION;

        return currentTx.getStatus();
    }

    @Override
    public Transaction getTransaction() throws SystemException {
        return getCurrentTransaction();
    }

    @Override
    public void setRollbackOnly() throws IllegalStateException, SystemException {
        BitronixTransaction currentTx = getCurrentTransaction();
        if (log.isDebugEnabled()) { log.debug("marking transaction as rollback only: " + currentTx); }
        if (currentTx == null)
            throw new IllegalStateException("no transaction started on this thread");

        currentTx.setRollbackOnly();
    }

    @Override
    public void setTransactionTimeout(int seconds) throws SystemException {
        if (seconds < 0)
            throw new BitronixSystemException("cannot set a timeout to less than 0 second (was: " + seconds + "s)");
        ThreadContext.getThreadContext().setTimeout(seconds);
    }

    @Override
    public Transaction suspend() throws SystemException {
        BitronixTransaction currentTx = getCurrentTransaction();
        if (log.isDebugEnabled()) { log.debug("suspending transaction " + currentTx); }
        if (currentTx == null)
            return null;

        try {
            currentTx.getResourceManager().suspend();
            clearCurrentContextForSuspension();
            inFlightTransactions.get(currentTx).setThreadContext(null);
            MDC.remove(MDC_GTRID_KEY);
            return currentTx;
        } catch (XAException ex) {
            String extraErrorDetails = TransactionManagerServices.getExceptionAnalyzer().extractExtraXAExceptionDetails(ex);
            throw new BitronixSystemException("cannot suspend " + currentTx + ", error=" + Decoder.decodeXAExceptionErrorCode(ex) +
                    (extraErrorDetails == null ? "" : ", extra error=" + extraErrorDetails), ex);
        }
    }

    @Override
    public void resume(Transaction transaction) throws InvalidTransactionException, IllegalStateException, SystemException {
        if (log.isDebugEnabled()) { log.debug("resuming " + transaction); }
        if (transaction == null)
            throw new InvalidTransactionException("resumed transaction cannot be null");
        if (!(transaction instanceof BitronixTransaction))
            throw new InvalidTransactionException("resumed transaction must be an instance of BitronixTransaction");

        BitronixTransaction tx = (BitronixTransaction) transaction;
        if (getCurrentTransaction() != null)
            throw new IllegalStateException("a transaction is already running on this thread");

        try {
            XAResourceManager resourceManager = tx.getResourceManager();
            resourceManager.resume();
            ThreadContext threadContext = ThreadContext.getThreadContext();
            threadContext.setTransaction(tx);
            inFlightTransactions.get(tx).setThreadContext(threadContext);
            MDC.put(MDC_GTRID_KEY, tx.getGtrid());
        } catch (XAException ex) {
            String extraErrorDetails = TransactionManagerServices.getExceptionAnalyzer().extractExtraXAExceptionDetails(ex);
            throw new BitronixSystemException("cannot resume " + tx + ", error=" + Decoder.decodeXAExceptionErrorCode(ex) +
                    (extraErrorDetails == null ? "" : ", extra error=" + extraErrorDetails), ex);
        }
    }


    /**
     * BitronixTransactionManager can only have a single instance per JVM so this method always returns a reference
     * with no special information to find back the sole instance. BitronixTransactionManagerObjectFactory will be used
     * by the JNDI server to get the BitronixTransactionManager instance of the JVM.
     *
     * @return an empty reference to get the BitronixTransactionManager.
     */
    @Override
    public Reference getReference() throws NamingException {
        return new Reference(
                BitronixTransactionManager.class.getName(),
                new StringRefAddr("TransactionManager", "BitronixTransactionManager"),
                BitronixTransactionManagerObjectFactory.class.getName(),
                null
        );
    }

    /**
     * Return a count of the current in-flight transactions.  Currently this method is only called by unit tests.
     * @return a count of in-flight transactions
     */
    public int getInFlightTransactionCount() {
        return inFlightTransactions.size();
    }

    /**
     * Return the timestamp of the oldest in-flight transaction.
     * @return the timestamp or Long.MIN_VALUE if there is no in-flight transaction.
     */
    public long getOldestInFlightTransactionTimestamp() {
        try {
        	// The inFlightTransactions map is sorted by timestamp, so the first transaction is always the oldest
        	BitronixTransaction oldestTransaction = inFlightTransactions.firstKey();
        	long oldestTimestamp = oldestTransaction.getResourceManager().getGtrid().extractTimestamp();
        	if (log.isDebugEnabled()) { log.debug("oldest in-flight transaction's timestamp: " + oldestTimestamp); }
        	return oldestTimestamp;

        } catch (NoSuchElementException e) {
        	if (log.isDebugEnabled()) { log.debug("oldest in-flight transaction's timestamp: " + Long.MIN_VALUE); }
        	return Long.MIN_VALUE;
        }
    }

    /**
     * Get the transaction currently registered on the current thread context.
     * @return the current transaction or null if no transaction has been started on the current thread.
     */
    public BitronixTransaction getCurrentTransaction() {
        return ThreadContext.getThreadContext().getTransaction();
    }

    /**
     * Check if the transaction manager is in the process of shutting down.
     * @return true if the transaction manager is in the process of shutting down.
     */
    private boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * Dump an overview of all running transactions as debug logs.
     */
    public void dumpTransactionContexts() {
        if (!log.isDebugEnabled())
            return;

        // We're using an iterator, so we must synchronize on the collection
    	synchronized (inFlightTransactions) {
	        log.debug("dumping " + inFlightTransactions.size() + " transaction context(s)");
	        for (BitronixTransaction tx : inFlightTransactions.keySet()) {
	            log.debug(tx.toString());
	        }
    	}
    }

    /**
     * Shut down the transaction manager and release all resources held by it.
     * <p>This call will also close the resources pools registered by the {@link bitronix.tm.resource.ResourceLoader}
     * like JMS and JDBC pools. The manually created ones are left untouched.</p>
     * <p>The Transaction Manager will wait during a configurable graceful period before forcibly killing active
     * transactions.</p>
     * After this method is called, attempts to create new transactions (via calls to
     * {@link javax.transaction.TransactionManager#begin()}) will be rejected with a {@link SystemException}.
     * @see Configuration#getGracefulShutdownInterval()
     */
    @Override
    public synchronized void shutdown() {
        if (isShuttingDown()) {
            if (log.isDebugEnabled()) { log.debug("Transaction Manager has already shut down"); }
            return;
        }

        log.info("shutting down Bitronix Transaction Manager");
        internalShutdown();

        if (log.isDebugEnabled()) { log.debug("shutting down resource loader"); }
        TransactionManagerServices.getResourceLoader().shutdown();

        if (log.isDebugEnabled()) { log.debug("shutting down executor"); }
        TransactionManagerServices.getExecutor().shutdown();

        if (log.isDebugEnabled()) { log.debug("shutting down task scheduler"); }
        TransactionManagerServices.getTaskScheduler().shutdown();

        if (log.isDebugEnabled()) { log.debug("shutting down journal"); }
        TransactionManagerServices.getJournal().shutdown();

        if (log.isDebugEnabled()) { log.debug("shutting down recoverer"); }
        TransactionManagerServices.getRecoverer().shutdown();

        if (log.isDebugEnabled()) { log.debug("shutting down configuration"); }
        TransactionManagerServices.getConfiguration().shutdown();

        // clear references
        TransactionManagerServices.clear();

        if (log.isDebugEnabled()) { log.debug("shutdown ran successfully"); }
    }

    private void internalShutdown() {
        shuttingDown = true;
        dumpTransactionContexts();

        int seconds = TransactionManagerServices.getConfiguration().getGracefulShutdownInterval();
        int txCount = 0;
        try {
            txCount = inFlightTransactions.size();
            while (seconds > 0  &&  txCount > 0) {
                if (log.isDebugEnabled()) { log.debug("still " + txCount + " in-flight transactions, waiting... (" + seconds + " second(s) left)"); }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    // ignore
                }
                seconds--;
                txCount = inFlightTransactions.size();
            }
        } catch (Exception ex) {
            log.error("cannot get a list of in-flight transactions", ex);
        }

        if (txCount > 0) {
            if (log.isDebugEnabled()) {
            	log.debug("still " + txCount + " in-flight transactions, shutting down anyway");
            	dumpTransactionContexts();
            }
        }
        else {
            if (log.isDebugEnabled()) { log.debug("all transactions finished, resuming shutdown"); }
        }
    }

    @Override
    public String toString() {
        return "a BitronixTransactionManager with " + inFlightTransactions.size() + " in-flight transaction(s)";
    }

    /*
    * Internal impl
    */

    /**
     * Output BTM version information as INFO log.
     */
    private void logVersion() {
        log.info("Bitronix Transaction Manager version " + Version.getVersion());
        if (log.isDebugEnabled()) { log.debug("JVM version " + System.getProperty("java.version")); }
    }

    /**
     * Create a new transaction on the current thread's context.
     * @return the created transaction.
     */
    private BitronixTransaction createTransaction() {
        BitronixTransaction transaction = new BitronixTransaction();
        ThreadContext.getThreadContext().setTransaction(transaction);
        MDC.put(MDC_GTRID_KEY, transaction.getGtrid());

        return transaction;
    }

    /**
     * Unlink the transaction from the current thread's context.
     */
    private void clearCurrentContextForSuspension() {
        if (log.isDebugEnabled()) { log.debug("clearing current thread context: " + ThreadContext.getThreadContext()); }
        ThreadContext.getThreadContext().clearTransaction();
    }

    private final class ClearContextSynchronization implements Synchronization {
        private final BitronixTransaction currentTx;
        private final AtomicReference<ThreadContext> threadContext;

        public ClearContextSynchronization(BitronixTransaction currentTx, ThreadContext threadContext) {
            this.currentTx = currentTx;
            this.threadContext = new AtomicReference<ThreadContext>(threadContext);
        }

        @Override
        public void beforeCompletion() {
        }

        @Override
        public void afterCompletion(int status) {
        	ThreadContext context = threadContext.get();
        	if (context != null) {
	            if (log.isDebugEnabled()) { log.debug("clearing transaction from thread context: " + context); }
	            context.clearTransaction();
        	}
        	else {
        		if (log.isDebugEnabled()) { log.debug("thread context was null when clear context synchronization executed"); }
        	}
            if (log.isDebugEnabled()) { log.debug("removing transaction from in-flight transactions: " + currentTx); }
            inFlightTransactions.remove(currentTx);
            MDC.remove(MDC_GTRID_KEY);
        }

        public void setThreadContext(ThreadContext threadContext) {
        	this.threadContext.set(threadContext);
        }

        @Override
        public String toString() {
            return "a ClearContextSynchronization for " + currentTx;
        }
    }
}
