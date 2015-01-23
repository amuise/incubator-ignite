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

package org.apache.ignite.internal.processors.cache.distributed.dht;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.affinity.*;
import org.apache.ignite.cache.affinity.consistenthash.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.transactions.*;
import org.apache.ignite.internal.processors.cache.distributed.near.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.testframework.junits.common.*;

import java.util.*;

import static org.apache.ignite.cache.GridCacheAtomicityMode.*;
import static org.apache.ignite.cache.GridCacheMode.*;
import static org.apache.ignite.cache.GridCacheDistributionMode.*;

/**
 * Unit tests for dht entry.
 */
public class GridCacheDhtEntrySelfTest extends GridCommonAbstractTest {
    /** Grid count. */
    private static final int GRID_CNT = 2;

    /** */
    private TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        TcpDiscoverySpi spi = new TcpDiscoverySpi();

        spi.setIpFinder(ipFinder);

        cfg.setDiscoverySpi(spi);

        CacheConfiguration cacheCfg = defaultCacheConfiguration();

        cacheCfg.setCacheMode(PARTITIONED);
        cacheCfg.setAffinity(new GridCacheConsistentHashAffinityFunction(false, 10));
        cacheCfg.setBackups(0);
        cacheCfg.setWriteSynchronizationMode(GridCacheWriteSynchronizationMode.FULL_SYNC);
        cacheCfg.setSwapEnabled(false);
        cacheCfg.setAtomicityMode(TRANSACTIONAL);
        cacheCfg.setDistributionMode(NEAR_PARTITIONED);

        cfg.setCacheConfiguration(cacheCfg);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        startGridsMultiThreaded(GRID_CNT);
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        stopAllGrids();
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"SizeReplaceableByIsEmpty"})
    @Override protected void beforeTest() throws Exception {
        for (int i = 0; i < GRID_CNT; i++) {
            assert near(grid(i)).size() == 0 : "Near cache size is not zero for grid: " + i;
            assert dht(grid(i)).size() == 0 : "DHT cache size is not zero for grid: " + i;

            assert near(grid(i)).isEmpty() : "Near cache is not empty for grid: " + i;
            assert dht(grid(i)).isEmpty() : "DHT cache is not empty for grid: " + i;
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"SizeReplaceableByIsEmpty"})
    @Override protected void afterTest() throws Exception {
        for (int i = 0; i < GRID_CNT; i++) {
            near(grid(i)).removeAll(F.<GridCacheEntry<Integer, String>>alwaysTrue());

            assertEquals("Near cache size is not zero for grid: " + i, 0, near(grid(i)).size());
            assertEquals("DHT cache size is not zero for grid: " + i, 0, dht(grid(i)).size());

            assert near(grid(i)).isEmpty() : "Near cache is not empty for grid: " + i;
            assert dht(grid(i)).isEmpty() : "DHT cache is not empty for grid: " + i;
        }

        for (int i = 0; i < GRID_CNT; i++) {
            IgniteTx tx = grid(i).cache(null).tx();

            if (tx != null)
                tx.close();
        }
    }

    /**
     * @param g Grid.
     * @return Near cache.
     */
    private GridCacheProjection<Integer, String> near(Ignite g) {
        return g.cache(null);
    }

    /**
     * @param g Grid.
     * @return Dht cache.
     */
    @SuppressWarnings({"unchecked", "TypeMayBeWeakened"})
    private GridDhtCacheAdapter<Integer, String> dht(Ignite g) {
        return ((GridNearCacheAdapter)((GridKernal)g).internalCache()).dht();
    }

    /**
     * @param nodeId Node ID.
     * @return Grid.
     */
    private Ignite grid(UUID nodeId) {
        return G.ignite(nodeId);
    }

    /** @throws Exception If failed. */
    public void testClearWithReaders() throws Exception {
        Integer key = 1;

        IgniteBiTuple<ClusterNode, ClusterNode> t = getNodes(key);

        ClusterNode primary = t.get1();
        ClusterNode other = t.get2();

        GridCacheProjection<Integer, String> near0 = near(grid(primary.id()));
        GridCacheProjection<Integer, String> near1 = near(grid(other.id()));

        assert near0 != near1;

        GridDhtCacheAdapter<Integer, String> dht0 = dht(grid(primary.id()));
        GridDhtCacheAdapter<Integer, String> dht1 = dht(grid(other.id()));

        // Put on primary node.
        String val = "v1";

        near0.put(key, val);

        GridDhtCacheEntry<Integer, String> e0 = (GridDhtCacheEntry<Integer, String>)dht0.peekEx(key);
        GridDhtCacheEntry<Integer, String> e1 = (GridDhtCacheEntry<Integer, String>)dht1.peekEx(key);

        assert e0 == null || e0.readers().isEmpty();
        assert e1 == null || e1.readers().isEmpty();

        // Get value on other node.
        assertEquals(val, near1.get(key));

        assert e0 != null;

        assert e0.readers().contains(other.id());
        assert e1 == null || e1.readers().isEmpty();

        assert !near0.clear(key);

        assertEquals(1, near0.size());
        assertEquals(1, dht0.size());

        assertEquals(1, near1.size());
        assertEquals(0, dht1.size());
    }

    /** @throws Exception If failed. */
    public void testRemoveWithReaders() throws Exception {
        Integer key = 1;

        IgniteBiTuple<ClusterNode, ClusterNode> t = getNodes(key);

        ClusterNode primary = t.get1();
        ClusterNode other = t.get2();

        GridCacheProjection<Integer, String> near0 = near(grid(primary.id()));
        GridCacheProjection<Integer, String> near1 = near(grid(other.id()));

        assert near0 != near1;

        GridDhtCacheAdapter<Integer, String> dht0 = dht(grid(primary.id()));
        GridDhtCacheAdapter<Integer, String> dht1 = dht(grid(other.id()));

        // Put on primary node.
        String val = "v1";

        near0.put(key, val);

        GridDhtCacheEntry<Integer, String> e0 = (GridDhtCacheEntry<Integer, String>)dht0.peekEx(key);
        GridDhtCacheEntry<Integer, String> e1 = (GridDhtCacheEntry<Integer, String>)dht1.peekEx(key);

        assert e0 == null || e0.readers().isEmpty();
        assert e1 == null || e1.readers().isEmpty();

        // Get value on other node.
        assertEquals(val, near1.get(key));

        assert e0 != null;

        assert e0.readers().contains(other.id());
        assert e1 == null || e1.readers().isEmpty();

        assert near0.removex(key);

        assertEquals(0, near0.size());
        assertEquals(0, dht0.size());

        assertEquals(0, near1.size());
        assertEquals(0, dht1.size());
    }

    /** @throws Exception If failed. */
    @SuppressWarnings({"AssertWithSideEffects"})
    public void testEvictWithReaders() throws Exception {
        Integer key = 1;

        IgniteBiTuple<ClusterNode, ClusterNode> t = getNodes(key);

        ClusterNode primary = t.get1();
        ClusterNode other = t.get2();

        GridCacheProjection<Integer, String> near0 = near(grid(primary.id()));
        GridCacheProjection<Integer, String> near1 = near(grid(other.id()));

        assert near0 != near1;

        GridDhtCacheAdapter<Integer, String> dht0 = dht(grid(primary.id()));
        GridDhtCacheAdapter<Integer, String> dht1 = dht(grid(other.id()));

        // Put on primary node.
        String val = "v1";

        near0.put(key, val);

        GridDhtCacheEntry<Integer, String> e0 = (GridDhtCacheEntry<Integer, String>)dht0.peekEx(key);
        GridDhtCacheEntry<Integer, String> e1 = (GridDhtCacheEntry<Integer, String>)dht1.peekEx(key);

        assert e0 == null || e0.readers().isEmpty();
        assert e1 == null || e1.readers().isEmpty();

        // Get value on other node.
        assertEquals(val, near1.get(key));

        assert e0 != null;

        assert e0.readers().contains(other.id());
        assert e1 == null || e1.readers().isEmpty();

        assert !e0.evictInternal(false, dht0.context().versions().next(), null);

        assertEquals(1, near0.size());
        assertEquals(1, dht0.size());

        assertEquals(1, near1.size());
        assertEquals(0, dht1.size());

        assert !e0.evictInternal(true, dht0.context().versions().next(), null);

        assertEquals(1, near0.size());
        assertEquals(1, dht0.size());

        assertEquals(1, near1.size());
        assertEquals(0, dht1.size());
    }

    /**
     * @param key Key.
     * @return For the given key pair {primary node, some other node}.
     */
    private IgniteBiTuple<ClusterNode, ClusterNode> getNodes(Integer key) {
        GridCacheAffinity<Integer> aff = grid(0).<Integer, Object>cache(null).affinity();

        int part = aff.partition(key);

        ClusterNode primary = aff.mapPartitionToNode(part);

        assert primary != null;

        Collection<ClusterNode> nodes = new ArrayList<>(grid(0).nodes());

        nodes.remove(primary);

        ClusterNode other = F.first(nodes);

        assert other != null;

        assert !F.eqNodes(primary, other);

        return F.t(primary, other);
    }
}