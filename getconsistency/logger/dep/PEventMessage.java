package mop;

import org.apache.logging.log4j.message.Message;

import p.runtime.values.*;

public class PEventMessage implements Message {

    private String eventName;
    private IValue<?> arg;

    public PEventMessage(String name, IValue<?> parg) {
        this.eventName = name;
        this.arg = parg;
    }

    public PEvent getPEvent() {
        return new PEvent(eventName, arg);
    }

    @Override
    public String getFormattedMessage() {
        return eventName + "(" + arg.toString() + ")" ;
    }

    @Override
    public String getFormat() {
        return getFormattedMessage();
    }

    @Override
    public Object[] getParameters() {
        return null;
    }

    @Override
    public Throwable getThrowable() {
        return null;
    }

}
