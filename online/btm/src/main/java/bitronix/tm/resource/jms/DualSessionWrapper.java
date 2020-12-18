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
package bitronix.tm.resource.jms;

import bitronix.tm.BitronixTransaction;
import bitronix.tm.internal.BitronixRollbackSystemException;
import bitronix.tm.internal.BitronixSystemException;
import bitronix.tm.resource.common.AbstractXAResourceHolder;
import bitronix.tm.resource.common.ResourceBean;
import bitronix.tm.resource.common.StateChangeListener;
import bitronix.tm.resource.common.TransactionContextHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.BytesMessage;
import javax.jms.Destination;
import javax.jms.IllegalStateException;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.QueueBrowser;
import javax.jms.Session;
import javax.jms.StreamMessage;
import javax.jms.TemporaryQueue;
import javax.jms.TemporaryTopic;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.jms.TopicSubscriber;
import javax.jms.TransactionInProgressException;
import javax.jms.TransactionRolledBackException;
import javax.jms.XASession;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.xa.XAResource;
import java.io.Serializable;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * JMS Session wrapper that will send calls to either a XASession or to a non-XA Session depending on the calling
 * context.
 *
 * @author Ludovic Orban
 */
public class DualSessionWrapper extends AbstractXAResourceHolder<DualSessionWrapper> implements Session, StateChangeListener<DualSessionWrapper> {

    private final static Logger log = LoggerFactory.getLogger(DualSessionWrapper.class);

    private final JmsPooledConnection pooledConnection;
    private final boolean transacted;
    private final int acknowledgeMode;

    private XASession xaSession;
    private Session session;
    private XAResource xaResource;
    private MessageListener listener;

    //TODO: shouldn't producers/consumers/subscribers be separated between XA and non-XA session ?
    private final Map<MessageProducerConsumerKey, MessageProducer> messageProducers = new HashMap<MessageProducerConsumerKey, MessageProducer>();
    private final Map<MessageProducerConsumerKey, MessageConsumer> messageConsumers = new HashMap<MessageProducerConsumerKey, MessageConsumer>();
    private final Map<MessageProducerConsumerKey, TopicSubscriberWrapper> topicSubscribers = new HashMap<MessageProducerConsumerKey, TopicSubscriberWrapper>();

    public DualSessionWrapper(JmsPooledConnection pooledConnection, boolean transacted, int acknowledgeMode) {
        this.pooledConnection = pooledConnection;
        this.transacted = transacted;
        this.acknowledgeMode = acknowledgeMode;

        if (log.isDebugEnabled()) { log.debug("getting session handle from " + pooledConnection); }
        setState(State.ACCESSIBLE);
        addStateChangeEventListener(this);
    }

    public PoolingConnectionFactory getPoolingConnectionFactory() {
        return pooledConnection.getPoolingConnectionFactory();
    }

    public Session getSession() throws JMSException {
        return getSession(false);
    }

    public Session getSession(boolean forceXa) throws JMSException {
        if (getState() == State.CLOSED)
            throw new IllegalStateException("session handle is closed");

        if (forceXa) {
            if (log.isDebugEnabled()) { log.debug("choosing XA session (forced)"); }
            return createXASession();
        }
        else {
            BitronixTransaction currentTransaction = TransactionContextHelper.currentTransaction();
            if (currentTransaction != null) {
                if (log.isDebugEnabled()) { log.debug("choosing XA session"); }
                return createXASession();
            }
            if (log.isDebugEnabled()) { log.debug("choosing non-XA session"); }
            return createNonXASession();
        }
    }

    private Session createNonXASession() throws JMSException {
        // non-XA
        if (session == null) {
            session = pooledConnection.getXAConnection().createSession(transacted, acknowledgeMode);
            if (listener != null) {
                session.setMessageListener(listener);
                if (log.isDebugEnabled()) { log.debug("get non-XA session registered message listener: " + listener); }
            }
        }
        return session;
    }

    private Session createXASession() throws JMSException {
        // XA
        if (xaSession == null) {
            xaSession = pooledConnection.getXAConnection().createXASession();
            if (listener != null) {
                xaSession.setMessageListener(listener);
                if (log.isDebugEnabled()) { log.debug("get XA session registered message listener: " + listener); }
            }
            xaResource = xaSession.getXAResource();
        }
        return xaSession.getSession();
    }

    @Override
    public String toString() {
        return "a DualSessionWrapper in state " + getState() + " of " + pooledConnection;
    }


    /* wrapped Session methods that have special XA semantics */

    @Override
    public void close() throws JMSException {
        if (getState() != State.ACCESSIBLE) {
            if (log.isDebugEnabled()) { log.debug("not closing already closed " + this); }
            return;
        }

        if (log.isDebugEnabled()) { log.debug("closing " + this); }

        // delisting
        try {
            TransactionContextHelper.delistFromCurrentTransaction(this);
        }
        catch (BitronixRollbackSystemException ex) {
            throw (JMSException) new TransactionRolledBackException("unilateral rollback of " + this).initCause(ex);
        }
        catch (SystemException ex) {
            throw (JMSException) new JMSException("error delisting " + this).initCause(ex);
        }
        finally {
            // requeuing
            try {
                TransactionContextHelper.requeue(this, pooledConnection.getPoolingConnectionFactory());
            }
            catch (BitronixSystemException ex) {
                // this may hide the exception thrown by delistFromCurrentTransaction() but
                // an error requeuing must absolutely be reported as an exception.
                // Too bad if this happens... See JdbcPooledConnection.release() as well.
                throw (JMSException) new JMSException("error requeuing " + this).initCause(ex);
            }
        }

    }

    @Override
    public Date getLastReleaseDate() {
        return null;
    }

    /*
     * When the session is closed (directly or deferred) the action is to change its state to IN_POOL.
     * There is no such state for JMS sessions, this just means that it has been closed -> force a
     * state switch to CLOSED then clean up.
     */
    @Override
    public void stateChanged(DualSessionWrapper source, State oldState, State newState) {
        if (newState == State.IN_POOL) {
            setState(State.CLOSED);
        }
        else if (newState == State.CLOSED) {
            if (log.isDebugEnabled()) { log.debug("session state changing to CLOSED, cleaning it up: " + this); }

            if (xaSession != null) {
                try {
                    xaSession.close();
                } catch (JMSException ex) {
                    log.error("error closing XA session", ex);
                }
                xaSession = null;
                xaResource = null;
            }

            if (session != null) {
                try {
                    session.close();
                } catch (JMSException ex) {
                    log.error("error closing session", ex);
                }
                session = null;
            }

            Iterator<Entry<MessageProducerConsumerKey, MessageProducer>> it = messageProducers.entrySet().iterator();
            while (it.hasNext()) {
                Entry<MessageProducerConsumerKey, MessageProducer> entry = it.next();
                MessageProducerWrapper messageProducerWrapper = (MessageProducerWrapper) entry.getValue();
                try {
                    messageProducerWrapper.close();
                } catch (JMSException ex) {
                    log.error("error closing message producer", ex);
                }
            }
            messageProducers.clear();

            Iterator<Entry<MessageProducerConsumerKey, MessageConsumer>> it2 = messageConsumers.entrySet().iterator();
            while (it2.hasNext()) {
                Entry<MessageProducerConsumerKey, MessageConsumer> entry = it2.next();
                MessageConsumerWrapper messageConsumerWrapper = (MessageConsumerWrapper) entry.getValue();
                try {
                    messageConsumerWrapper.close();
                } catch (JMSException ex) {
                    log.error("error closing message consumer", ex);
                }
            }
            messageConsumers.clear();

        } // if newState == State.CLOSED
    }

    @Override
    public void stateChanging(DualSessionWrapper source, State currentState, State futureState) {
    }

    @Override
    public MessageProducer createProducer(Destination destination) throws JMSException {
        MessageProducerConsumerKey key = new MessageProducerConsumerKey(destination);
        if (log.isDebugEnabled()) { log.debug("looking for producer based on " + key); }
        MessageProducerWrapper messageProducer = (MessageProducerWrapper) messageProducers.get(key);
        if (messageProducer == null) {
            if (log.isDebugEnabled()) { log.debug("found no producer based on " + key + ", creating it"); }
            messageProducer = new MessageProducerWrapper(getSession().createProducer(destination), this, pooledConnection.getPoolingConnectionFactory());

            if (pooledConnection.getPoolingConnectionFactory().getCacheProducersConsumers()) {
                if (log.isDebugEnabled()) { log.debug("caching producer via key " + key); }
                messageProducers.put(key, messageProducer);
            }
        }
        else if (log.isDebugEnabled()) { log.debug("found producer based on " + key + ", recycling it: " + messageProducer); }
        return messageProducer;
    }

    @Override
    public MessageConsumer createConsumer(Destination destination) throws JMSException {
        MessageProducerConsumerKey key = new MessageProducerConsumerKey(destination);
        if (log.isDebugEnabled()) { log.debug("looking for consumer based on " + key); }
        MessageConsumerWrapper messageConsumer = (MessageConsumerWrapper) messageConsumers.get(key);
        if (messageConsumer == null) {
            if (log.isDebugEnabled()) { log.debug("found no consumer based on " + key + ", creating it"); }
            messageConsumer = new MessageConsumerWrapper(getSession().createConsumer(destination), this, pooledConnection.getPoolingConnectionFactory());

            if (pooledConnection.getPoolingConnectionFactory().getCacheProducersConsumers()) {
                if (log.isDebugEnabled()) { log.debug("caching consumer via key " + key); }
                messageConsumers.put(key, messageConsumer);
            }
        }
        else if (log.isDebugEnabled()) { log.debug("found consumer based on " + key + ", recycling it: " + messageConsumer); }
        return messageConsumer;
    }

    @Override
    public MessageConsumer createConsumer(Destination destination, String messageSelector) throws JMSException {
        MessageProducerConsumerKey key = new MessageProducerConsumerKey(destination, messageSelector);
        if (log.isDebugEnabled()) { log.debug("looking for consumer based on " + key); }
        MessageConsumerWrapper messageConsumer = (MessageConsumerWrapper) messageConsumers.get(key);
        if (messageConsumer == null) {
            if (log.isDebugEnabled()) { log.debug("found no consumer based on " + key + ", creating it"); }
            messageConsumer = new MessageConsumerWrapper(getSession().createConsumer(destination, messageSelector), this, pooledConnection.getPoolingConnectionFactory());

            if (pooledConnection.getPoolingConnectionFactory().getCacheProducersConsumers()) {
                if (log.isDebugEnabled()) { log.debug("caching consumer via key " + key); }
                messageConsumers.put(key, messageConsumer);
            }
        }
        else if (log.isDebugEnabled()) { log.debug("found consumer based on " + key + ", recycling it: " + messageConsumer); }
        return messageConsumer;
    }

    @Override
    public MessageConsumer createConsumer(Destination destination, String messageSelector, boolean noLocal) throws JMSException {
        MessageProducerConsumerKey key = new MessageProducerConsumerKey(destination, messageSelector, noLocal);
        if (log.isDebugEnabled()) { log.debug("looking for consumer based on " + key); }
        MessageConsumerWrapper messageConsumer = (MessageConsumerWrapper) messageConsumers.get(key);
        if (messageConsumer == null) {
            if (log.isDebugEnabled()) { log.debug("found no consumer based on " + key + ", creating it"); }
            messageConsumer = new MessageConsumerWrapper(getSession().createConsumer(destination, messageSelector, noLocal), this, pooledConnection.getPoolingConnectionFactory());

            if (pooledConnection.getPoolingConnectionFactory().getCacheProducersConsumers()) {
                if (log.isDebugEnabled()) { log.debug("caching consumer via key " + key); }
                messageConsumers.put(key, messageConsumer);
            }
        }
        else if (log.isDebugEnabled()) { log.debug("found consumer based on " + key + ", recycling it: " + messageConsumer); }
        return messageConsumer;
    }

    @Override
    public TopicSubscriber createDurableSubscriber(Topic topic, String name) throws JMSException {
        MessageProducerConsumerKey key = new MessageProducerConsumerKey(topic);
        if (log.isDebugEnabled()) { log.debug("looking for durable subscriber based on " + key); }
        TopicSubscriberWrapper topicSubscriber = topicSubscribers.get(key);
        if (topicSubscriber == null) {
            if (log.isDebugEnabled()) { log.debug("found no durable subscriber based on " + key + ", creating it"); }
            topicSubscriber = new TopicSubscriberWrapper(getSession().createDurableSubscriber(topic, name), this, pooledConnection.getPoolingConnectionFactory());

            if (pooledConnection.getPoolingConnectionFactory().getCacheProducersConsumers()) {
                if (log.isDebugEnabled()) { log.debug("caching durable subscriber via key " + key); }
                topicSubscribers.put(key, topicSubscriber);
            }
        }
        else if (log.isDebugEnabled()) { log.debug("found durable subscriber based on " + key + ", recycling it: " + topicSubscriber); }
        return topicSubscriber;
    }

    @Override
    public TopicSubscriber createDurableSubscriber(Topic topic, String name, String messageSelector, boolean noLocal) throws JMSException {
        MessageProducerConsumerKey key = new MessageProducerConsumerKey(topic, messageSelector, noLocal);
        if (log.isDebugEnabled()) { log.debug("looking for durable subscriber based on " + key); }
        TopicSubscriberWrapper topicSubscriber = topicSubscribers.get(key);
        if (topicSubscriber == null) {
            if (log.isDebugEnabled()) { log.debug("found no durable subscriber based on " + key + ", creating it"); }
            topicSubscriber = new TopicSubscriberWrapper(getSession().createDurableSubscriber(topic, name, messageSelector, noLocal), this, pooledConnection.getPoolingConnectionFactory());

            if (pooledConnection.getPoolingConnectionFactory().getCacheProducersConsumers()) {
                if (log.isDebugEnabled()) { log.debug("caching durable subscriber via key " + key); }
                topicSubscribers.put(key, topicSubscriber);
            }
        }
        else if (log.isDebugEnabled()) { log.debug("found durable subscriber based on " + key + ", recycling it: " + topicSubscriber); }
        return topicSubscriber;
    }

    @Override
    public MessageListener getMessageListener() throws JMSException {
        return listener;
    }

    @Override
    public void setMessageListener(MessageListener listener) throws JMSException {
        if (getState() == State.CLOSED)
            throw new IllegalStateException("session handle is closed");

        if (session != null)
            session.setMessageListener(listener);
        if (xaSession != null)
            xaSession.setMessageListener(listener);

        this.listener = listener;
    }

    @Override
    public void run() {
        try {
            Session session = getSession(true);
            if (log.isDebugEnabled()) { log.debug("running XA session " + session); }
            session.run();
        } catch (JMSException ex) {
            log.error("error getting session", ex);
        }
    }

    /* XAResourceHolder implementation */

    @Override
    public XAResource getXAResource() {
        return xaResource;
    }

    @Override
    public ResourceBean getResourceBean() {
        return getPoolingConnectionFactory();
    }

    /* XAStatefulHolder implementation */

    @Override
    public List<DualSessionWrapper> getXAResourceHolders() {
        return Collections.singletonList(this);
    }

    public DualSessionWrapper getXAResourceHolderForXaResource(XAResource xaResource) {
        if (xaResource == this.xaResource) {
            return this;
        }
        return null;
    }

    @Override
    public Object getConnectionHandle() throws Exception {
        return null;
    }

    /* XA-enhanced methods */

    @Override
    public boolean getTransacted() throws JMSException {
        if (isParticipatingInActiveGlobalTransaction())
            return true; // for consistency with EJB 2.1 spec (17.3.5)

        return getSession().getTransacted();
    }

    @Override
    public int getAcknowledgeMode() throws JMSException {
        if (isParticipatingInActiveGlobalTransaction())
            return 0; // for consistency with EJB 2.1 spec (17.3.5)

        return getSession().getAcknowledgeMode();
    }

    @Override
    public void commit() throws JMSException {
        if (isParticipatingInActiveGlobalTransaction())
            throw new TransactionInProgressException("cannot commit a resource enlisted in a global transaction");

        getSession().commit();
    }

    @Override
    public void rollback() throws JMSException {
        if (isParticipatingInActiveGlobalTransaction())
            throw new TransactionInProgressException("cannot rollback a resource enlisted in a global transaction");

        getSession().rollback();
    }

    @Override
    public void recover() throws JMSException {
        if (isParticipatingInActiveGlobalTransaction())
            throw new TransactionInProgressException("cannot recover a resource enlisted in a global transaction");

        getSession().recover();
    }

    @Override
    public QueueBrowser createBrowser(javax.jms.Queue queue) throws JMSException {
        enlistResource();
        return getSession().createBrowser(queue);
    }

    @Override
    public QueueBrowser createBrowser(javax.jms.Queue queue, String messageSelector) throws JMSException {
        enlistResource();
        return getSession().createBrowser(queue, messageSelector);
    }

    /* dumb wrapping of Session methods */

    @Override
    public BytesMessage createBytesMessage() throws JMSException {
        return getSession().createBytesMessage();
    }

    @Override
    public MapMessage createMapMessage() throws JMSException {
        return getSession().createMapMessage();
    }

    @Override
    public Message createMessage() throws JMSException {
        return getSession().createMessage();
    }

    @Override
    public ObjectMessage createObjectMessage() throws JMSException {
        return getSession().createObjectMessage();
    }

    @Override
    public ObjectMessage createObjectMessage(Serializable serializable) throws JMSException {
        return getSession().createObjectMessage(serializable);
    }

    @Override
    public StreamMessage createStreamMessage() throws JMSException {
        return getSession().createStreamMessage();
    }

    @Override
    public TextMessage createTextMessage() throws JMSException {
        return getSession().createTextMessage();
    }

    @Override
    public TextMessage createTextMessage(String text) throws JMSException {
        return getSession().createTextMessage(text);
    }

    @Override
    public javax.jms.Queue createQueue(String queueName) throws JMSException {
        return getSession().createQueue(queueName);
    }

    @Override
    public Topic createTopic(String topicName) throws JMSException {
        return getSession().createTopic(topicName);
    }

    @Override
    public TemporaryQueue createTemporaryQueue() throws JMSException {
        return getSession().createTemporaryQueue();
    }

    @Override
    public TemporaryTopic createTemporaryTopic() throws JMSException {
        return getSession().createTemporaryTopic();
    }

    @Override
    public void unsubscribe(String name) throws JMSException {
        getSession().unsubscribe(name);
    }


    /**
     * Enlist this session into the current transaction if automaticEnlistingEnabled = true for this resource.
     * If no transaction is running then this method does nothing.
     * @throws JMSException if an exception occurs
     */
    protected void enlistResource() throws JMSException {
        PoolingConnectionFactory poolingConnectionFactory = pooledConnection.getPoolingConnectionFactory();
        if (poolingConnectionFactory.getAutomaticEnlistingEnabled()) {
            getSession(); // make sure the session is created before enlisting it
            try {
                TransactionContextHelper.enlistInCurrentTransaction(this);
            } catch (SystemException ex) {
                throw (JMSException) new JMSException("error enlisting " + this).initCause(ex);
            } catch (RollbackException ex) {
                throw (JMSException) new JMSException("error enlisting " + this).initCause(ex);
            }
        } // if getAutomaticEnlistingEnabled
    }
}
