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
package bitronix.tm.utils;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * @author Ludovic Orban
 */
public class UidGeneratorTest extends TestCase {


    public void testHexaStringEncoder() throws Exception {
        byte[] result = Encoder.intToBytes(0x80);
        String hexString = new Uid(result).toString();
        assertEquals("00000080", hexString);

        result = Encoder.longToBytes(0x81);
        hexString = new Uid(result).toString();
        assertEquals("0000000000000081", hexString);

        result = Encoder.shortToBytes((short) 0xff);
        hexString = new Uid(result).toString();
        assertEquals("00FF", hexString);
    }


    public void testUniqueness() throws Exception {
        final int count = 10000;
        HashSet<String> uids = new HashSet<String>(2048);

        for (int i = 0; i < count; i++) {
            Uid uid = UidGenerator.generateUid();
            assertTrue("UidGenerator generated duplicate UID at #" + i, uids.add(uid.toString()));
        }
    }

    public void testMultiThreadedUniqueness() throws Exception {
        final int concurrency = 128, callsPerThread = 1000;
        List<Future<Set<Uid>>> handles = new ArrayList<Future<Set<Uid>>>(concurrency);
        ExecutorService executorService = Executors.newFixedThreadPool(concurrency);
        try {
            for (int i = 0; i < concurrency; i++) {
                handles.add(executorService.submit(new Callable<Set<Uid>>() {
                    @Override
                    public Set<Uid> call() throws Exception {
                        Set<Uid> ids = new HashSet<Uid>(callsPerThread);
                        for (int i = 0; i < callsPerThread; i++)
                            ids.add(UidGenerator.generateUid());
                        return ids;
                    }
                }));
            }
        } finally {
            executorService.shutdown();
        }

        Set<Uid> allIds = new HashSet<Uid>(concurrency * callsPerThread);
        for (Future<Set<Uid>> handle : handles)
            allIds.addAll(handle.get());

        assertEquals(concurrency * callsPerThread, allIds.size());
    }

    public void testEquals() throws Exception {
        Uid uid1 = UidGenerator.generateUid();
        Uid uid2 = UidGenerator.generateUid();
        Uid uid3 = null;

        assertFalse(uid1.equals(uid2));
        assertFalse(uid2.equals(uid3));
        assertTrue(uid2.equals(uid2));
    }

    public void testExtracts() throws Exception {
        byte[] timestamp = Encoder.longToBytes(System.currentTimeMillis());
        byte[] sequence = Encoder.intToBytes(1);
        byte[] serverId = "my-server-id".getBytes();

        int uidLength = serverId.length + timestamp.length + sequence.length;
        byte[] uidArray = new byte[uidLength];

        System.arraycopy(serverId, 0, uidArray, 0, serverId.length);
        System.arraycopy(timestamp, 0, uidArray, serverId.length, timestamp.length);
        System.arraycopy(sequence, 0, uidArray, serverId.length + timestamp.length, sequence.length);

        Uid uid = new Uid(uidArray);

        assertTrue(Arrays.equals(serverId, uid.extractServerId()));
        assertEquals(Encoder.bytesToLong(timestamp, 0), uid.extractTimestamp());
        assertEquals(Encoder.bytesToInt(sequence, 0), uid.extractSequence());
    }

}
