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

package org.apache.ignite.internal.processors.cache.distributed;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.store.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.resources.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.testframework.junits.common.*;
import org.jdk8.backport.*;

import javax.cache.configuration.*;
import java.util.*;

import static org.apache.ignite.cache.CacheAtomicWriteOrderMode.*;
import static org.apache.ignite.cache.CacheDistributionMode.*;
import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.*;

/**
 * Check reloadAll() on partitioned cache.
 */
public abstract class GridCachePartitionedReloadAllAbstractSelfTest extends GridCommonAbstractTest {
    /** Amount of nodes in the grid. */
    private static final int GRID_CNT = 4;

    /** Amount of backups in partitioned cache. */
    private static final int BACKUP_CNT = 1;

    /** IP finder. */
    private static TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /** Map where dummy cache store values are stored. */
    private final Map<Integer, String> map = new ConcurrentHashMap8<>();

    /** Collection of caches, one per grid node. */
    private List<GridCache<Integer, String>> caches;

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration c = super.getConfiguration(gridName);

        TcpDiscoverySpi disco = new TcpDiscoverySpi();

        disco.setIpFinder(ipFinder);

        c.setDiscoverySpi(disco);

        CacheConfiguration cc = defaultCacheConfiguration();

        cc.setDistributionMode(nearEnabled() ? NEAR_PARTITIONED : PARTITIONED_ONLY);

        cc.setCacheMode(cacheMode());

        cc.setAtomicityMode(atomicityMode());

        cc.setBackups(BACKUP_CNT);

        cc.setWriteSynchronizationMode(FULL_SYNC);

        CacheStore store = cacheStore();

        if (store != null) {
            cc.setCacheStoreFactory(new FactoryBuilder.SingletonFactory(store));
            cc.setReadThrough(true);
            cc.setWriteThrough(true);
            cc.setLoadPreviousValue(true);
        }
        else
            cc.setCacheStoreFactory(null);

        cc.setAtomicWriteOrderMode(atomicWriteOrderMode());

        c.setCacheConfiguration(cc);

        return c;
    }

    /**
     * @return Cache mode.
     */
    protected CacheMode cacheMode() {
        return PARTITIONED;
    }

    /**
     * @return Atomicity mode.
     */
    protected CacheAtomicityMode atomicityMode() {
        return CacheAtomicityMode.TRANSACTIONAL;
    }

    /**
     * @return Write order mode for atomic cache.
     */
    protected CacheAtomicWriteOrderMode atomicWriteOrderMode() {
        return CLOCK;
    }

    /**
     * @return {@code True} if near cache is enabled.
     */
    protected abstract boolean nearEnabled();

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        caches = new ArrayList<>(GRID_CNT);

        for (int i = 0; i < GRID_CNT; i++)
            caches.add(startGrid(i).<Integer, String>cache(null));

        awaitPartitionMapExchange();
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        stopAllGrids();

        map.clear();

        caches = null;
    }

    /**
     * Create new cache store.
     *
     * @return Write through storage emulator.
     */
    protected CacheStore<?, ?> cacheStore() {
        return new CacheStoreAdapter<Integer, String>() {
            @IgniteInstanceResource
            private Ignite g;

            @Override public void loadCache(IgniteBiInClosure<Integer, String> c,
                Object... args) {
                X.println("Loading all on: " + caches.indexOf(g.<Integer, String>cache(null)));

                for (Map.Entry<Integer, String> e : map.entrySet())
                    c.apply(e.getKey(), e.getValue());
            }

            @Override public String load(Integer key) {
                X.println("Loading on: " + caches.indexOf(g.<Integer, String>cache(null)) + " key=" + key);

                return map.get(key);
            }

            @Override public void write(javax.cache.Cache.Entry<? extends Integer, ? extends String> e) {
                fail("Should not be called within the test.");
            }

            @Override public void delete(Object key) {
                fail("Should not be called within the test.");
            }
        };
    }

    /**
     * Ensure that reloadAll() with disabled near cache reloads data only on a node
     * on which reloadAll() has been called.
     *
     * @throws Exception If test failed.
     */
    public void testReloadAll() throws Exception {
        // Fill caches with values.
        for (GridCache<Integer, String> cache : caches) {
            Iterable<Integer> keys = primaryKeysForCache(cache, 100);

            info("Values [cache=" + caches.indexOf(cache) + ", size=" + F.size(keys.iterator()) +  ", keys=" + keys + "]");

            for (Integer key : keys)
                map.put(key, "val" + key);
        }

        Collection<GridCache<Integer, String>> emptyCaches = new ArrayList<>(caches);

        for (GridCache<Integer, String> cache : caches) {
            info("Reloading cache: " + caches.indexOf(cache));

            // Check data is reloaded only on the nodes on which reloadAll() has been called.
            if (!nearEnabled()) {
                for (GridCache<Integer, String> eCache : emptyCaches)
                    assertEquals("Non-null values found in cache [cache=" + caches.indexOf(eCache) +
                        ", size=" + eCache.size() + ", size=" + eCache.size() +
                        ", entrySetSize=" + eCache.entrySet().size() + "]",
                        0, eCache.size());
            }

            cache.reloadAll(map.keySet());

            for (Integer key : map.keySet()) {
                if (cache.affinity().isPrimaryOrBackup(grid(caches.indexOf(cache)).localNode(), key) ||
                    nearEnabled())
                    assertEquals(map.get(key), cache.peek(key));
                else
                    assertNull(cache.peek(key));
            }

            emptyCaches.remove(cache);
        }
    }

    /**
     * Create list of keys for which the given cache is primary.
     *
     * @param cache Cache.
     * @param cnt Keys count.
     * @return Collection of keys for which given cache is primary.
     */
    private Iterable<Integer> primaryKeysForCache(GridCache<Integer,String> cache, int cnt) {
        Collection<Integer> found = new ArrayList<>(cnt);

        for (int i = 0; i < 10000; i++) {
            if (cache.affinity().isPrimary(grid(caches.indexOf(cache)).localNode(), i)) {
                found.add(i);

                if (found.size() == cnt)
                    return found;
            }
        }

        throw new IllegalStateException("Unable to find " + cnt + " keys as primary for cache.");
    }
}
