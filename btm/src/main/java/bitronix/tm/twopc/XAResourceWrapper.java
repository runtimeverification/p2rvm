package bitronix.tm.twopc;

import javax.transaction.Transaction;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

public class XAResourceWrapper {

    private final XAResource resource;

    public XAResourceWrapper(XAResource xa) {
        this.resource = xa;
    }

    public XAResource getXAResource() { return resource; }

    public int prepare(Transaction tx, Xid xid) throws XAException {
        return resource.prepare(xid);
    }

    public void rollback(Transaction tx, Xid xid) throws XAException {
        resource.rollback(xid);
    }

    public void commit(Transaction tx, Xid xid, boolean onePhase) throws XAException {
        resource.commit(xid, onePhase);
    }

}
