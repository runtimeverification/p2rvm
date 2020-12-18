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

import bitronix.tm.TransactionManagerServices;
import bitronix.tm.utils.Uid;
import bitronix.tm.utils.UidGenerator;
import junit.framework.TestCase;

import javax.transaction.Status;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 *
 * @author Ludovic Orban
 */
public class DiskJournalTest extends TestCase {

    protected void setUp() throws Exception {
        new File(TransactionManagerServices.getConfiguration().getLogPart1Filename()).delete();
        new File(TransactionManagerServices.getConfiguration().getLogPart2Filename()).delete();
    }

    public void testExceptions() throws Exception {
        DiskJournal journal = new DiskJournal();

        try {
            journal.force();
            fail("expected IOException");
        } catch (IOException ex) {
            assertEquals("cannot force log writing, disk logger is not open", ex.getMessage());
        }
        try {
            journal.log(0, null, null);
            fail("expected IOException");
        } catch (IOException ex) {
            assertEquals("cannot write log, disk logger is not open", ex.getMessage());
        }
        try {
            journal.collectDanglingRecords();
            fail("expected IOException");
        } catch (IOException ex) {
            assertEquals("cannot collect dangling records, disk logger is not open", ex.getMessage());
        }

        journal.close();
        journal.shutdown();
    }

    public void testSimpleCollectDanglingRecords() throws Exception {
        DiskJournal journal = new DiskJournal();
        journal.open();
        Uid gtrid = UidGenerator.generateUid();

        assertEquals(0, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTING, gtrid, csvToSet("name1"));
        assertEquals(1, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTED, gtrid, csvToSet("name1"));
        assertEquals(0, journal.collectDanglingRecords().size());

        journal.close();
        journal.shutdown();
    }

    public void testComplexCollectDanglingRecords() throws Exception {
        DiskJournal journal = new DiskJournal();
        journal.open();
        Uid gtrid1 = UidGenerator.generateUid();
        Uid gtrid2 = UidGenerator.generateUid();

        assertEquals(0, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTING, gtrid1, csvToSet("name1,name2,name3"));
        assertEquals(1, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTED, gtrid1, csvToSet("name1"));
        assertEquals(1, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTED, gtrid1, csvToSet("name2"));
        assertEquals(1, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTED, gtrid1, csvToSet("name3"));
        assertEquals(0, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTING, gtrid2, csvToSet("name1,name2,name3"));
        assertEquals(1, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTED, gtrid2, csvToSet("name2"));
        assertEquals(1, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTED, gtrid2, csvToSet("name3,name1"));
        assertEquals(0, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTING, gtrid2, csvToSet("name1,name2,name3"));
        assertEquals(1, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_UNKNOWN, gtrid2, csvToSet("name2"));
        assertEquals(1, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTED, gtrid2, csvToSet("name1"));
        assertEquals(1, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_ROLLEDBACK, gtrid2, csvToSet("name3"));
        assertEquals(0, journal.collectDanglingRecords().size());

        journal.close();
        journal.shutdown();
    }

    public void testCorruptedCollectDanglingRecords() throws Exception {
        DiskJournal journal = new DiskJournal();
        journal.open();
        Uid gtrid1 = UidGenerator.generateUid();
        Uid gtrid2 = UidGenerator.generateUid();

        assertEquals(0, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTING, gtrid1, csvToSet("name1,name2,name3"));
        assertEquals(1, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTED, gtrid1, csvToSet("name1"));
        assertEquals(1, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTED, gtrid1, csvToSet("name3"));
        assertEquals(1, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTED, gtrid1, csvToSet("name4"));
        assertEquals(1, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTED, gtrid1, csvToSet("name2"));
        assertEquals(0, journal.collectDanglingRecords().size());

        journal.log(Status.STATUS_COMMITTED, gtrid2, csvToSet("name1"));
        assertEquals(0, journal.collectDanglingRecords().size());

        journal.close();
        journal.shutdown();
    }

    public void testCrc32Value() throws Exception {
        Set<String> names = new HashSet<String>();
        names.add("ActiveMQ");
        names.add("com.mysql.jdbc.jdbc2.optional.MysqlXADataSource");

        String uidString = "626974726F6E697853657276657249440000011C31FD45510000955B";
        byte[] uidArray = new byte[uidString.length()/2];
        for (int i=0; i<uidString.length()/2 ;i++) {
            String substr = uidString.substring(i*2, i*2+2);
            byte b = (byte)Integer.parseInt(substr, 16);

            uidArray[i] = b;
        }
        Uid uid = new Uid(uidArray);

        TransactionLogRecord tlr = new TransactionLogRecord(Status.STATUS_COMMITTED, 116, 28, 1220609394845L, 38266, -1380478121, uid, names, TransactionLogAppender.END_RECORD);
        boolean correct = tlr.isCrc32Correct();
        assertTrue("CRC32 values did not match", correct);

        names = new TreeSet<String>();
        names.add("com.mysql.jdbc.jdbc2.optional.MysqlXADataSource");
        names.add("ActiveMQ");

        tlr = new TransactionLogRecord(Status.STATUS_COMMITTED, 116, 28, 1220609394845L, 38266, -1380478121, uid, names, TransactionLogAppender.END_RECORD);
        assertTrue(tlr.isCrc32Correct());
    }

    public void testRollover() throws Exception {
    	TransactionManagerServices.getConfiguration().setMaxLogSizeInMb(1);
        DiskJournal journal = new DiskJournal();
        journal.open();

        List<Uid> uncommitted = new ArrayList<Uid>();
        for (int i = 1; i < 4000; i++) {
	        Uid gtrid = UidGenerator.generateUid();
	        journal.log(Status.STATUS_COMMITTING, gtrid, csvToSet("name1,name2,name3"));

	        if (i < 3600)
	        {
		        journal.log(Status.STATUS_COMMITTED, gtrid, csvToSet("name1"));
		        journal.log(Status.STATUS_COMMITTED, gtrid, csvToSet("name2"));
		        journal.log(Status.STATUS_COMMITTED, gtrid, csvToSet("name3"));
	        }
	        else
	        {
	        	uncommitted.add(gtrid);
	        }
        }

        Map<Uid, JournalRecord> danglingRecords = journal.collectDanglingRecords();
        assertEquals(400, danglingRecords.size());

        for (Uid gtrid : uncommitted) {
            journal.log(Status.STATUS_COMMITTED, gtrid, csvToSet("name1"));
	        journal.log(Status.STATUS_COMMITTED, gtrid, csvToSet("name2"));
	        journal.log(Status.STATUS_COMMITTED, gtrid, csvToSet("name3"));
        }

        assertEquals(0, journal.collectDanglingRecords().size());

        journal.shutdown();
    }

    public void testJournalPerformance() throws IOException, InterruptedException {
        TransactionManagerServices.getConfiguration().setMaxLogSizeInMb(40);
        TransactionManagerServices.getConfiguration().setForcedWriteEnabled(false);
        final DiskJournal journal = new DiskJournal();
        journal.open();

        final int threads = 4;
        final int count = 120000 / threads;
        
        class Runner extends Thread {
            private int ndx;

            Runner(int i) {
                this.ndx = i;
            }
            
            @Override
            public void run() {
                try {
                    SortedSet<String> set = csvToSet(String.format("%d.name1,%d.name2,%d.name3", ndx, ndx, ndx));
                    for (int i = 1; i < count; i++) {
                        Uid gtrid = UidGenerator.generateUid();
                        journal.log(Status.STATUS_COMMITTING, gtrid, set);
                        
                        journal.log(Status.STATUS_COMMITTED, gtrid, csvToSet(ndx + ".name1"));
                        journal.log(Status.STATUS_COMMITTED, gtrid, csvToSet(ndx + ".name2"));
                        journal.log(Status.STATUS_COMMITTED, gtrid, csvToSet(ndx + ".name3"));
                    }
                }
                catch (IOException io) {
                    fail(io.getMessage());
                }
            }
        };

        Runner[] runners = new Runner[threads];
        for (int i = 0; i < threads; i++) {
            runners[i] = new Runner(i);
            runners[i].start();
        }

        for (int i = 0; i < threads; i++) {
            runners[i].join();
        }

        journal.shutdown();
    }

    public void testRolloverStress() throws IOException, InterruptedException {
        TransactionManagerServices.getConfiguration().setMaxLogSizeInMb(1);
        final DiskJournal journal = new DiskJournal();
        journal.open();

        class Runner extends Thread {
        	private int ndx;

        	Runner(int i) {
        		this.ndx = i;
        	}

        	@Override
        	public void run() {
        		try {
        			SortedSet<String> set = csvToSet(String.format("%d.name1,%d.name2,%d.name3", ndx, ndx, ndx));
	        		for (int i = 1; i < 40000; i++) {
	        			Uid gtrid = UidGenerator.generateUid();
	        			journal.log(Status.STATUS_COMMITTING, gtrid, set);
                        journal.force();
	        			
	        			journal.log(Status.STATUS_COMMITTED, gtrid, csvToSet(ndx + ".name1"));
	        			journal.log(Status.STATUS_COMMITTED, gtrid, csvToSet(ndx + ".name2"));
	        			journal.log(Status.STATUS_COMMITTED, gtrid, csvToSet(ndx + ".name3"));
	        		}
        		}
        		catch (IOException io) {
        			fail(io.getMessage());
        		}
        	}
        };

        Runner[] runners = new Runner[4];
        for (int i = 0; i < 4; i++) {
        	runners[i] = new Runner(i);
        	runners[i].start();
        }

        for (int i = 0; i < 4; i++) {
        	runners[i].join();
        }

        journal.shutdown();
    }

    private SortedSet<String> csvToSet(String s) {
        SortedSet<String> result = new TreeSet<String>();
        String[] names = s.split("\\,");
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            result.add(name);
        }
        return result;
    }

}
