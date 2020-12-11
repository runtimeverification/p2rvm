package mop;

import java.lang.ref.*;
import org.aspectj.lang.*;

// add your own imports.
import db.*;

import p.runtime.values.*;
import mop.PEventMessage;;


aspect BaseAspect {
    pointcut notwithin() :
        !within(sun..*) &&
        !within(java..*) &&
        !within(javax..*) &&
        !within(com.sun..*) &&
        !within(org.apache.commons..*) &&
        !within(org.apache.geronimo..*) &&
        !within(net.sf.cglib..*) &&
        !within(mop..*) &&
        !within(javamoprt..*) &&
        !within(rvmonitorrt..*) &&
        !within(com.runtimeverification..*);
}

// Signatures of all the events that need dispatching.
// getConsistencyRuntimeMonitor.getConsistency_eGetReqEvent(NamedTuple)
// getConsistencyRuntimeMonitor.getConsistency_eGetRespEvent(NamedTuple)
// getConsistencyRuntimeMonitor.getConsistency_ePutReqEvent(NamedTuple)
// getConsistencyRuntimeMonitor.getConsistency_ePutRespEvent(NamedTuple)

public aspect getConsistencyMonitorAspect implements com.runtimeverification.rvmonitor.java.rt.RVMObject {
    RVMLogger logger = new RVMLogger("./getConsistency.log");

    getConsistencyMonitorAspect() { }
    
    pointcut MOP_CommonPointCut() : !within(com.runtimeverification.rvmonitor.java.rt.RVMObject+) && !adviceexecution() && BaseAspect.notwithin();
    
    // Implement your code here.
    pointcut getConsistency_getReq(String key, int rId) : (execution(* Database.getReq(String, int)) && args(key, rId)) && MOP_CommonPointCut();
    after (String key, int rId) : getConsistency_getReq(key, rId) {
        logger.log(new PEventMessage("getReq", 
                              new NamedTuple(
                                new String[]{"key", "rId"},
                                new IValue<?>[]{new StringValue(key), new IntValue(rId)}
                              )));
    }

    pointcut getConsistency_getRes(boolean res, Record r, int rId) : (execution(* Database.getRes(boolean, Record, int)) && args(res, r, rId)) && MOP_CommonPointCut();
    after (boolean res, Record r, int rId) : getConsistency_getRes(res, r, rId) {
        logger.log(new PEventMessage("getRes",
                              new NamedTuple(
                                new String[]{"res", "record", "rId"},
                                new IValue<?>[]{
                                    new BoolValue(res),
                                    new NamedTuple(
                                        new String[]{"key", "val", "sqr"},
                                        new IValue<?>[]{new StringValue(r.key), new IntValue(r.val), new IntValue(r.sqr)}
                                    ),
                                    new IntValue(rId)
                                }
                              )));
    }

    pointcut getConsistency_putReq(Record r, int rId) : (execution(* Database.putReq(Record, int)) && args(r, rId)) && MOP_CommonPointCut();
    after (Record r, int rId) : getConsistency_putReq(r, rId) {
        logger.log(new PEventMessage("putReq",
                              new NamedTuple(
                                new String[]{"record", "rId"},
                                new IValue<?>[]{
                                    new NamedTuple(
                                        new String[]{"key", "val", "sqr"},
                                        new IValue<?>[]{new StringValue(r.key), new IntValue(r.val), new IntValue(r.sqr)}
                                    ),
                                    new IntValue(rId)
                                }
                              )));
    }

    pointcut getConsistency_putRes(boolean res, Record r, int rId) : (execution(* Database.putRes(boolean, Record, int)) && args(res, r, rId)) && MOP_CommonPointCut();
    after (boolean res, Record r, int rId) : getConsistency_putRes(res, r, rId) {
        logger.log(new PEventMessage("putRes",
                              new NamedTuple(
                                new String[]{"res", "record", "rId"},
                                new IValue<?>[]{
                                    new BoolValue(res),
                                    new NamedTuple(
                                        new String[]{"key", "val", "sqr"},
                                        new IValue<?>[]{new StringValue(r.key), new IntValue(r.val), new IntValue(r.sqr)}
                                    ),
                                      new IntValue(rId)
                                }
                              )));
    }
}
