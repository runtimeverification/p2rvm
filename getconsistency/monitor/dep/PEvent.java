package mop;

import p.runtime.values.*;

public class PEvent {

    private String eventName;
    private IValue<?> arg;

    public PEvent(String name, IValue<?> parg) {
        this.eventName = name;
        this.arg = parg;
    }

    public String getEventName() {
        return this.eventName;
    }

    public IValue<?> getArg() {
        return this.arg;
    }

    @Override
    public String toString() {
        return eventName + "(" + arg.toString() + ")";
    }

    public PEvent() {}

    public void setEventName(String name) {
        this.eventName = name;
    }

    public void setArg(IValue<?> arg) {
        this.arg = arg;
    }
}
