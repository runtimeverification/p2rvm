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

import bitronix.tm.utils.Decoder;
import bitronix.tm.utils.MonotonicClock;
import bitronix.tm.utils.Uid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * Representation of a transaction log record on disk.
 * <p>On-disk format has been implemented following Mike Spille's recommendations. Quoting him:</p>
 * <p>
 * <p><code>
 * [RECORD_TYPE :4]
 * [RECORD_LEN :4]
 * [HEADER_LEN :4]
 * [System.currentTimeMillis :8]
 * [Sequence number :4]
 * [Checksum :4]
 * [Payload :X]
 * [END_RECORD_INDICATOR :4]
 * </code></p>
 * <p>Where [RECORD_TYPE] is a passed-in record type from the TM. [RECORD_LEN] is the overall record length
 * (sans [RECORD_TYPE and [RECORD_LEN]). [HEADER_LEN] is the length of the remainder of the header - important if you
 * want to support easy upgrades of your format. The remaining pieces are the rest of the header, and the payload. The
 * header at least should have [System.currentTimeMillis] and [Sequence number], with the [sequence number] coming from
 * some monotically increasing sequence generator unique to the process. The [checksum] is optional for the paranoid
 * among us. The time information can be very useful for profiling and tracking down problems in production, and in
 * conjunction with the sequence number it can give you precise ordering. This doesn't give you much in this solution,
 * but can be priceless if you ever move to a system with multiple dual log file pairs to lessen single-threading on a
 * single log file pair. Finally, I like having an [END_RECORD_INDICATOR] as an extra corruption detector device - I'm
 * a suspenders and belt kind of guy. Actually, the END_RECORD_INDICATOR and [RECORD_LEN] in conjunction are very
 * useful in development, as well, to catch programming mistakes in the log system early.<br>
 * 
 * <p>Payload contains <code>[GTRID LENGTH :1] [GTRID :A] [UNIQUE NAMES COUNT :4] ([UNIQUE NAME LENGTH :2] [UNIQUE NAME :Y] ...)</code>
 * which makes a major difference with Mike's proposed format because here a record can vary in length: the GTRID size
 * is A bytes long (A being the GTRID length) and there can be X unique names that are Y characters long, Y being eventually
 * different for each name.</p>
 *
 * @see <a href="http://jroller.com/page/pyrasun?entry=xa_exposed_part_iii_the">XA Exposed, Part III: The Implementor's Notebook</a>
 * @author Ludovic Orban
 */
public class TransactionLogRecord implements JournalRecord {

    private final static Logger log = LoggerFactory.getLogger(TransactionLogRecord.class);

    // status + record length + record header length + current time + sequence number + checksum
    private final static int RECORD_HEADER_LENGTH = 4 + 4 + 4 + 8 + 4 + 4;

    private static final Charset US_ASCII = Charset.forName("US-ASCII");

    private final static AtomicInteger sequenceGenerator = new AtomicInteger();

    private final int status;
    private int recordLength;
    private final int headerLength;
    private final long time;
    private final int sequenceNumber;
    private int crc32;
    private final Uid gtrid;
    private final SortedSet<String> uniqueNames;
    private final int endRecord;
    private long writePosition;

    /**
     * Use this constructor when restoring a log from the disk.
     *
     * @param status record type
     * @param recordLength record length excluding status and recordLength
     * @param headerLength length of all fields except gtrid, uniqueNames and endRecord
     * @param time current time in milliseconds
     * @param sequenceNumber atomically generated sequence number during a JVM's lifespan
     * @param crc32 checksum of the full record
     * @param gtrid global transaction id
     * @param uniqueNames unique names of XA data sources used in this transaction
     * @param endRecord end of record marker
     */
    public TransactionLogRecord(int status, int recordLength, int headerLength, long time, int sequenceNumber, int crc32, Uid gtrid, Set<String> uniqueNames, int endRecord) {
        this.status = status;
        this.recordLength = recordLength;
        this.headerLength = headerLength;
        this.time = time;
        this.sequenceNumber = sequenceNumber;
        this.crc32 = crc32;
        this.gtrid = gtrid;
        this.uniqueNames = new TreeSet<String>(uniqueNames);
        this.endRecord = endRecord;
    }

    /**
     * Create a new transaction log ready to be stored.
     * @param status record type
     * @param gtrid global transaction id
     * @param uniqueNames unique names of XA data sources used in this transaction
     */
    public TransactionLogRecord(int status, Uid gtrid, Set<String> uniqueNames) {
        this.status = status;
        this.time = MonotonicClock.currentTimeMillis();
        this.sequenceNumber = sequenceGenerator.incrementAndGet();
        this.gtrid = gtrid;
        this.uniqueNames = new TreeSet<String>(uniqueNames);
        this.endRecord = TransactionLogAppender.END_RECORD;
        this.headerLength = RECORD_HEADER_LENGTH;

        refresh();
    }

    @Override
    public int getStatus() {
        return status;
    }

    public int getRecordLength() {
        return recordLength;
    }

    public int getHeaderLength() {
        return headerLength;
    }

    @Override
    public long getTime() {
        return time;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public int getCrc32() {
        return crc32;
    }

    @Override
    public Uid getGtrid() {
        return gtrid;
    }

    public long getWritePosition() {
        return writePosition;
    }

    public void setWritePosition(long position) {
        writePosition = position;
    }

    @Override
    public Set<String> getUniqueNames() {
        return Collections.unmodifiableSortedSet(uniqueNames);
    }

    public int getEndRecord() {
        return endRecord;
    }

    /**
     * Recalculate and store the dynamic values of this record: {@link #getRecordLength()}, {@link #getRecordHeaderLength()}
     * and {@link #calculateCrc32()}. This method must be called each time after the set of contained unique names is updated.
     */
    private void refresh() {
        crc32 = calculateCrc32();
    }

    /**
     * Recalculate the CRC32 value of this record (using {@link #calculateCrc32()}) and compare it with the stored value.
     * @return true if the recalculated value equals the stored one, false otherwise.
     */
    public boolean isCrc32Correct() {
        return calculateCrc32() == getCrc32();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isValid() {
        return isCrc32Correct();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, ?> getRecordProperties() {
        Map<String, Object> props = new LinkedHashMap<String, Object>(4);
        props.put("recordLength", recordLength);
        props.put("headerLength", headerLength);
        props.put("sequenceNumber", sequenceNumber);
        props.put("crc32", crc32);
        return props;
    }

    /**
     * Calculate the CRC32 value of this record.
     * @return the CRC32 value of this record.
     */
    public int calculateCrc32() {
        int total = 0;
        for (String uniqueName : uniqueNames) {
        	total += 2 + uniqueName.length(); // 2 bytes for storing the unique name length + unique name length
        }
        recordLength = total + getFixedRecordLength();

    	ByteBuffer buf = ByteBuffer.allocate(24 + gtrid.length() + 4 /*uniqueNames.size*/ + total + 4 /*endRecord*/);
    	buf.putInt(status);              // offset: 0
    	buf.putInt(recordLength);        // offset: 4
    	buf.putInt(headerLength);        // offset: 8
    	buf.putLong(time);               // offset: 12
    	buf.putInt(sequenceNumber);      // offset: 20
    	buf.put(gtrid.getArray());       // offset: 24
    	buf.putInt(uniqueNames.size());  // offset: 24 + gtridArray.length

        for (String name : uniqueNames) {
            buf.putShort((short) name.length());
            buf.put(name.getBytes(US_ASCII));
        }

        buf.putInt(endRecord);

        CRC32 crc32 = new CRC32();
        crc32.update(buf.array());
        return (int) crc32.getValue();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);

        sb.append("a Bitronix TransactionLogRecord with ");
        sb.append("status="); sb.append(Decoder.decodeStatus(status)); sb.append(", ");
        sb.append("recordLength="); sb.append(recordLength); sb.append(", ");
        sb.append("headerLength="); sb.append(headerLength); sb.append(", ");
        sb.append("time="); sb.append(time); sb.append(", ");
        sb.append("sequenceNumber="); sb.append(sequenceNumber); sb.append(", ");
        sb.append("crc32="); sb.append(crc32); sb.append(", ");
        sb.append("gtrid="); sb.append(gtrid.toString()); sb.append(", ");
        sb.append("uniqueNames=");
        Iterator<String> it = uniqueNames.iterator();
        while (it.hasNext()) {
            String s = it.next();
            sb.append(s);
            if (it.hasNext())
                sb.append(',');
        }

        return sb.toString();
    }


    /**
     * this is the total size on disk of a TransactionLog.
     * @return recordLength
     */
    int calculateTotalRecordSize() {
        return recordLength + 4 + 4; // + status + record length
    }

    /**
     * Length of all the fixed size fields part of the record length header except status and record length.
     * @return fixedRecordLength
     */
    private int getFixedRecordLength() {
     // record header length + current time + sequence number + checksum + GTRID size + GTRID + unique names count + end record marker
        return 4 + 8 + 4 + 4 + 1 + gtrid.length() + 4 + 4;
    }

    static class NullOutputStream extends OutputStream {
        static final NullOutputStream INSTANCE = new NullOutputStream();

        private NullOutputStream() {
        }

        @Override
        public void write(int b) throws IOException {
        }
    }
}
