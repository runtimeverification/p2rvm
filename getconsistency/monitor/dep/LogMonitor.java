package mop;

import java.io.*;
import java.util.*;

import p.runtime.values.*;

public class LogMonitor {

    private LogParser parser;
    private String logPath;

    public LogMonitor(String path) {
        try {
            this.logPath = path;
            this.parser = new LogParser(path);
        } catch (IOException e) {
            System.out.println(e);
        }
    }

    public void monitorLog() {
        Iterator<PEvent> iter = parser.parseLog();
        while (iter.hasNext()) {
            PEvent e = iter.next();
            System.out.println(e.toString());
            String eventName = e.getEventName();
            IValue<?> arg = e.getArg();
            if (eventName.equals("getReq")) {
                getConsistencyRuntimeMonitor.getConsistency_eGetReqEvent((NamedTuple)arg);
            } else if (eventName.equals("getRes")) {
                getConsistencyRuntimeMonitor.getConsistency_eGetRespEvent((NamedTuple)arg);
	        } else if (eventName.equals("putReq")) {
                getConsistencyRuntimeMonitor.getConsistency_ePutReqEvent((NamedTuple)arg);
            } else if (eventName.equals("putRes")) {
                getConsistencyRuntimeMonitor.getConsistency_ePutRespEvent((NamedTuple)arg);
            }
        }
    }

    public static void main(String[] args) {
        String path = args[0];
        LogMonitor monitor = new LogMonitor(path);
        monitor.monitorLog();
    }

}
