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
import org.apache.ignite.lang.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.transactions.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.testframework.*;
import org.gridgain.testframework.junits.common.*;

import java.util.*;
import java.util.concurrent.atomic.*;

import static org.gridgain.grid.cache.GridCacheAtomicityMode.*;
import static org.gridgain.grid.cache.GridCacheMode.*;

/**
 * Affinity routing tests.
 */
public class GridCacheVariableTopologySelfTest extends GridCommonAbstractTest {
    /** */
    private static final Random RAND = new Random();

    /** */
    private TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /** Constructs test. */
    public GridCacheVariableTopologySelfTest() {
        super(/* don't start grid */ false);
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        TcpDiscoverySpi spi = new TcpDiscoverySpi();

        spi.setIpFinder(ipFinder);

        cfg.setDiscoverySpi(spi);

        // Default cache configuration.
        GridCacheConfiguration cacheCfg = defaultCacheConfiguration();

        cacheCfg.setCacheMode(PARTITIONED);
        cacheCfg.setBackups(1);
        cacheCfg.setWriteSynchronizationMode(GridCacheWriteSynchronizationMode.FULL_SYNC);
        cacheCfg.setAtomicityMode(TRANSACTIONAL);

        cfg.setCacheConfiguration(cacheCfg);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        super.afterTest();

        stopAllGrids();

        assert G.allGrids().isEmpty();
    }

    /**
     * @param cnt Number of grids to starts.
     * @param startIdx Start grid index.
     * @throws Exception If failed to start grids.
     */
    private void startGrids(int cnt, int startIdx) throws Exception {
        assert startIdx >= 0;
        assert cnt >= 0;

        for (int idx = startIdx; idx < startIdx + cnt; idx++)
            startGrid(idx);
    }

    /**
     * JUnit.
     *
     * @throws Exception If failed.
     */
    @SuppressWarnings({"TooBroadScope"})
    public void testNodeStop() throws Exception {
        // -- Test parameters. -- //
        int nodeCnt = 3;
        int threadCnt = 20;
        final int txCnt = 1000;
        final long txDelay = 0;
        final int keyRange = 1000;
        final int logMod = 20;

        assert nodeCnt > 1 : "Node count for this test must be greater than 1.";

        startGrids(nodeCnt, 0);

        final AtomicBoolean done = new AtomicBoolean();

        IgniteFuture<?> fut = GridTestUtils.runMultiThreadedAsync(new CAX() {
            /** */
            private int cnt;

            @SuppressWarnings({"BusyWait"})
            @Override public void applyx() throws IgniteCheckedException {
                while (cnt++ < txCnt && !done.get()) {
                    GridCache<Object, Object> cache = grid(0).cache(null);

                    if (cnt % logMod == 0)
                        info("Starting transaction: " + cnt);

                    try (IgniteTx tx = cache.txStart()) {
                        int kv = RAND.nextInt(keyRange);

                        cache.put(kv, kv);

                        cache.get(kv);

                        tx.commit();
                    }
                    catch (IgniteTxOptimisticException e) {
                        info("Caught cache optimistic exception: " + e);
                    }

                    try {
                        Thread.sleep(txDelay);
                    }
                    catch (InterruptedException ignored) {
                        // No-op.
                    }
                }
            }
        }, threadCnt, "TEST-THREAD");

        Thread.sleep(2000);

        for (int idx = 1; idx < nodeCnt; idx++) {
            info("Stopping node: " + idx);

            stopGrid(idx);
        }

        // This is just for debugging.
        /*
        GridFuture<?> debugFut = GridTestUtils.runMultiThreadedAsync(new Runnable() {
            @SuppressWarnings({"UnusedDeclaration"})
            @Override public void run() {
                GridCache<Object, Object> cache = grid(0).cache(null);

                try {
                    Thread.sleep(15000);
                }
                catch (InterruptedException ignored) {
                    return;
                }

                info("Set breakpoint here.");
            }
        }, 1, "TEST-THREAD");
        */

        done.set(true);

        fut.get();

        stopGrid(0);

        info("Grid 0 stopped.");

        //debugFut.get();
    }
}
