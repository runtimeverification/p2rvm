package mop;

import java.util.*;

public class RVMEvent {

    private String eventName;
    private List<Object> args;

    public RVMEvent(String name, List<Object> args) {
        this.eventName = name;
        this.args = args;
    }

    public String getEventName() {
        return this.eventName;
    }

    public List<Object> getArgs() {
        return this.args;
    }
}
