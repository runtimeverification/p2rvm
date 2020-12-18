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
package bitronix.tm.mock.resource;

import bitronix.tm.internal.BitronixXAException;
import bitronix.tm.mock.events.EventRecorder;
import bitronix.tm.mock.events.XAResourceCommitEvent;
import bitronix.tm.mock.events.XAResourceEndEvent;
import bitronix.tm.mock.events.XAResourceForgetEvent;
import bitronix.tm.mock.events.XAResourceIsSameRmEvent;
import bitronix.tm.mock.events.XAResourcePrepareEvent;
import bitronix.tm.mock.events.XAResourceRollbackEvent;
import bitronix.tm.mock.events.XAResourceStartEvent;
import bitronix.tm.mock.resource.jdbc.MockitoXADataSource;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

/**
 *
 * @author Ludovic Orban
 */
public class MockXAResource implements XAResource {

    private int prepareRc = XAResource.XA_OK;
    private int transactiontimeout;
    private MockitoXADataSource xads;

    private XAException endException;
    private XAException prepareException;
    private XAException commitException;
    private XAException rollbackException;
    private RuntimeException prepareRuntimeException;
    private XAException recoverException;
    private long recoveryDelay;

    public MockXAResource(MockitoXADataSource xads) {
        this.xads = xads;
    }

    public void setRecoveryDelay(long recoveryDelay) {
        this.recoveryDelay = recoveryDelay;
    }

    public void setPrepareRc(int prepareRc) {
        this.prepareRc = prepareRc;
    }

    public void addInDoubtXid(Xid xid) {
        xads.addInDoubtXid(xid);
    }

    private EventRecorder getEventRecorder() {
        return EventRecorder.getEventRecorder(this);
    }

    /*
    Interface implementation
    */

    public int getTransactionTimeout() throws XAException {
        return transactiontimeout;
    }

    public boolean setTransactionTimeout(int i) throws XAException {
        this.transactiontimeout = i;
        return true;
    }

    public boolean isSameRM(XAResource xaResource) throws XAException {
        boolean result = xaResource == this;
        getEventRecorder().addEvent(new XAResourceIsSameRmEvent(this, xaResource, result));
        return result;
    }

    public Xid[] recover(int flag) throws XAException {
        if (recoveryDelay > 0) {
            try {
                Thread.sleep(recoveryDelay);
            } catch (InterruptedException e) {
                // ignore
            }
        }

        if (recoverException != null)
            throw recoverException;
        if (xads == null)
            return new Xid[0];
        return xads.getInDoubtXids();
    }

    public int prepare(Xid xid) throws XAException {
        if (prepareException != null) {
            getEventRecorder().addEvent(new XAResourcePrepareEvent(this, prepareException, xid, -1));
            prepareException.fillInStackTrace();
            throw prepareException;
        }
        if (prepareRuntimeException != null) {
            prepareRuntimeException.fillInStackTrace();
            getEventRecorder().addEvent(new XAResourcePrepareEvent(this, prepareRuntimeException, xid, -1));
            throw prepareRuntimeException;
        }
        getEventRecorder().addEvent(new XAResourcePrepareEvent(this, xid, prepareRc));
        return prepareRc;
    }

    public void forget(Xid xid) throws XAException {
        getEventRecorder().addEvent(new XAResourceForgetEvent(this, xid));
        boolean found = xads.removeInDoubtXid(xid);
        if (!found)
            throw new BitronixXAException("unknown XID: " + xid, XAException.XAER_INVAL);
    }

    public void rollback(Xid xid) throws XAException {
        getEventRecorder().addEvent(new XAResourceRollbackEvent(this, rollbackException, xid));
        if (rollbackException != null)
            throw rollbackException;
        if (xads != null) xads.removeInDoubtXid(xid);
    }

    public void end(Xid xid, int flag) throws XAException {
        getEventRecorder().addEvent(new XAResourceEndEvent(this, xid, flag));
        if (endException != null)
            throw endException;
    }

    public void start(Xid xid, int flag) throws XAException {
        getEventRecorder().addEvent(new XAResourceStartEvent(this, xid, flag));
    }

    public void commit(Xid xid, boolean b) throws XAException {
        getEventRecorder().addEvent(new XAResourceCommitEvent(this, commitException, xid, b));
        if (commitException != null)
            throw commitException;
        if (xads != null) xads.removeInDoubtXid(xid);
    }

    public void setEndException(XAException endException) {
        this.endException = endException;
    }

    public void setPrepareException(XAException prepareException) {
        this.prepareException = prepareException;
    }

    public void setPrepareException(RuntimeException prepareException) {
        this.prepareRuntimeException = prepareException;
    }

    public void setCommitException(XAException commitException) {
        this.commitException = commitException;
    }

    public void setRollbackException(XAException rollbackException) {
        this.rollbackException = rollbackException;
    }

    public void setRecoverException(XAException recoverException) {
        this.recoverException = recoverException;
    }
}
