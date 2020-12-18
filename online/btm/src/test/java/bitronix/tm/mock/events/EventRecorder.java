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
package bitronix.tm.mock.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Ludovic Orban
 */
public class EventRecorder {

    private static final Map<Object, EventRecorder> eventRecorders = new HashMap<Object, EventRecorder>();

    public synchronized static EventRecorder getEventRecorder(Object key) {
        EventRecorder er = eventRecorders.get(key);
        if (er == null) {
            er = new EventRecorder();
            eventRecorders.put(key, er);
        }
        return er;
    }

    public static Map<Object, EventRecorder> getEventRecorders() {
        return eventRecorders;
    }

    public static Iterator<? extends Event> iterateEvents() {
        return new EventsIterator(eventRecorders);
    }

    public static List<? extends Event> getOrderedEvents() {
        Iterator<? extends Event> iterator = iterateEvents();
        List<Event> orderedEvents = new ArrayList<Event>();
        while (iterator.hasNext()) {
            Event ev = iterator.next();
            orderedEvents.add(ev);
        }
        return orderedEvents;
    }

    public static String dumpToString() {
        StringBuilder sb = new StringBuilder();

        int i = 0;
        Iterator<? extends Event> it = iterateEvents();
        while (it.hasNext()) {
            Event event = it.next();
            sb.append(i++);
            sb.append(" - ");
            sb.append(event.toString());
            sb.append("\n");
        }

        return sb.toString();
    }

    public static void clear() {
        eventRecorders.clear();
    }

    private final List<Event> events = new ArrayList<Event>();

    private EventRecorder() {
    }

    public void addEvent(Event evt) {
        events.add(evt);
    }

    public List<Event> getEvents() {
        return events;
    }

}
