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

package org.gridgain.grid.kernal.processors.cache.distributed.dht;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.events.*;
import org.apache.ignite.lang.*;
import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.affinity.*;
import org.gridgain.grid.cache.affinity.consistenthash.*;
import org.gridgain.grid.kernal.processors.cache.distributed.dht.preloader.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.testframework.junits.common.*;

import java.util.*;

import static org.apache.ignite.configuration.IgniteDeploymentMode.*;
import static org.apache.ignite.events.IgniteEventType.*;
import static org.gridgain.grid.cache.GridCacheConfiguration.*;
import static org.gridgain.grid.cache.GridCacheMode.*;
import static org.gridgain.grid.cache.GridCachePreloadMode.*;
import static org.gridgain.grid.cache.GridCacheWriteSynchronizationMode.*;
import static org.gridgain.grid.kernal.processors.cache.distributed.dht.GridDhtPartitionState.*;

/**
 * Test cases for partitioned cache {@link GridDhtPreloader preloader}.
 */
public class GridCacheDhtPreloadSelfTest extends GridCommonAbstractTest {
    /** Flag to print preloading events. */
    private static final boolean DEBUG = false;

    /** */
    private static final long TEST_TIMEOUT = 5 * 60 * 1000;

    /** Default backups. */
    private static final int DFLT_BACKUPS = 1;

    /** Partitions. */
    private static final int DFLT_PARTITIONS = 521;

    /** Preload batch size. */
    private static final int DFLT_BATCH_SIZE = DFLT_PRELOAD_BATCH_SIZE;

    /** Number of key backups. Each test method can set this value as required. */
    private int backups = DFLT_BACKUPS;

    /** Preload mode. */
    private GridCachePreloadMode preloadMode = ASYNC;

    /** */
    private int preloadBatchSize = DFLT_BATCH_SIZE;

    /** Number of partitions. */
    private int partitions = DFLT_PARTITIONS;

    /** IP finder. */
    private TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /**
     *
     */
    public GridCacheDhtPreloadSelfTest() {
        super(false /*start grid. */);
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        TcpDiscoverySpi disco = new TcpDiscoverySpi();

        disco.setIpFinder(ipFinder);

        cfg.setDiscoverySpi(disco);
        cfg.setCacheConfiguration(cacheConfiguration(gridName));
        cfg.setDeploymentMode(CONTINUOUS);

        return cfg;
    }

    /**
     * Gets cache configuration for grid with given name.
     *
     * @param gridName Grid name.
     * @return Cache configuration.
     */
    protected GridCacheConfiguration cacheConfiguration(String gridName) {
        GridCacheConfiguration cacheCfg = defaultCacheConfiguration();

        cacheCfg.setCacheMode(PARTITIONED);
        cacheCfg.setPreloadBatchSize(preloadBatchSize);
        cacheCfg.setWriteSynchronizationMode(FULL_SYNC);
        cacheCfg.setPreloadMode(preloadMode);
        cacheCfg.setAffinity(new GridCacheConsistentHashAffinityFunction(false, partitions));
        cacheCfg.setBackups(backups);

        return cacheCfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
//        resetLog4j(Level.DEBUG, true,
//            // Categories.
//            GridDhtPreloader.class.getPackage().getName(),
//            GridDhtPartitionTopologyImpl.class.getName(),
//            GridDhtLocalPartition.class.getName());
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        backups = DFLT_BACKUPS;
        partitions = DFLT_PARTITIONS;
        preloadMode = ASYNC;
        preloadBatchSize = DFLT_BATCH_SIZE;
    }

    /** {@inheritDoc} */
    @Override protected long getTestTimeout() {
        return TEST_TIMEOUT;
    }

    /**
     * @param cache Cache.
     * @return Affinity.
     */
    @SuppressWarnings({"unchecked"})
    private GridCacheAffinity<Integer> affinity(GridCache<Integer, ?> cache) {
        return cache.affinity();
    }

    /**
     * @param c Cache.
     * @return {@code True} if synchronous preloading.
     */
    private boolean isSync(GridCache<?, ?> c) {
        return c.configuration().getPreloadMode() == SYNC;
    }

    /**
     * @throws Exception If failed.
     */
    public void testActivePartitionTransferSyncSameCoordinator() throws Exception {
        preloadMode = SYNC;

        checkActivePartitionTransfer(1000, 4, true, false);
    }

    /**
     * @throws Exception If failed.
     */
    public void testActivePartitionTransferAsyncSameCoordinator() throws Exception {
        checkActivePartitionTransfer(1000, 4, true, false);
    }

    /**
     * @throws Exception If failed.
     */
    public void testActivePartitionTransferSyncChangingCoordinator() throws Exception {
        preloadMode = SYNC;

        checkActivePartitionTransfer(1000, 4, false, false);
    }

    /**
     * @throws Exception If failed.
     */
    public void testActivePartitionTransferAsyncChangingCoordinator() throws Exception {
        checkActivePartitionTransfer(1000, 4, false, false);
    }

    /**
     * @throws Exception If failed.
     */
    public void testActivePartitionTransferSyncRandomCoordinator() throws Exception {
        preloadMode = SYNC;

        checkActivePartitionTransfer(1000, 4, false, true);
    }

    /**
     * @throws Exception If failed.
     */
    public void testActivePartitionTransferAsyncRandomCoordinator() throws Exception {
        checkActivePartitionTransfer(1000, 4, false, true);
    }

    /**
     * @param keyCnt Key count.
     * @param nodeCnt Node count.
     * @param sameCoord Same coordinator flag.
     * @param shuffle Shuffle flag.
     * @throws Exception If failed.
     */
    private void checkActivePartitionTransfer(int keyCnt, int nodeCnt, boolean sameCoord, boolean shuffle)
        throws Exception {
//        resetLog4j(Level.DEBUG, true,
//            // Categories.
//            GridDhtPreloader.class.getPackage().getName(),
//            GridDhtPartitionTopologyImpl.class.getName(),
//            GridDhtLocalPartition.class.getName());

        try {
            Ignite ignite1 = startGrid(0);

            GridCache<Integer, String> cache1 = ignite1.cache(null);

            putKeys(cache1, keyCnt);
            checkKeys(cache1, keyCnt, F.asList(ignite1));

            List<Ignite> ignites = new ArrayList<>(nodeCnt + 1);

            startGrids(nodeCnt, 1, ignites);

            // Check all nodes.
            for (Ignite g : ignites) {
                GridCache<Integer, String> c = g.cache(null);

                checkKeys(c, keyCnt, ignites);
            }

            if (shuffle)
                Collections.shuffle(ignites);

            if (sameCoord)
                // Add last.
                ignites.add(ignite1);
            else
                // Add first.
                ignites.add(0, ignite1);

            if (!sameCoord && shuffle)
                Collections.shuffle(ignites);

            checkActiveState(ignites);

            info(">>> Finished checking nodes [keyCnt=" + keyCnt + ", nodeCnt=" + nodeCnt + ", grids=" +
                U.grids2names(ignites) + ']');

            Collection<IgniteFuture<?>> futs = new LinkedList<>();

            Ignite last = F.last(ignites);

            for (Iterator<Ignite> it = ignites.iterator(); it.hasNext(); ) {
                Ignite g = it.next();

                if (!it.hasNext()) {
                    assert last == g;

                    break;
                }

                checkActiveState(ignites);

                final UUID nodeId = g.cluster().localNode().id();

                it.remove();

                futs.add(waitForLocalEvent(last.events(), new P1<IgniteEvent>() {
                    @Override public boolean apply(IgniteEvent e) {
                        IgniteCachePreloadingEvent evt = (IgniteCachePreloadingEvent)e;

                        ClusterNode node = evt.discoveryNode();

                        return evt.type() == EVT_CACHE_PRELOAD_STOPPED && node.id().equals(nodeId) &&
                            evt.discoveryEventType() == EVT_NODE_LEFT;
                    }
                }, EVT_CACHE_PRELOAD_STOPPED));

                info("Before grid stop [name=" + g.name() + ", fullTop=" + top2string(ignites));

                stopGrid(g.name());

                info("After grid stop [name=" + g.name() + ", fullTop=" + top2string(ignites));

                // Check all left nodes.
                checkActiveState(ignites);
            }

            info("Waiting for preload futures: " + F.view(futs, F.unfinishedFutures()));

            X.waitAll(futs);

            info("Finished waiting for preload futures.");

            assert last != null;

            GridCache<Integer, String> lastCache = last.cache(null);

            GridDhtCacheAdapter<Integer, String> dht = dht(lastCache);

            GridCacheAffinity<Integer> aff = affinity(lastCache);

            info("Finished waiting for all exchange futures...");

            for (int i = 0; i < keyCnt; i++) {
                if (aff.mapPartitionToPrimaryAndBackups(aff.partition(i)).contains(last.cluster().localNode())) {
                    GridDhtPartitionTopology<Integer, String> top = dht.topology();

                    for (GridDhtLocalPartition<Integer, String> p : top.localPartitions()) {
                        Collection<ClusterNode> moving = top.moving(p.id());

                        assert moving.isEmpty() : "Nodes with partition in moving state [part=" + p +
                            ", moving=" + moving + ']';

                        assert OWNING == p.state() : "Invalid partition state for partition [part=" + p + ", map=" +
                            top.partitionMap(false) + ']';
                    }
                }
            }

            checkActiveState(ignites);
        }
        catch (Error | Exception e) {
            error("Test failed.", e);

            throw e;
        } finally {
            stopAllGrids();
        }
    }

    /**
     * @param grids Grids.
     */
    private void checkActiveState(Iterable<Ignite> grids) {
        // Check that nodes don't have non-active information about other nodes.
        for (Ignite g : grids) {
            GridCache<Integer, String> c = g.cache(null);

            GridDhtCacheAdapter<Integer, String> dht = dht(c);

            GridDhtPartitionFullMap allParts = dht.topology().partitionMap(false);

            for (GridDhtPartitionMap parts : allParts.values()) {
                if (!parts.nodeId().equals(g.cluster().localNode().id())) {
                    for (Map.Entry<Integer, GridDhtPartitionState> e : parts.entrySet()) {
                        int p = e.getKey();

                        GridDhtPartitionState state = e.getValue();

                        assert state == OWNING || state == MOVING || state == RENTING :
                            "Invalid state [grid=" + g.name() + ", part=" + p + ", state=" + state +
                                ", parts=" + parts + ']';

                        assert state.active();
                    }
                }
            }
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testMultiplePartitionBatchesSyncPreload() throws Exception {
        preloadMode = SYNC;
        preloadBatchSize = 100;
        partitions = 2;

        checkNodes(1000, 1, true, false);
    }

    /**
     * @throws Exception If failed.
     */
    public void testMultiplePartitionBatchesAsyncPreload() throws Exception {
        preloadBatchSize = 100;
        partitions = 2;

        checkNodes(1000, 1, true, false);
    }

    /**
     * @throws Exception If failed.
     */
    public void testMultipleNodesSyncPreloadSameCoordinator() throws Exception {
        preloadMode = SYNC;

        checkNodes(1000, 4, true, false);
    }

    /**
     * @throws Exception If failed.
     */
    public void testMultipleNodesAsyncPreloadSameCoordinator() throws Exception {
        checkNodes(1000, 4, true, false);
    }

    /**
     * @throws Exception If failed.
     */
    public void testMultipleNodesSyncPreloadChangingCoordinator() throws Exception {
        preloadMode = SYNC;

        checkNodes(1000, 4, false, false);
    }

    /**
     * @throws Exception If failed.
     */
    public void testMultipleNodesAsyncPreloadChangingCoordinator() throws Exception {
        checkNodes(1000, 4, false, false);
    }


    /**
     * @throws Exception If failed.
     */
    public void testMultipleNodesSyncPreloadRandomCoordinator() throws Exception {
        preloadMode = SYNC;

        checkNodes(1000, 4, false, true);
    }

    /**
     * @throws Exception If failed.
     */
    public void testMultipleNodesAsyncPreloadRandomCoordinator() throws Exception {
        checkNodes(1000, 4, false, true);
    }

    /**
     * @param cnt Number of grids.
     * @param startIdx Start node index.
     * @param list List of started grids.
     * @throws Exception If failed.
     */
    private void startGrids(int cnt, int startIdx, Collection<Ignite> list) throws Exception {
        for (int i = 0; i < cnt; i++) {
            final Ignite g = startGrid(startIdx++);

            if (DEBUG)
                g.events().localListen(new IgnitePredicate<IgniteEvent>() {
                    @Override public boolean apply(IgniteEvent evt) {
                        info("\n>>> Preload event [grid=" + g.name() + ", evt=" + evt + ']');

                        return true;
                    }
                }, EVTS_CACHE_PRELOAD);

            list.add(g);
        }
    }

    /**
     * @param grids Grids to stop.
     */
    private void stopGrids(Iterable<Ignite> grids) {
        for (Ignite g : grids)
            stopGrid(g.name());
    }

    /**
     * @param keyCnt Key count.
     * @param nodeCnt Node count.
     * @param sameCoord Same coordinator flag.
     * @param shuffle Shuffle flag.
     * @throws Exception If failed.
     */
    private void checkNodes(int keyCnt, int nodeCnt, boolean sameCoord, boolean shuffle)
        throws Exception {
//        resetLog4j(Level.DEBUG, true,
//            // Categories.
//            GridDhtPreloader.class.getPackage().getName(),
//            GridDhtPartitionTopologyImpl.class.getName(),
//            GridDhtLocalPartition.class.getName());

        try {
            Ignite ignite1 = startGrid(0);

            GridCache<Integer, String> cache1 = ignite1.cache(null);

            putKeys(cache1, keyCnt);
            checkKeys(cache1, keyCnt, F.asList(ignite1));

            List<Ignite> ignites = new ArrayList<>(nodeCnt + 1);

            startGrids(nodeCnt, 1, ignites);

            // Check all nodes.
            for (Ignite g : ignites) {
                GridCache<Integer, String> c = g.cache(null);

                checkKeys(c, keyCnt, ignites);
            }

            if (shuffle)
                Collections.shuffle(ignites);

            if (sameCoord)
                // Add last.
                ignites.add(ignite1);
            else
                // Add first.
                ignites.add(0, ignite1);

            if (!sameCoord && shuffle)
                Collections.shuffle(ignites);

            info(">>> Finished checking nodes [keyCnt=" + keyCnt + ", nodeCnt=" + nodeCnt + ", grids=" +
                U.grids2names(ignites) + ']');

            Ignite last = null;

            for (Iterator<Ignite> it = ignites.iterator(); it.hasNext(); ) {
                Ignite g = it.next();

                if (!it.hasNext()) {
                    last = g;

                    break;
                }

                final UUID nodeId = g.cluster().localNode().id();

                it.remove();

                Collection<IgniteFuture<?>> futs = new LinkedList<>();

                for (Ignite gg : ignites)
                    futs.add(waitForLocalEvent(gg.events(), new P1<IgniteEvent>() {
                            @Override public boolean apply(IgniteEvent e) {
                                IgniteCachePreloadingEvent evt = (IgniteCachePreloadingEvent)e;

                                ClusterNode node = evt.discoveryNode();

                                return evt.type() == EVT_CACHE_PRELOAD_STOPPED && node.id().equals(nodeId) &&
                                    evt.discoveryEventType() == EVT_NODE_LEFT;
                            }
                        }, EVT_CACHE_PRELOAD_STOPPED));


                info("Before grid stop [name=" + g.name() + ", fullTop=" + top2string(ignites));

                stopGrid(g.name());

                info(">>> Waiting for preload futures [leftNode=" + g.name() + ", remaining=" + U.grids2names(ignites) + ']');

                X.waitAll(futs);

                info("After grid stop [name=" + g.name() + ", fullTop=" + top2string(ignites));

                // Check all left nodes.
                for (Ignite gg : ignites) {
                    GridCache<Integer, String> c = gg.cache(null);

                    checkKeys(c, keyCnt, ignites);
                }
            }

            assert last != null;

            GridCache<Integer, String> lastCache = last.cache(null);

            GridDhtCacheAdapter<Integer, String> dht = dht(lastCache);

            GridCacheAffinity<Integer> aff = affinity(lastCache);

            for (int i = 0; i < keyCnt; i++) {
                if (aff.mapPartitionToPrimaryAndBackups(aff.partition(i)).contains(last.cluster().localNode())) {
                    GridDhtPartitionTopology<Integer, String> top = dht.topology();

                    for (GridDhtLocalPartition<Integer, String> p : top.localPartitions()) {
                        Collection<ClusterNode> moving = top.moving(p.id());

                        assert moving.isEmpty() : "Nodes with partition in moving state [part=" + p +
                            ", moving=" + moving + ']';

                        assert OWNING == p.state() : "Invalid partition state for partition [part=" + p + ", map=" +
                            top.partitionMap(false) + ']';
                    }
                }
            }
        }
        catch (Error | Exception e) {
            error("Test failed.", e);

            throw e;
        } finally {
            stopAllGrids();
        }
    }

    /**
     * @param c Cache.
     * @param cnt Key count.
     * @throws IgniteCheckedException If failed.
     */
    private void putKeys(GridCache<Integer, String> c, int cnt) throws IgniteCheckedException {
        for (int i = 0; i < cnt; i++)
            c.put(i, Integer.toString(i));
    }

    /**
     * @param cache Cache.
     * @param cnt Key count.
     * @param grids Grids.
     * @throws IgniteCheckedException If failed.
     */
    private void checkKeys(GridCache<Integer, String> cache, int cnt, Iterable<Ignite> grids) throws IgniteCheckedException {
        GridCacheAffinity<Integer> aff = affinity(cache);

        Ignite ignite = cache.gridProjection().ignite();

        ClusterNode loc = ignite.cluster().localNode();

        boolean sync = cache.configuration().getPreloadMode() == SYNC;

        for (int i = 0; i < cnt; i++) {
            Collection<ClusterNode> nodes = ignite.cluster().nodes();

            Collection<ClusterNode> affNodes = aff.mapPartitionToPrimaryAndBackups(aff.partition(i));

            assert !affNodes.isEmpty();

            if (affNodes.contains(loc)) {
                String val = sync ? cache.peek(i) : cache.get(i);

                ClusterNode primaryNode = F.first(affNodes);

                assert primaryNode != null;

                boolean primary = primaryNode.equals(loc);

                assert Integer.toString(i).equals(val) : "Key check failed [grid=" + ignite.name() +
                    ", cache=" + cache.name() + ", key=" + i + ", expected=" + i + ", actual=" + val +
                    ", part=" + aff.partition(i) + ", primary=" + primary + ", affNodes=" + U.nodeIds(affNodes) +
                    ", locId=" + loc.id() + ", allNodes=" + U.nodeIds(nodes) + ", allParts=" + top2string(grids) + ']';
            }
        }
    }

    /**
     * @param grids Grids
     * @return String representation of all partitions and their state.
     */
    @SuppressWarnings( {"ConstantConditions"})
    private String top2string(Iterable<Ignite> grids) {
        Map<String, String> map = new HashMap<>();

        for (Ignite g : grids) {
            GridCache<Integer, String> c = g.cache(null);

            GridDhtCacheAdapter<Integer, String> dht = dht(c);

            GridDhtPartitionFullMap fullMap = dht.topology().partitionMap(false);

            map.put(g.name(), DEBUG ? fullMap.toFullString() : fullMap.toString());
        }

        return "Grid partition maps [" + map.toString() + ']';
    }
}
