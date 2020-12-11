package mop;

import java.io.*;
import java.util.*;

import com.owlike.genson.Genson;
import com.owlike.genson.GensonBuilder;
import com.owlike.genson.stream.ObjectReader;

public class LogParser {

    private String logPath;
    private FileInputStream fis;

    public LogParser(String logPath) throws IOException {
        this.logPath = logPath;
        this.fis = new FileInputStream(logPath);
    }

    public Iterator<PEvent> parseLog() {
        Genson genson = new GensonBuilder().useRuntimeType(true).useClassMetadata(true).create();
        Iterator<PEvent> iter = genson.deserializeValues(fis, PEvent.class);
        return iter;
    }
}
