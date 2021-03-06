/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gridgain.grid.util.offheap;

import org.apache.ignite.*;
import org.apache.ignite.lang.*;
import org.gridgain.grid.util.lang.*;
import org.gridgain.grid.util.offheap.unsafe.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.testframework.junits.common.*;
import org.jdk8.backport.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Tests off-heap map.
 */
public abstract class GridOffHeapMapAbstractSelfTest extends GridCommonAbstractTest {
    /** Random. */
    private static final Random RAND = new Random();

    /** Unsafe map. */
    private GridOffHeapMap<String> map;

    /** */
    protected float load = 0.75f;

    /** */
    protected int initCap = 100;

    /** */
    protected int concurrency = 16;

    /** */
    protected short lruStripes = 16;

    /** */
    protected GridOffHeapEvictListener evictLsnr;

    /** */
    protected long mem = 20 * 1024 * 1024;

    /** */
    protected int loadCnt = 100000;

    /**
     *
     */
    protected GridOffHeapMapAbstractSelfTest() {
        super(false);
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        //resetLog4j(Level.INFO, true, false, "");
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        if (map != null)
            map.destruct();
    }

    /**
     * @return New map.
     */
    protected abstract <K> GridOffHeapMap<K> newMap();

    /**
     *
     * @return New Object.
     */
    private String string() {
        String key = "";

        for (int i = 0; i < 3; i++)
            key += RAND.nextLong();

        return key;
    }

    /**
     * @param len Length of byte array.
     * @return Random byte array.
     */
    private byte[] bytes(int len) {
        byte[] b = new byte[len];

        RAND.nextBytes(b);

        return b;
    }

    /**
     * @param key Key.
     * @return Hash.
     */
    private int hash(Object key) {
        return hash(key.hashCode());
    }

    /**
     * @param h Hashcode.
     * @return Hash.
     */
    private int hash(int h) {
        // Apply base step of MurmurHash; see http://code.google.com/p/smhasher/
        // Despite two multiplies, this is often faster than others
        // with comparable bit-spread properties.
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;

        return (h >>> 16) ^ h;
    }

    /**
     * @throws Exception If failed.
     */
    public void testInsert() throws Exception {
        map = newMap();

        for (int i = 0; i < 10; i++) {
            String key = string();
            String val = string();

            map.insert(hash(key), key.getBytes(), val.getBytes());

            assertTrue("Failed to insert for index: " + i, map.contains(hash(key), key.getBytes()));
            assertEquals(val,  new String(map.get(hash(key), key.getBytes())));
            assertEquals(i + 1, map.size());
        }

        assert map.size() == 10;
    }

    /**
     * @throws Exception If failed.
     */
    public void testRehash() throws Exception {
        initCap = 10;

        map = newMap();

        Map<String, String> kv = new HashMap<>(10);

        for (int i = 0; i < 10; i++)
            kv.put(string(), string());


        for (Map.Entry<String, String> e : kv.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();

            map.insert(hash(key), key.getBytes(), val.getBytes());

            assertTrue(map.contains(hash(key), key.getBytes()));
            assertEquals(val,  new String(map.get(hash(key), key.getBytes())));
        }

        for (Map.Entry<String, String> e : kv.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();

            byte[] valBytes = map.get(hash(key), key.getBytes());

            assertNotNull(valBytes);
            assertEquals(val, new String(valBytes));
        }

        assert map.size() == 10;
    }

    /**
     * @throws Exception If failed.
     */
    public void testGet() throws Exception {
        map = newMap();

        for (int i = 0; i < 10; i++) {
            String key = string();
            String val = string();

            map.insert(hash(key), key.getBytes(), val.getBytes());

            assertTrue(map.contains(hash(key), key.getBytes()));
            assertEquals(val,  new String(map.get(hash(key), key.getBytes())));
            assertEquals(i + 1, map.size());
        }

        assert map.size() == 10;
    }

    /**
     * @throws Exception If failed.
     */
    public void testPut1() throws Exception {
        map = newMap();

        for (int i = 0; i < 10; i++) {
            String key = string();
            String val = string();

            assertTrue(map.put(hash(key), key.getBytes(), val.getBytes()));

            assertTrue(map.contains(hash(key), key.getBytes()));
            assertEquals(val,  new String(map.get(hash(key), key.getBytes())));
            assertEquals(i + 1, map.size());
        }

        assertEquals(10, map.size());
    }

    /**
     * @throws Exception If failed.
     */
    public void testPut2() throws Exception {
        map = newMap();

        for (int i = 0; i < 10; i++) {
            String key = string();

            String val1 = string();
            String val2 = string();

            assertTrue(map.put(hash(key), key.getBytes(), val1.getBytes()));

            assertTrue(map.contains(hash(key), key.getBytes()));
            assertEquals(val1,  new String(map.get(hash(key), key.getBytes())));
            assertEquals(i + 1, map.size());

            assertFalse(map.put(hash(key), key.getBytes(), val2.getBytes()));

            assertTrue(map.contains(hash(key), key.getBytes()));
            assertEquals(val2,  new String(map.get(hash(key), key.getBytes())));
            assertEquals(i + 1, map.size());
        }

        assertEquals(10, map.size());
    }

    /**
     * @throws Exception If failed.
     */
    public void testRemove() throws Exception {
        map = newMap();

        for (int i = 0; i < 10; i++) {
            String key = string();
            String val = string();

            assertTrue(map.put(hash(key), key.getBytes(), val.getBytes()));

            assertTrue(map.contains(hash(key), key.getBytes()));
            assertNotNull(map.get(hash(key), key.getBytes()));
            assertEquals(new String(map.get(hash(key), key.getBytes())), val);
            assertEquals(1, map.size());

            byte[] val2 = map.remove(hash(key), key.getBytes());

            assertNotNull(val2);
            assertEquals(val, new String(val2));
            assertFalse(map.contains(hash(key), key.getBytes()));
            assertNull(map.get(hash(key), key.getBytes()));
            assertEquals(0, map.size());
        }

        assertEquals(0, map.size());
    }

    /**
     * @throws Exception If failed.
     */
    public void testRemovex() throws Exception {
        map = newMap();

        for (int i = 0; i < 10; i++) {
            String key = string();
            String val = string();

            assertTrue(map.put(hash(key), key.getBytes(), val.getBytes()));

            assertTrue(map.contains(hash(key), key.getBytes()));
            assertNotNull(map.get(hash(key), key.getBytes()));
            assertEquals(new String(map.get(hash(key), key.getBytes())), val);
            assertEquals(1, map.size());

            boolean rmvd = map.removex(hash(key), key.getBytes());

            assertTrue(rmvd);
            assertFalse(map.contains(hash(key), key.getBytes()));
            assertNull(map.get(hash(key), key.getBytes()));
            assertEquals(0, map.size());
        }

        assertEquals(0, map.size());
    }

    /**
     * @throws Exception If failed.
     */
    public void testIterator() throws Exception {
        initCap = 10;

        map = newMap();

        final AtomicInteger rehashes = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();

        map.eventListener(new GridOffHeapEventListener() {
            @Override public void onEvent(GridOffHeapEvent evt) {
                switch (evt) {
                    case REHASH:
                        rehashes.incrementAndGet();
                        break;
                    case RELEASE:
                        releases.incrementAndGet();
                        break;

                    default: // No-op.
                }
            }
        });

        int max = 1024;

        Map<String, String> m = new HashMap<>(max);

        for (int i = 0; i < max; i++) {
            String key = string();
            String val = string();

            // info("Storing [i=" + i + ", key=" + key + ", val=" + val + ']');

            assertTrue(map.put(hash(key), key.getBytes(), val.getBytes()));

            assertTrue(map.contains(hash(key), key.getBytes()));
            assertNotNull(map.get(hash(key), key.getBytes()));
            assertEquals(new String(map.get(hash(key), key.getBytes())), val);

            m.put(key, val);

            int cnt = 0;

            try (GridCloseableIterator<IgniteBiTuple<byte[], byte[]>> it = map.iterator()) {
                while (it.hasNext()) {
                    IgniteBiTuple<byte[], byte[]> t = it.next();

                    String k = new String(t.get1());
                    String v = new String(t.get2());

                    // info("Entry [k=" + k + ", v=" + v + ']');

                    assertEquals(m.get(k), v);

                    cnt++;
                }
            }

            assertEquals(map.size(), cnt);
        }

        assertEquals(max, map.size());

        info("Stats [size=" + map.size() + ", rehashes=" + rehashes + ", releases=" + releases + ']');

        assertTrue(rehashes.get() > 0);
        assertEquals(rehashes.get(), releases.get());
    }

    /**
     * @throws Exception If failed.
     */
    public void testIteratorMultithreaded() throws Exception {
        initCap = 10;

        map = newMap();

        final AtomicInteger rehashes = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();

        map.eventListener(new GridOffHeapEventListener() {
            @Override public void onEvent(GridOffHeapEvent evt) {
                switch (evt) {
                    case REHASH:
                        rehashes.incrementAndGet();
                        break;
                    case RELEASE:
                        releases.incrementAndGet();
                        break;

                    default: // No-op.
                }
            }
        });

        final int max = 1024;

        int threads = 5;

        final Map<String, String> m = new ConcurrentHashMap8<>(max);

        multithreaded(new Callable() {
            @Override public Object call() throws Exception {
                for (int i = 0; i < max; i++) {
                    String key = string();
                    String val = string();

                    // info("Storing [i=" + i + ", key=" + key + ", val=" + val + ']');

                    m.put(key, val);

                    assertTrue(map.put(hash(key), key.getBytes(), val.getBytes()));

                    assertTrue(map.contains(hash(key), key.getBytes()));
                    assertNotNull(map.get(hash(key), key.getBytes()));
                    assertEquals(new String(map.get(hash(key), key.getBytes())), val);

                    try (GridCloseableIterator<IgniteBiTuple<byte[], byte[]>> it = map.iterator()) {
                        while (it.hasNext()) {
                            IgniteBiTuple<byte[], byte[]> t = it.next();

                            String k = new String(t.get1());
                            String v = new String(t.get2());

                            // info("Entry [k=" + k + ", v=" + v + ']');

                            assertEquals(m.get(k), v);
                        }
                    }
                }

                return null;
            }
        }, threads);

        assertEquals(max * threads, map.size());

        info("Stats [size=" + map.size() + ", rehashes=" + rehashes + ", releases=" + releases + ']');

        assertTrue(rehashes.get() > 0);
        assertEquals(rehashes.get(), releases.get());
    }

    /**
     *
     */
    public void testInsertLoad() {
        map = newMap();

        Map<String, String> m = new HashMap<>();

        for (int i = 0; i < loadCnt; i++)
            m.put(string(),  string());

        int cnt = 0;

        for (Map.Entry<String, String> e : m.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();

            try {
                map.insert(hash(key), key.getBytes(), val.getBytes());
            }
            catch (GridOffHeapOutOfMemoryException ex) {
                error("Map put failed for count: " + cnt, ex);

                throw ex;
            }

            assertTrue(map.contains(hash(key), key.getBytes()));
            assertNotNull(map.get(hash(key), key.getBytes()));
            assertEquals(new String(map.get(hash(key), key.getBytes())), val);
            assertEquals(++cnt, map.size());
        }
    }


    /**
     *
     */
    public void testPutLoad() {
        map = newMap();

        Map<String, String> m = new HashMap<>();

        for (int i = 0; i < loadCnt; i++)
            m.put(string(),  string());

        int cnt = 0;

        for (Map.Entry<String, String> e : m.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();

            try {
                assertTrue(map.put(hash(key), key.getBytes(), val.getBytes()));
                assertTrue(map.contains(hash(key), key.getBytes()));
                assertFalse(map.put(hash(key), key.getBytes(), val.getBytes()));
            }
            catch (GridOffHeapOutOfMemoryException ex) {
                error("Map put failed for count: " + cnt, ex);

                throw ex;
            }

            assertTrue(map.contains(hash(key), key.getBytes()));
            assertNotNull(map.get(hash(key), key.getBytes()));
            assertEquals(new String(map.get(hash(key), key.getBytes())), val);
            assertEquals(++cnt, map.size());
        }
    }

    /**
     *
     */
    public void testLru1() {
        lruStripes = 1;
        mem = 10;

        final AtomicInteger evictCnt = new AtomicInteger();

        evictLsnr = new GridOffHeapEvictListener() {
            @Override public void onEvict(int part, int hash, byte[] keyBytes, byte[] valBytes) {
                String key = new String(keyBytes);

                info("Evicted key: " + key);

                evictCnt.incrementAndGet();
            }
        };

        map = newMap();

        for (int i = 0; i < 10; i++) {
            String key = string();

            byte[] keyBytes = key.getBytes();
            byte[] valBytes = bytes(100);

            map.insert(hash(key), keyBytes, valBytes);

            info("Evicted: " + evictCnt);

            assertEquals(1, evictCnt.get());
            assertEquals(0, map.size());

            assertTrue(evictCnt.compareAndSet(1, 0));
        }
    }

    /**
     *
     */
    public void testLru2() {
        mem = 1000 + 64 * 16; // Add segment size.

        lruStripes = 6;
        concurrency = 8;

        final AtomicInteger evictCnt = new AtomicInteger();

        evictLsnr = new GridOffHeapEvictListener() {
            @Override public void onEvict(int part, int hash, byte[] k, byte[] v) {
                evictCnt.incrementAndGet();
            }
        };

        map = newMap();

        for (int i = 0; i < 10000; i++) {
            String key = string();

            byte[] keyBytes = key.getBytes();
            byte[] valBytes = bytes(100);

            map.insert(hash(key), keyBytes, valBytes);
        }

        assertTrue(evictCnt.get() > 10);
        assertTrue("Invalid map free size [size=" + map.freeSize() + ", evictCnt=" + evictCnt + ']',
            map.freeSize() >= 0);
    }

    /**
     * @throws Exception If failed.
     */
    public void testLruMultithreaded() throws Exception {
        mem = 1000 + 64 * 16; // Add segment size.

        lruStripes = 3;
        concurrency = 8;

        final AtomicInteger evictCnt = new AtomicInteger();

        final AtomicInteger cnt = new AtomicInteger();

        evictLsnr = new GridOffHeapEvictListener() {
            @Override public void onEvict(int part, int hash, byte[] k, byte[] v) {
                evictCnt.incrementAndGet();
            }
        };

        map = newMap();

        multithreaded(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < 10000; i++) {
                    String key = string();

                    byte[] keyBytes = key.getBytes();
                    byte[] valBytes = bytes(100);

                    assert !map.contains(hash(key), keyBytes);

                    map.insert(hash(key), keyBytes, valBytes);

                    int n;

                    if ((n = cnt.incrementAndGet()) % 10000 == 0)
                        info("Inserted entries: " + n);
                }
            }
        }, 10);

        info("Map stats [evicted=" + evictCnt + ", size=" + map.size() + ", allocated=" + map.allocatedSize() +
            ", freeSize=" + map.freeSize() + ']');

        assertTrue(map.freeSize() >= 0);
    }

    /**
     * @throws Exception If failed.
     */
    public void testIteratorAfterRehash() throws Exception {
        mem = 0;
        initCap = 10;
        concurrency = 2;

        map = newMap();

        final CountDownLatch startLatch = new CountDownLatch(1);

        final AtomicBoolean run = new AtomicBoolean(true);

        IgniteFuture<?> itFut = multithreadedAsync(new Runnable() {
            @Override public void run() {
                try {
                    startLatch.await();

                    while (run.get()) {
                        GridCloseableIterator<IgniteBiTuple<byte[], byte[]>> it = map.iterator();

                        while (it.hasNext())
                            it.next();

                        it.close();
                    }
                }
                catch (IgniteCheckedException e) {
                    e.printStackTrace();
                }
                catch (InterruptedException e) {
                    e.printStackTrace();

                    Thread.currentThread().interrupt();
                }
            }
        }, 1);

        IgniteFuture<?> putFut = multithreadedAsync(new Runnable() {
            @Override public void run() {
                try {
                    startLatch.await();

                    Random rnd = new Random();

                    for (int size = 50; size < 5000; size++) {
                        int valSize = rnd.nextInt(50) + 1;

                        for (int i = 0; i < size; i++)
                            map.put(i, U.intToBytes(i), new byte[valSize]);
                    }
                }
                catch (InterruptedException e) {
                    e.printStackTrace();

                    Thread.currentThread().interrupt();
                }
            }
        }, 1);

        startLatch.countDown();

        putFut.get();

        run.set(false);

        itFut.get();
    }

    /**
     * @throws Exception If failed.
     */
    public void testMultithreadedOps() throws Exception {
        mem = 1512; // Small enough for evictions.

        lruStripes = 3;
        concurrency = 8;
        initCap = 256;

        map = newMap();

        long zeroAllocated = map.allocatedSize();

        X.println("Empty map offheap size: " + zeroAllocated);

        final AtomicBoolean stop = new AtomicBoolean();

        final byte[][] keys = new byte[127][16];

        Random rnd = new Random();

        for (int i = 0; i < keys.length; i++) {
            rnd.nextBytes(keys[i]);

            keys[i][0] = (byte)i; // hash
        }

        IgniteFuture<?> fut = multithreadedAsync(new Callable<Void>() {
            @Override
            public Void call() throws IgniteCheckedException {
                Random rnd = new Random();

                while (!stop.get()) {
                    byte[] key = keys[rnd.nextInt(keys.length)];

                    int hash = key[0];

                    byte[] val = new byte[1 + rnd.nextInt(11)];
                    rnd.nextBytes(val);

                    switch (rnd.nextInt(5)) {
                        case 0:
                            map.put(hash, key, val);

                            break;
                        case 1:
                            map.remove(hash, key);

                            break;
                        case 2:
                            map.contains(hash, key);

                            break;
                        case 3:
                            map.get(hash, key);

                            break;
                        case 4:
//                            map.insert(hash, key, val);
//                            break;
                        case 5:
                            GridCloseableIterator<IgniteBiTuple<byte[], byte[]>> iter = map.iterator();

                            while (iter.hasNext())
                                assertNotNull(iter.next());

                            iter.close();

                            break;
                    }
                }

                return null;
            }
        }, 49);

        Thread.sleep(60000);

        stop.set(true);

        fut.get();

        for (byte[] key : keys)
            map.remove(key[0], key);

        assertEquals(0, map.size());
        assertEquals(0, ((GridUnsafeMap)map).lruSize());

        assertEquals(zeroAllocated, map.allocatedSize());
    }
}
