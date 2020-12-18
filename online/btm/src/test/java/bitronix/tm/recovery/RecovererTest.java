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
package bitronix.tm.recovery;

import bitronix.tm.BitronixTransaction;
import bitronix.tm.BitronixTransactionManager;
import bitronix.tm.BitronixXid;
import bitronix.tm.TransactionManagerServices;
import bitronix.tm.internal.TransactionStatusChangeListener;
import bitronix.tm.journal.Journal;
import bitronix.tm.mock.events.Event;
import bitronix.tm.mock.events.EventRecorder;
import bitronix.tm.mock.events.JournalLogEvent;
import bitronix.tm.mock.resource.MockJournal;
import bitronix.tm.mock.resource.MockXAResource;
import bitronix.tm.mock.resource.MockXid;
import bitronix.tm.mock.resource.jdbc.MockitoXADataSource;
import bitronix.tm.resource.ResourceRegistrar;
import bitronix.tm.resource.common.ResourceBean;
import bitronix.tm.resource.jdbc.JdbcPooledConnection;
import bitronix.tm.resource.jdbc.PooledConnectionProxy;
import bitronix.tm.resource.jdbc.PoolingDataSource;
import bitronix.tm.utils.Uid;
import bitronix.tm.utils.UidGenerator;
import junit.framework.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.Status;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.io.File;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 * @author Ludovic Orban
 */
public class RecovererTest extends TestCase {
    private final static Logger log = LoggerFactory.getLogger(RecovererTest.class);

    private MockXAResource xaResource;
    private PoolingDataSource pds;
    private Journal journal;

    @Override
    protected void setUp() throws Exception {
        Iterator<String> it = ResourceRegistrar.getResourcesUniqueNames().iterator();
        while (it.hasNext()) {
            String name = it.next();
            ResourceRegistrar.unregister(ResourceRegistrar.get(name));
        }

        pds = new PoolingDataSource();
        pds.setClassName(MockitoXADataSource.class.getName());
        pds.setUniqueName("mock-xads");
        pds.setMinPoolSize(1);
        pds.setMaxPoolSize(1);
        pds.init();

        new File(TransactionManagerServices.getConfiguration().getLogPart1Filename()).delete();
        new File(TransactionManagerServices.getConfiguration().getLogPart2Filename()).delete();
        EventRecorder.clear();

        Connection connection1 = pds.getConnection();
        PooledConnectionProxy handle = (PooledConnectionProxy) connection1;
        xaResource = (MockXAResource) handle.getPooledConnection().getXAResource();
        connection1.close();

        // test the clustered recovery as its logic is more complex and covers the non-clustered logic
        TransactionManagerServices.getConfiguration().setCurrentNodeOnlyRecovery(true);

        // recoverer needs the journal to be open to be run manually
        journal = TransactionManagerServices.getJournal();
        journal.open();
    }

    @Override
    protected void tearDown() throws Exception {
        if (TransactionManagerServices.isTransactionManagerRunning())
            TransactionManagerServices.getTransactionManager().shutdown();

        journal.close();
        pds.close();
        TransactionManagerServices.getJournal().close();
        new File(TransactionManagerServices.getConfiguration().getLogPart1Filename()).delete();
        new File(TransactionManagerServices.getConfiguration().getLogPart2Filename()).delete();
        EventRecorder.clear();
    }

    /**
     * Create 3 XIDs on the resource that are not in the journal -> recoverer presumes they have aborted and rolls
     * them back.
     * @throws Exception
     */
    public void testRecoverPresumedAbort() throws Exception {
        byte[] gtrid = UidGenerator.generateUid().getArray();

        xaResource.addInDoubtXid(new MockXid(0, gtrid, BitronixXid.FORMAT_ID));
        xaResource.addInDoubtXid(new MockXid(1, gtrid, BitronixXid.FORMAT_ID));
        xaResource.addInDoubtXid(new MockXid(2, gtrid, BitronixXid.FORMAT_ID));

        TransactionManagerServices.getRecoverer().run();

        assertEquals(0, TransactionManagerServices.getRecoverer().getCommittedCount());
        assertEquals(3, TransactionManagerServices.getRecoverer().getRolledbackCount());
        assertEquals(0, xaResource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN).length);
    }

    /**
     * Create 3 XIDs on the resource that are not in the journal -> recoverer presumes they have aborted and rolls
     * them back.
     * @throws Exception
     */
    public void testIncrementalRecoverPresumedAbort() throws Exception {
        byte[] gtrid = UidGenerator.generateUid().getArray();

        xaResource.addInDoubtXid(new MockXid(0, gtrid, BitronixXid.FORMAT_ID));
        xaResource.addInDoubtXid(new MockXid(1, gtrid, BitronixXid.FORMAT_ID));
        xaResource.addInDoubtXid(new MockXid(2, gtrid, BitronixXid.FORMAT_ID));

        IncrementalRecoverer.recover(pds);

        assertEquals(0, xaResource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN).length);
    }

    /**
     * Create 3 XIDs on the resource that are in the journal -> recoverer commits them.
     * @throws Exception
     */
    public void testRecoverCommitting() throws Exception {
        Xid xid0 = new MockXid(0, UidGenerator.generateUid().getArray(), BitronixXid.FORMAT_ID);
        xaResource.addInDoubtXid(xid0);
        Xid xid1 = new MockXid(1, UidGenerator.generateUid().getArray(), BitronixXid.FORMAT_ID);
        xaResource.addInDoubtXid(xid1);
        Xid xid2 = new MockXid(2, UidGenerator.generateUid().getArray(), BitronixXid.FORMAT_ID);
        xaResource.addInDoubtXid(xid2);

        Set<String> names = new HashSet<String>();
        names.add(pds.getUniqueName());
        journal.log(Status.STATUS_COMMITTING, new Uid(xid0.getGlobalTransactionId()), names);
        journal.log(Status.STATUS_COMMITTING, new Uid(xid1.getGlobalTransactionId()), names);
        journal.log(Status.STATUS_COMMITTING, new Uid(xid2.getGlobalTransactionId()), names);
        TransactionManagerServices.getRecoverer().run();

        assertEquals(3, TransactionManagerServices.getRecoverer().getCommittedCount());
        assertEquals(0, TransactionManagerServices.getRecoverer().getRolledbackCount());
        assertEquals(0, xaResource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN).length);
    }

    /**
     * Create 3 XIDs on the resource that are in the journal -> recoverer commits them.
     * @throws Exception
     */
    public void testIncrementalRecoverCommitting() throws Exception {
        Xid xid0 = new MockXid(0, UidGenerator.generateUid().getArray(), BitronixXid.FORMAT_ID);
        xaResource.addInDoubtXid(xid0);
        Xid xid1 = new MockXid(1, UidGenerator.generateUid().getArray(), BitronixXid.FORMAT_ID);
        xaResource.addInDoubtXid(xid1);
        Xid xid2 = new MockXid(2, UidGenerator.generateUid().getArray(), BitronixXid.FORMAT_ID);
        xaResource.addInDoubtXid(xid2);

        Set<String> names = new HashSet<String>();
        names.add(pds.getUniqueName());
        journal.log(Status.STATUS_COMMITTING, new Uid(xid0.getGlobalTransactionId()), names);
        journal.log(Status.STATUS_COMMITTING, new Uid(xid1.getGlobalTransactionId()), names);
        journal.log(Status.STATUS_COMMITTING, new Uid(xid2.getGlobalTransactionId()), names);

        IncrementalRecoverer.recover(pds);

        assertEquals(0, xaResource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN).length);
    }

    public void testSkipInFlightRollback() throws Exception {
        BitronixTransactionManager btm = TransactionManagerServices.getTransactionManager();

        Uid uid0 = UidGenerator.generateUid();
        Xid xid0 = new MockXid(0, uid0.getArray(), BitronixXid.FORMAT_ID);
        xaResource.addInDoubtXid(xid0);

        assertNull(btm.getCurrentTransaction());
        Thread.sleep(30); // let the clock run a bit so that in-flight TX is a bit older than the journaled one
        btm.begin();

        Xid xid1 = new MockXid(1, UidGenerator.generateUid().getArray(), BitronixXid.FORMAT_ID);
        xaResource.addInDoubtXid(xid1);

        TransactionManagerServices.getRecoverer().run();

        btm.rollback();

        assertEquals(0, TransactionManagerServices.getRecoverer().getCommittedCount());
        assertEquals(1, TransactionManagerServices.getRecoverer().getRolledbackCount());
        assertEquals(1, xaResource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN).length);

        btm.shutdown();
        TransactionManagerServices.getJournal().open();
        TransactionManagerServices.getRecoverer().run();

        assertEquals(0, TransactionManagerServices.getRecoverer().getCommittedCount());
        assertEquals(1, TransactionManagerServices.getRecoverer().getRolledbackCount());
        assertEquals(0, xaResource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN).length);
    }

    public void testSkipInFlightCommit() throws Exception {
        BitronixTransactionManager btm = TransactionManagerServices.getTransactionManager();

        Uid uid0 = UidGenerator.generateUid();
        Xid xid0 = new MockXid(0, uid0.getArray(), BitronixXid.FORMAT_ID);
        xaResource.addInDoubtXid(xid0);
        Set<String> names = new HashSet<String>();
        names.add(pds.getUniqueName());
        journal.log(Status.STATUS_COMMITTING, new Uid(xid0.getGlobalTransactionId()), names);

        assertNull(btm.getCurrentTransaction());
        Thread.sleep(30); // let the clock run a bit so that in-flight TX is a bit older than the journaled one
        btm.begin();

        Xid xid1 = new MockXid(1, UidGenerator.generateUid().getArray(), BitronixXid.FORMAT_ID);
        xaResource.addInDoubtXid(xid1);

        names = new HashSet<String>();
        names.add(pds.getUniqueName());
        journal.log(Status.STATUS_COMMITTING, new Uid(xid1.getGlobalTransactionId()), names);

        TransactionManagerServices.getRecoverer().run();

        btm.rollback();

        assertEquals(1, xaResource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN).length);

        btm.shutdown();
        TransactionManagerServices.getJournal().open();
        TransactionManagerServices.getRecoverer().run();

        assertEquals(0, xaResource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN).length);
    }

    public void testRecoverMissingResource() throws Exception {
        final Xid xid0 = new MockXid(0, UidGenerator.generateUid().getArray(), BitronixXid.FORMAT_ID);
        xaResource.addInDoubtXid(xid0);

        Set<String> names = new HashSet<String>();
        names.add("no-such-registered-resource");
        journal.log(Status.STATUS_COMMITTING, new Uid(xid0.getGlobalTransactionId()), names);
        assertEquals(1, TransactionManagerServices.getJournal().collectDanglingRecords().size());

        // the TM must run the recoverer in this scenario
        TransactionManagerServices.getTransactionManager();

        assertEquals(1, TransactionManagerServices.getJournal().collectDanglingRecords().size());
        assertNull(TransactionManagerServices.getRecoverer().getCompletionException());
        assertEquals(0, TransactionManagerServices.getRecoverer().getCommittedCount());
        assertEquals(1, TransactionManagerServices.getRecoverer().getRolledbackCount());
        assertEquals(0, xaResource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN).length);


        // the TM is running, adding this resource will kick incremental recovery on it
        PoolingDataSource pds = new PoolingDataSource() {
            @Override
            public JdbcPooledConnection createPooledConnection(Object xaFactory, ResourceBean bean) throws Exception {
                JdbcPooledConnection pc = super.createPooledConnection(xaFactory, bean);
                MockXAResource xaResource = (MockXAResource) pc.getXAResource();
                xaResource.addInDoubtXid(UidGenerator.generateXid(new Uid(xid0.getGlobalTransactionId())));
                return pc;
            }
        };
        pds.setClassName(MockitoXADataSource.class.getName());
        pds.setUniqueName("no-such-registered-resource");
        pds.setMinPoolSize(1);
        pds.setMaxPoolSize(1);
        pds.init();

        Connection connection = pds.getConnection();
        PooledConnectionProxy handle = (PooledConnectionProxy) connection;
        XAResource xaResource = handle.getPooledConnection().getXAResource();
        connection.close();

        assertEquals(0, xaResource.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN).length);
        assertEquals(0, TransactionManagerServices.getJournal().collectDanglingRecords().size());

        pds.close();

        TransactionManagerServices.getTransactionManager().shutdown();
    }

    volatile boolean listenerExecuted = false;
    public void testBackgroundRecovererSkippingInFlightTransactions() throws Exception {
        // change disk journal into mock journal
        Field field = TransactionManagerServices.class.getDeclaredField("journalRef");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<Journal> journalRef = (AtomicReference<Journal>) field.get(TransactionManagerServices.class);
        journalRef.set(new MockJournal());

        pds.setMaxPoolSize(2);
        BitronixTransactionManager btm = TransactionManagerServices.getTransactionManager();
        final Recoverer recoverer = TransactionManagerServices.getRecoverer();

        try {
            btm.begin();

            BitronixTransaction tx = btm.getCurrentTransaction();
            tx.addTransactionStatusChangeListener(new TransactionStatusChangeListener() {
                @Override
                public void statusChanged(int oldStatus, int newStatus) {
                    if (newStatus != Status.STATUS_COMMITTING)
                        return;

                    recoverer.run();
                    assertEquals(0, recoverer.getCommittedCount());
                    assertEquals(0, recoverer.getRolledbackCount());
                    assertNull(recoverer.getCompletionException());
                    listenerExecuted = true;
                }
            });

            Connection c = pds.getConnection();
            c.createStatement();
            c.close();

            xaResource.addInDoubtXid(new MockXid(new byte[] {0, 1, 2}, tx.getResourceManager().getGtrid().getArray(), BitronixXid.FORMAT_ID));

            btm.commit();
        }
        finally {
            btm.shutdown();
        }

        assertTrue("recoverer did not run between phases 1 and 2", listenerExecuted);

        int committedCount = 0;

        List events = EventRecorder.getOrderedEvents();
        for (int i = 0; i < events.size(); i++) {
            Event event = (Event) events.get(i);
            if (event instanceof JournalLogEvent) {
                if (((JournalLogEvent) event).getStatus() == Status.STATUS_COMMITTED)
                    committedCount++;
            }
        }

        assertEquals("TX has been committed more or less times than just once", 1, committedCount);
    }


    public void testReentrance() throws Exception {
        log.debug("Start test RecovererTest.testReentrance()");
        final int THREAD_COUNT = 10;
        Recoverer recoverer = new Recoverer();
        xaResource.setRecoveryDelay(1000);

        List<Thread> threads = new ArrayList<Thread>();

        //create
        for (int i=0; i< THREAD_COUNT;i++) {
            Thread t = new Thread(recoverer);
            threads.add(t);
        }

        //start
        for (int i=0; i< THREAD_COUNT;i++) {
            Thread t = threads.get(i);
            t.start();
        }

        //join
        for (int i=0; i< THREAD_COUNT;i++) {
            Thread t = threads.get(i);
            t.join();
        }

        assertEquals(1, recoverer.getExecutionsCount());
    }

}
