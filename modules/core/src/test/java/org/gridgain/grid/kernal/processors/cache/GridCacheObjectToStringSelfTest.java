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

package org.gridgain.grid.kernal.processors.cache;

import org.apache.ignite.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.transactions.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.eviction.*;
import org.gridgain.grid.cache.eviction.fifo.*;
import org.gridgain.grid.cache.eviction.lru.*;
import org.gridgain.grid.kernal.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.gridgain.testframework.junits.common.*;

import java.util.*;

import static org.gridgain.grid.cache.GridCacheAtomicityMode.*;
import static org.gridgain.grid.cache.GridCacheMode.*;
import static org.gridgain.grid.cache.GridCacheDistributionMode.*;
import static org.apache.ignite.transactions.IgniteTxConcurrency.*;
import static org.apache.ignite.transactions.IgniteTxIsolation.*;

/**
 * Tests that common cache objects' toString() methods do not lead to stack overflow.
 */
public class GridCacheObjectToStringSelfTest extends GridCommonAbstractTest {
    /** VM ip finder for TCP discovery. */
    private static TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /** Cache mode for test. */
    private GridCacheMode cacheMode;

    /** Cache eviction policy. */
    private GridCacheEvictionPolicy evictionPlc;

    /** Near enabled flag. */
    private boolean nearEnabled;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        TcpDiscoverySpi discoSpi = new TcpDiscoverySpi();
        discoSpi.setIpFinder(ipFinder);
        cfg.setDiscoverySpi(discoSpi);

        GridCacheConfiguration cacheCfg = defaultCacheConfiguration();

        cacheCfg.setCacheMode(cacheMode);
        cacheCfg.setEvictionPolicy(evictionPlc);
        cacheCfg.setDistributionMode(nearEnabled ? NEAR_PARTITIONED : PARTITIONED_ONLY);
        cacheCfg.setAtomicityMode(TRANSACTIONAL);

        cfg.setCacheConfiguration(cacheCfg);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        super.afterTest();

        evictionPlc = null;
    }

    /** @throws Exception If failed. */
    public void testLocalCacheFifoEvictionPolicy() throws Exception {
        cacheMode = LOCAL;
        evictionPlc = new GridCacheFifoEvictionPolicy();

        checkToString();
    }

    /** @throws Exception If failed. */
    public void testLocalCacheLruEvictionPolicy() throws Exception {
        cacheMode = LOCAL;
        evictionPlc = new GridCacheLruEvictionPolicy();

        checkToString();
    }

    /** @throws Exception If failed. */
    public void testReplicatedCacheFifoEvictionPolicy() throws Exception {
        cacheMode = REPLICATED;
        evictionPlc = new GridCacheFifoEvictionPolicy();

        checkToString();
    }

    /** @throws Exception If failed. */
    public void testReplicatedCacheLruEvictionPolicy() throws Exception {
        cacheMode = REPLICATED;
        evictionPlc = new GridCacheLruEvictionPolicy();

        checkToString();
    }

    /** @throws Exception If failed. */
    public void testPartitionedCacheFifoEvictionPolicy() throws Exception {
        cacheMode = PARTITIONED;
        nearEnabled = true;
        evictionPlc = new GridCacheFifoEvictionPolicy();

        checkToString();
    }

    /** @throws Exception If failed. */
    public void testPartitionedCacheLruEvictionPolicy() throws Exception {
        cacheMode = PARTITIONED;
        nearEnabled = true;
        evictionPlc = new GridCacheLruEvictionPolicy();

        checkToString();
    }

    /** @throws Exception If failed. */
    public void testColocatedCacheFifoEvictionPolicy() throws Exception {
        cacheMode = PARTITIONED;
        nearEnabled = false;
        evictionPlc = new GridCacheFifoEvictionPolicy();

        checkToString();
    }

    /** @throws Exception If failed. */
    public void testColocatedCacheLruEvictionPolicy() throws Exception {
        cacheMode = PARTITIONED;
        nearEnabled = false;
        evictionPlc = new GridCacheLruEvictionPolicy();

        checkToString();
    }

    /** @throws Exception If failed. */
    private void checkToString() throws Exception {
        Ignite g = startGrid(0);

        try {
            GridCache<Object, Object> cache = g.cache(null);

            for (int i = 0; i < 10; i++)
                cache.put(i, i);

            for (int i = 0; i < 10; i++) {
                GridCacheEntryEx<Object, Object> entry = ((GridKernal)g).context().cache().internalCache().peekEx(i);

                if (entry != null)
                    assertFalse("Entry is locked after implicit transaction commit: " + entry, entry.lockedByAny());
            }

            Set<GridCacheEntry<Object, Object>> entries = cache.entrySet();

            assertNotNull(entries);
            assertFalse(entries.toString().isEmpty());

            try (IgniteTx tx = cache.txStart(PESSIMISTIC, REPEATABLE_READ)) {
                assertEquals(1, cache.get(1));

                cache.put(2, 22);

                assertFalse(tx.toString().isEmpty());

                entries = cache.entrySet();

                assertNotNull(entries);
                assertFalse(entries.toString().isEmpty());

                tx.commit();
            }
        }
        finally {
            stopAllGrids();
        }
    }
}
