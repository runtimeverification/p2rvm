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

import bitronix.tm.internal.BitronixSystemException;
import bitronix.tm.resource.common.AbstractXAStatefulHolder;
import bitronix.tm.resource.common.RecoveryXAResourceHolder;
import bitronix.tm.resource.common.StateChangeListener;
import bitronix.tm.resource.common.TransactionContextHelper;
import bitronix.tm.resource.jms.lrc.LrcXAConnectionFactory;
import bitronix.tm.utils.ManagementRegistrar;
import bitronix.tm.utils.MonotonicClock;
import bitronix.tm.utils.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.JMSException;
import javax.jms.Session;
import javax.jms.TemporaryQueue;
import javax.jms.XAConnection;
import javax.jms.XASession;
import javax.transaction.xa.XAResource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Implementation of a JMS pooled connection wrapping vendor's {@link XAConnection} implementation.
 *
 * @author Ludovic Orban
 * TODO: how can the JMS connection be accurately tested?
 */
public class JmsPooledConnection extends AbstractXAStatefulHolder<JmsPooledConnection> implements JmsPooledConnectionMBean {

    private final static Logger log = LoggerFactory.getLogger(JmsPooledConnection.class);

    private volatile XAConnection xaConnection;
    private final PoolingConnectionFactory poolingConnectionFactory;
    private final Set<DualSessionWrapper> sessions = Collections.synchronizedSet(new HashSet<DualSessionWrapper>());

    /* management */
    private final String jmxName;
    private volatile Date acquisitionDate;
    private volatile Date lastReleaseDate;

    protected JmsPooledConnection(PoolingConnectionFactory poolingConnectionFactory, XAConnection connection) {
        this.poolingConnectionFactory = poolingConnectionFactory;
        this.xaConnection = connection;
        this.lastReleaseDate = new Date(MonotonicClock.currentTimeMillis());
        addStateChangeEventListener(new JmsPooledConnectionStateChangeListener());

        if (LrcXAConnectionFactory.class.getName().equals(poolingConnectionFactory.getClassName())) {
            if (log.isDebugEnabled()) { log.debug("emulating XA for resource " + poolingConnectionFactory.getUniqueName() + " - changing twoPcOrderingPosition to ALWAYS_LAST_POSITION"); }
            poolingConnectionFactory.setTwoPcOrderingPosition(Scheduler.ALWAYS_LAST_POSITION);
            if (log.isDebugEnabled()) { log.debug("emulating XA for resource " + poolingConnectionFactory.getUniqueName() + " - changing deferConnectionRelease to true"); }
            poolingConnectionFactory.setDeferConnectionRelease(true);
            if (log.isDebugEnabled()) { log.debug("emulating XA for resource " + poolingConnectionFactory.getUniqueName() + " - changing useTmJoin to true"); }
            poolingConnectionFactory.setUseTmJoin(true);
        }

        this.jmxName = "bitronix.tm:type=JMS,UniqueName=" + ManagementRegistrar.makeValidName(poolingConnectionFactory.getUniqueName()) + ",Id=" + poolingConnectionFactory.incCreatedResourcesCounter();
        ManagementRegistrar.register(jmxName, this);
    }

    public XAConnection getXAConnection() {
        return xaConnection;
    }

    public PoolingConnectionFactory getPoolingConnectionFactory() {
        return poolingConnectionFactory;
    }

    public synchronized RecoveryXAResourceHolder createRecoveryXAResourceHolder() throws JMSException {
        DualSessionWrapper dualSessionWrapper = new DualSessionWrapper(this, false, 0);
        dualSessionWrapper.getSession(true); // force creation of XASession to allow access to XAResource
        return new RecoveryXAResourceHolder(dualSessionWrapper);
    }

    @Override
    public synchronized void close() throws JMSException {
        if (xaConnection != null) {
            poolingConnectionFactory.unregister(this);
            setState(State.CLOSED);
            try {
                xaConnection.close();
            } finally {
                xaConnection = null;
            }
        }
    }

    @Override
    public List<DualSessionWrapper> getXAResourceHolders() {
        synchronized (sessions) {
            return new ArrayList<DualSessionWrapper>(sessions);
        }
    }

    @Override
    public Object getConnectionHandle() throws Exception {
        if (log.isDebugEnabled()) { log.debug("getting connection handle from " + this); }
        State oldState = getState();

        setState(State.ACCESSIBLE);

        if (oldState == State.IN_POOL) {
            if (log.isDebugEnabled()) { log.debug("connection " + xaConnection + " was in state IN_POOL, testing it"); }
            testXAConnection();
        }
        else {
            if (log.isDebugEnabled()) { log.debug("connection " + xaConnection + " was in state " + oldState + ", no need to test it"); }
        }

        if (log.isDebugEnabled()) { log.debug("got connection handle from " + this); }
        return new JmsConnectionHandle(this, xaConnection);
    }

    private void testXAConnection() throws JMSException {
        if (!poolingConnectionFactory.getTestConnections()) {
            if (log.isDebugEnabled()) { log.debug("not testing connection of " + this); }
            return;
        }

        if (log.isDebugEnabled()) { log.debug("testing connection of " + this); }
        XASession xaSession = xaConnection.createXASession();
        try {
            TemporaryQueue tq = xaSession.createTemporaryQueue();
            tq.delete();
        } finally {
            xaSession.close();
        }
    }

    protected void release() throws JMSException {
        if (log.isDebugEnabled()) { log.debug("releasing to pool " + this); }
        closePendingSessions();

        // requeuing
        try {
            TransactionContextHelper.requeue(this, poolingConnectionFactory);
        } catch (BitronixSystemException ex) {
            throw (JMSException) new JMSException("error requeueing " + this).initCause(ex);
        }

        if (log.isDebugEnabled()) { log.debug("released to pool " + this); }
    }

    private void closePendingSessions() {
        synchronized (sessions) {
            for (DualSessionWrapper dualSessionWrapper : sessions) {
                if (dualSessionWrapper.getState() != State.ACCESSIBLE)
                    continue;

                try {
                    if (log.isDebugEnabled()) { log.debug("trying to close pending session " + dualSessionWrapper); }
                    dualSessionWrapper.close();
                } catch (JMSException ex) {
                    log.warn("error closing pending session " + dualSessionWrapper, ex);
                }
            }
        }
    }

    protected Session createSession(boolean transacted, int acknowledgeMode) throws JMSException {
        synchronized (sessions) {
            DualSessionWrapper sessionHandle = getNotAccessibleSession();

            if (sessionHandle == null) {
                if (log.isDebugEnabled()) { log.debug("no session handle found in NOT_ACCESSIBLE state, creating new session"); }
                sessionHandle = new DualSessionWrapper(this, transacted, acknowledgeMode);
                sessionHandle.addStateChangeEventListener(new JmsConnectionHandleStateChangeListener());
                sessions.add(sessionHandle);
            }
            else {
                if (log.isDebugEnabled()) { log.debug("found session handle in NOT_ACCESSIBLE state, recycling it: " + sessionHandle); }
                sessionHandle.setState(State.ACCESSIBLE);
            }

            return sessionHandle;
        }
    }

     private DualSessionWrapper getNotAccessibleSession() {
        synchronized (sessions) {
            if (log.isDebugEnabled()) { log.debug(sessions.size() + " session(s) open from " + this); }
            for (DualSessionWrapper sessionHandle : sessions) {
                if (sessionHandle.getState() == State.NOT_ACCESSIBLE)
                    return sessionHandle;
            }
            return null;
        }
    }

    @Override
    public Date getLastReleaseDate() {
        return lastReleaseDate;
    }

    @Override
    public String toString() {
        return "a JmsPooledConnection of pool " + poolingConnectionFactory.getUniqueName() + " in state " +
                getState() + " with underlying connection " + xaConnection;
    }

    /* management */

    @Override
    public String getStateDescription() {
        return getState().toString();
    }

    @Override
    public Date getAcquisitionDate() {
        return acquisitionDate;
    }

    @Override
    public Collection<String> getTransactionGtridsCurrentlyHoldingThis() {
        synchronized (sessions) {
            Set<String> result = new HashSet<String>();
            for (DualSessionWrapper dsw : sessions) {
                result.addAll(dsw.getXAResourceHolderStateGtrids());
            }
            return result;
        }
    }

    /**
     * {@link JmsPooledConnection} {@link bitronix.tm.resource.common.StateChangeListener}.
     * When state changes to State.CLOSED, the connection is unregistered from
     * {@link bitronix.tm.utils.ManagementRegistrar}.
     */
    private final class JmsPooledConnectionStateChangeListener implements StateChangeListener<JmsPooledConnection> {
        @Override
        public void stateChanged(JmsPooledConnection source, State oldState, State newState) {
            if (newState == State.IN_POOL) {
                if (log.isDebugEnabled()) { log.debug("requeued JMS connection of " + poolingConnectionFactory); }
                lastReleaseDate = new Date(MonotonicClock.currentTimeMillis());
            }
            if (oldState == State.IN_POOL && newState == State.ACCESSIBLE) {
                acquisitionDate = new Date(MonotonicClock.currentTimeMillis());
            }
            if (newState == State.CLOSED) {
                ManagementRegistrar.unregister(jmxName);
            }
        }

        @Override
        public void stateChanging(JmsPooledConnection source, State currentState, State futureState) {
        }
    }

    /**
     * {@link JmsConnectionHandle} {@link bitronix.tm.resource.common.StateChangeListener}.
     * When state changes to State.CLOSED, the session is removed from the list of opened sessions.
     */
    private final class JmsConnectionHandleStateChangeListener implements StateChangeListener<DualSessionWrapper> {
        @Override
        public void stateChanged(DualSessionWrapper source, State oldState, State newState) {
            if (newState == State.CLOSED) {
                synchronized (sessions) {
                    sessions.remove(source);
                    if (log.isDebugEnabled()) { log.debug("DualSessionWrapper has been closed, " + sessions.size() + " session(s) left open in pooled connection"); }
                }
            }
        }

        @Override
        public void stateChanging(DualSessionWrapper source, State currentState, State futureState) {
        }
    }

    public DualSessionWrapper getXAResourceHolderForXaResource(XAResource xaResource) {
        synchronized (sessions) {
            for (DualSessionWrapper xaResourceHolder : sessions) {
                if (xaResourceHolder.getXAResource() == xaResource) {
                    return xaResourceHolder;
                }
            }
            return null;
        }
    }
}
