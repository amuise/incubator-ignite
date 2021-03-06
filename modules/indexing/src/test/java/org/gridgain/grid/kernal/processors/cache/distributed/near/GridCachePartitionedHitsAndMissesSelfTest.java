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

package org.gridgain.grid.kernal.processors.cache.distributed.near;

import org.apache.ignite.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.dataload.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.transactions.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.query.*;
import org.gridgain.testframework.junits.common.*;

import java.util.*;

import static org.gridgain.grid.cache.GridCacheDistributionMode.*;
import static org.gridgain.grid.cache.GridCacheMode.*;
import static org.gridgain.grid.cache.GridCacheWriteSynchronizationMode.*;

/**
 * Test for issue GG-3997 Total Hits and Misses display wrong value for in-memory database.
 */
public class GridCachePartitionedHitsAndMissesSelfTest extends GridCommonAbstractTest {
    /** Amount of grids to start. */
    private static final int GRID_CNT = 3;

    /** Count of total numbers to generate. */
    private static final int CNT = 2000;

    /** IP Finder. */
    private static final TcpDiscoveryIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder(true);

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        // DiscoverySpi
        TcpDiscoverySpi disco = new TcpDiscoverySpi();
        disco.setIpFinder(IP_FINDER);
        cfg.setDiscoverySpi(disco);

        // Cache.
        cfg.setCacheConfiguration(cacheConfiguration(gridName));

        TransactionsConfiguration tCfg = new TransactionsConfiguration();

        tCfg.setDefaultTxConcurrency(IgniteTxConcurrency.PESSIMISTIC);
        tCfg.setDefaultTxIsolation(IgniteTxIsolation.REPEATABLE_READ);

        cfg.setTransactionsConfiguration(tCfg);

        return cfg;
    }

    /**
     * Cache configuration.
     *
     * @param gridName Grid name.
     * @return Cache configuration.
     * @throws Exception In case of error.
     */
    protected GridCacheConfiguration cacheConfiguration(String gridName) throws Exception {
        GridCacheConfiguration cfg = defaultCacheConfiguration();
        cfg.setCacheMode(PARTITIONED);
        cfg.setStartSize(700000);
        cfg.setWriteSynchronizationMode(FULL_ASYNC);
        cfg.setEvictionPolicy(null);
        cfg.setBackups(1);
        cfg.setDistributionMode(PARTITIONED_ONLY);
        cfg.setPreloadPartitionedDelay(-1);
        cfg.setBackups(1);

        GridCacheQueryConfiguration qcfg = new GridCacheQueryConfiguration();

        qcfg.setIndexPrimitiveKey(true);

        cfg.setQueryConfiguration(qcfg);

        return cfg;
    }

    /**
     * This test is just a wrapper for org.gridgain.examples1.data.realtime.GridPopularNumbersRealTimeExample
     *
     * @throws Exception If failed.
     */
    public void testHitsAndMisses() throws Exception {
        assert(GRID_CNT > 0);

        startGrids(GRID_CNT);

        try {
            final Ignite g = grid(0);

            realTimePopulate(g);

            // Check metrics for the whole cache.
            long hits = 0;
            long misses = 0;

            for (int i = 0; i < GRID_CNT; i++) {
                GridCacheMetrics m = grid(i).cache(null).metrics();

                hits += m.hits();
                misses += m.misses();
            }

            assertEquals(CNT/2, hits);
            assertEquals(CNT/2, misses);
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * Populates cache with data loader.
     *
     * @param g Grid.
     * @throws IgniteCheckedException If failed.
     */
    private static void realTimePopulate(final Ignite g) throws IgniteCheckedException {
        try (IgniteDataLoader<Integer, Long> ldr = g.dataLoader(null)) {
            // Sets max values to 1 so cache metrics have correct values.
            ldr.perNodeParallelLoadOperations(1);

            // Count closure which increments a count on remote node.
            ldr.updater(new IncrementingUpdater());

            for (int i = 0; i < CNT; i++)
                ldr.addData(i % (CNT / 2), 1L);
        }
    }

    /**
     * Increments value for key.
     */
    private static class IncrementingUpdater implements IgniteDataLoadCacheUpdater<Integer, Long> {
        /** */
        private static final IgniteClosure<Long, Long> INC = new IgniteClosure<Long, Long>() {
            @Override public Long apply(Long e) {
                return e == null ? 1L : e + 1;
            }
        };

        /** {@inheritDoc} */
        @Override public void update(GridCache<Integer, Long> cache,
            Collection<Map.Entry<Integer, Long>> entries) throws IgniteCheckedException {
            for (Map.Entry<Integer, Long> entry : entries)
                cache.transform(entry.getKey(), INC);
        }
    }
}
