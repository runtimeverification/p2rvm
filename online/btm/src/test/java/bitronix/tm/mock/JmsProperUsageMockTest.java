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
package bitronix.tm.mock;

import bitronix.tm.BitronixTransactionManager;
import bitronix.tm.TransactionManagerServices;
import bitronix.tm.mock.events.EventRecorder;
import bitronix.tm.mock.events.JournalLogEvent;
import bitronix.tm.mock.events.XAResourceCommitEvent;
import bitronix.tm.mock.events.XAResourceEndEvent;
import bitronix.tm.mock.events.XAResourceStartEvent;
import bitronix.tm.resource.jms.PoolingConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.transaction.Status;
import javax.transaction.xa.XAResource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

/**
 *
 * @author Ludovic Orban
 */
public class JmsProperUsageMockTest extends AbstractMockJmsTest {

    private final static Logger log = LoggerFactory.getLogger(JmsProperUsageMockTest.class);

    public void testSimpleWorkingCase() throws Exception {
        if (log.isDebugEnabled()) { log.debug("*** getting TM"); }
        BitronixTransactionManager tm = TransactionManagerServices.getTransactionManager();
        if (log.isDebugEnabled()) { log.debug("*** before begin"); }
        tm.setTransactionTimeout(10);
        tm.begin();
        if (log.isDebugEnabled()) { log.debug("*** after begin"); }

        if (log.isDebugEnabled()) { log.debug("*** getting connection from CF1"); }
        Connection connection1 = poolingConnectionFactory1.createConnection();

        if (log.isDebugEnabled()) { log.debug("*** creating session 1 on connection 1"); }
        Session session1 = connection1.createSession(false, Session.AUTO_ACKNOWLEDGE);

        if (log.isDebugEnabled()) { log.debug("*** creating queue 1 on session 1"); }
        Queue queue1 = session1.createQueue("queue");

        if (log.isDebugEnabled()) { log.debug("*** creating producer1 on session 1"); }
        MessageProducer producer1 = session1.createProducer(queue1);

        if (log.isDebugEnabled()) { log.debug("*** sending message on producer1"); }
        producer1.send(session1.createTextMessage("testSimpleWorkingCase"));


        if (log.isDebugEnabled()) { log.debug("*** closing connection 1"); }
        connection1.close();

        if (log.isDebugEnabled()) { log.debug("*** committing"); }
        tm.commit();
        if (log.isDebugEnabled()) { log.debug("*** TX is done"); }

        // check flow
        List orderedEvents = EventRecorder.getOrderedEvents();
        log.info(EventRecorder.dumpToString());

        assertEquals(8, orderedEvents.size());
        int i=0;
        assertEquals(Status.STATUS_ACTIVE, ((JournalLogEvent) orderedEvents.get(i++)).getStatus());
        assertEquals(XAResource.TMNOFLAGS, ((XAResourceStartEvent) orderedEvents.get(i++)).getFlag());
        assertEquals(XAResource.TMSUCCESS, ((XAResourceEndEvent) orderedEvents.get(i++)).getFlag());
        assertEquals(Status.STATUS_PREPARING, ((JournalLogEvent) orderedEvents.get(i++)).getStatus());
        assertEquals(Status.STATUS_PREPARED, ((JournalLogEvent) orderedEvents.get(i++)).getStatus());
        assertEquals(Status.STATUS_COMMITTING, ((JournalLogEvent) orderedEvents.get(i++)).getStatus());
        assertEquals(true, ((XAResourceCommitEvent) orderedEvents.get(i++)).isOnePhase());
        assertEquals(Status.STATUS_COMMITTED, ((JournalLogEvent) orderedEvents.get(i++)).getStatus());
    }

    public void testSerialization() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(poolingConnectionFactory1);
        oos.close();

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(bais);
        poolingConnectionFactory1 = (PoolingConnectionFactory) ois.readObject();
        ois.close();
    }
}
