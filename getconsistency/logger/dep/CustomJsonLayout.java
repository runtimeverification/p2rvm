package mop;

import java.nio.charset.Charset;
import java.lang.reflect.*;

import mop.PEvent;
import mop.PEventMessage;

import com.owlike.genson.Genson;
import com.owlike.genson.GensonBuilder;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.*;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.layout.AbstractLayout;
import org.apache.logging.log4j.message.Message;

@Plugin(name = "CustomJsonLayout", category = Node.CATEGORY, elementType = Layout.ELEMENT_TYPE, printObject = true)
public class CustomJsonLayout extends AbstractLayout<String> {

    private Genson genson;

    protected CustomJsonLayout() {
        super(null, null, null);
        genson = new GensonBuilder().useRuntimeType(true).useClassMetadata(true).create();
    }

    protected CustomJsonLayout(final Configuration configuration, final byte[] header, final byte[] footer) {
        super(configuration, header, footer);
    }

    @PluginFactory
    public static CustomJsonLayout createLayout() {
        return new CustomJsonLayout();
    }

    @Override
    public String toSerializable(final LogEvent event) {
        Message msg = event.getMessage();
        if (msg instanceof PEventMessage) {
            PEvent pEvent = ((PEventMessage) msg).getPEvent();
            return genson.serialize(pEvent);
        } else {
            return genson.serialize(msg);
        }
    } 
 
    @Override
    public byte[] toByteArray(final LogEvent event) {
        return toSerializable(event).getBytes();
    }

    @Override
    public String getContentType() {
        return "application/json";
    }


}
