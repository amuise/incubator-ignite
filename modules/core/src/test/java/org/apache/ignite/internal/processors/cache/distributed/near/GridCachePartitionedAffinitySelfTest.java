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

package org.apache.ignite.internal.processors.cache.distributed.near;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.affinity.*;
import org.apache.ignite.cache.affinity.consistenthash.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.events.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.resources.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.testframework.*;
import org.apache.ignite.testframework.junits.common.*;

import java.util.*;
import java.util.concurrent.atomic.*;

import static org.apache.ignite.cache.CacheAtomicityMode.*;
import static org.apache.ignite.cache.CacheDistributionMode.*;
import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.cache.CachePreloadMode.*;
import static org.apache.ignite.cache.affinity.consistenthash.CacheConsistentHashAffinityFunction.*;
import static org.apache.ignite.events.EventType.*;

/**
 * Partitioned affinity test.
 */
@SuppressWarnings({"PointlessArithmeticExpression"})
public class GridCachePartitionedAffinitySelfTest extends GridCommonAbstractTest {
    /** Backup count. */
    private static final int BACKUPS = 1;

    /** Grid count. */
    private static final int GRIDS = 3;

    /** Fail flag. */
    private static AtomicBoolean failFlag = new AtomicBoolean(false);

    /** */
    private TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        CacheConfiguration cacheCfg = defaultCacheConfiguration();

        cacheCfg.setCacheMode(PARTITIONED);
        cacheCfg.setBackups(BACKUPS);
        cacheCfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);
        cacheCfg.setPreloadMode(SYNC);
        cacheCfg.setAtomicityMode(TRANSACTIONAL);
        cacheCfg.setDistributionMode(NEAR_PARTITIONED);

        TcpDiscoverySpi spi = new TcpDiscoverySpi();

        spi.setIpFinder(ipFinder);

        IgniteConfiguration cfg = super.getConfiguration(gridName);

        cfg.setCacheConfiguration(cacheCfg);
        cfg.setDiscoverySpi(spi);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        startGrids(GRIDS);
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        stopAllGrids();
    }

    /**
     * @param ignite Grid.
     * @return Affinity.
     */
    static CacheAffinity<Object> affinity(Ignite ignite) {
        return ignite.affinity(null);
    }

    /**
     * @param aff Affinity.
     * @param key Key.
     * @return Nodes.
     */
    private static Collection<? extends ClusterNode> nodes(CacheAffinity<Object> aff, Object key) {
        return aff.mapKeyToPrimaryAndBackups(key);
    }

    /** Test predefined affinity - must be ported to all clients. */
    @SuppressWarnings("UnaryPlus")
    public void testPredefined() throws IgniteCheckedException {
        CacheConsistentHashAffinityFunction aff = new CacheConsistentHashAffinityFunction();

        getTestResources().inject(aff);

        aff.setHashIdResolver(new CacheAffinityNodeIdHashResolver());

        List<ClusterNode> nodes = new ArrayList<>();

        nodes.add(createNode("000ea4cd-f449-4dcb-869a-5317c63bd619", 50));
        nodes.add(createNode("010ea4cd-f449-4dcb-869a-5317c63bd62a", 60));
        nodes.add(createNode("0209ec54-ff53-4fdb-8239-5a3ac1fb31bd", 70));
        nodes.add(createNode("0309ec54-ff53-4fdb-8239-5a3ac1fb31ef", 80));
        nodes.add(createNode("040c9b94-02ae-45a6-9d5c-a066dbdf2636", 90));
        nodes.add(createNode("050c9b94-02ae-45a6-9d5c-a066dbdf2747", 100));
        nodes.add(createNode("0601f916-4357-4cfe-a7df-49d4721690bf", 110));
        nodes.add(createNode("0702f916-4357-4cfe-a7df-49d4721691c0", 120));

        Map<Object, Integer> data = new LinkedHashMap<>();

        data.put("", 4);
        data.put("asdf", 4);
        data.put("224ea4cd-f449-4dcb-869a-5317c63bd619", 5);
        data.put("fdc9ec54-ff53-4fdb-8239-5a3ac1fb31bd", 2);
        data.put("0f9c9b94-02ae-45a6-9d5c-a066dbdf2636", 2);
        data.put("d8f1f916-4357-4cfe-a7df-49d4721690bf", 7);
        data.put("c77ffeae-78a1-4ee6-a0fd-8d197a794412", 3);
        data.put("35de9f21-3c9b-4f4a-a7d5-3e2c6cb01564", 1);
        data.put("d67eb652-4e76-47fb-ad4e-cd902d9b868a", 7);

        data.put(0, 4);
        data.put(1, 7);
        data.put(12, 5);
        data.put(123, 6);
        data.put(1234, 4);
        data.put(12345, 6);
        data.put(123456, 6);
        data.put(1234567, 6);
        data.put(12345678, 0);
        data.put(123456789, 7);
        data.put(1234567890, 7);
        data.put(1234567890L, 7);
        data.put(12345678901L, 2);
        data.put(123456789012L, 1);
        data.put(1234567890123L, 0);
        data.put(12345678901234L, 1);
        data.put(123456789012345L, 6);
        data.put(1234567890123456L, 7);
        data.put(-23456789012345L, 4);
        data.put(-2345678901234L, 1);
        data.put(-234567890123L, 5);
        data.put(-23456789012L, 5);
        data.put(-2345678901L, 7);
        data.put(-234567890L, 4);
        data.put(-234567890, 7);
        data.put(-23456789, 7);
        data.put(-2345678, 0);
        data.put(-234567, 6);
        data.put(-23456, 6);
        data.put(-2345, 6);
        data.put(-234, 7);
        data.put(-23, 5);
        data.put(-2, 4);

        data.put(0x80000000, 4);
        data.put(0x7fffffff, 7);
        data.put(0x8000000000000000L, 4);
        data.put(0x7fffffffffffffffL, 4);

        data.put(+1.1, 3);
        data.put(-10.01, 4);
        data.put(+100.001, 4);
        data.put(-1000.0001, 4);
        data.put(+1.7976931348623157E+308, 6);
        data.put(-1.7976931348623157E+308, 6);
        data.put(+4.9E-324, 7);
        data.put(-4.9E-324, 7);

        boolean ok = true;

        for (Map.Entry<Object, Integer> entry : data.entrySet()) {
            int part = aff.partition(entry.getKey());
            Collection<ClusterNode> affNodes = aff.nodes(part, nodes, 1);
            UUID act = F.first(affNodes).id();
            UUID exp = nodes.get(entry.getValue()).id();

            if (!exp.equals(act)) {
                ok = false;

                info("Failed to validate affinity for key '" + entry.getKey() + "' [expected=" + exp +
                    ", actual=" + act + ".");
            }
        }

        if (ok)
            return;

        fail("Server partitioned affinity validation fails.");
    }

    /** Test predefined affinity - must be ported to other clients. */
    @SuppressWarnings("UnaryPlus")
    public void testPredefinedHashIdResolver() throws IgniteCheckedException {
        // Use Md5 hasher for this test.
        CacheConsistentHashAffinityFunction aff = new CacheConsistentHashAffinityFunction();

        getTestResources().inject(aff);

        aff.setHashIdResolver(new CacheAffinityNodeHashResolver() {
            @Override public Object resolve(ClusterNode node) {
                return node.attribute(DFLT_REPLICA_COUNT_ATTR_NAME);
            }
        });

        List<ClusterNode> nodes = new ArrayList<>();

        nodes.add(createNode("000ea4cd-f449-4dcb-869a-5317c63bd619", 50));
        nodes.add(createNode("010ea4cd-f449-4dcb-869a-5317c63bd62a", 60));
        nodes.add(createNode("0209ec54-ff53-4fdb-8239-5a3ac1fb31bd", 70));
        nodes.add(createNode("0309ec54-ff53-4fdb-8239-5a3ac1fb31ef", 80));
        nodes.add(createNode("040c9b94-02ae-45a6-9d5c-a066dbdf2636", 90));
        nodes.add(createNode("050c9b94-02ae-45a6-9d5c-a066dbdf2747", 100));
        nodes.add(createNode("0601f916-4357-4cfe-a7df-49d4721690bf", 110));
        nodes.add(createNode("0702f916-4357-4cfe-a7df-49d4721691c0", 120));

        Map<Object, Integer> data = new LinkedHashMap<>();

        data.put("", 4);
        data.put("asdf", 3);
        data.put("224ea4cd-f449-4dcb-869a-5317c63bd619", 5);
        data.put("fdc9ec54-ff53-4fdb-8239-5a3ac1fb31bd", 2);
        data.put("0f9c9b94-02ae-45a6-9d5c-a066dbdf2636", 2);
        data.put("d8f1f916-4357-4cfe-a7df-49d4721690bf", 4);
        data.put("c77ffeae-78a1-4ee6-a0fd-8d197a794412", 3);
        data.put("35de9f21-3c9b-4f4a-a7d5-3e2c6cb01564", 4);
        data.put("d67eb652-4e76-47fb-ad4e-cd902d9b868a", 2);

        data.put(0, 4);
        data.put(1, 1);
        data.put(12, 7);
        data.put(123, 1);
        data.put(1234, 6);
        data.put(12345, 2);
        data.put(123456, 5);
        data.put(1234567, 4);
        data.put(12345678, 6);
        data.put(123456789, 3);
        data.put(1234567890, 3);
        data.put(1234567890L, 3);
        data.put(12345678901L, 0);
        data.put(123456789012L, 1);
        data.put(1234567890123L, 3);
        data.put(12345678901234L, 5);
        data.put(123456789012345L, 5);
        data.put(1234567890123456L, 7);
        data.put(-23456789012345L, 6);
        data.put(-2345678901234L, 4);
        data.put(-234567890123L, 3);
        data.put(-23456789012L, 0);
        data.put(-2345678901L, 4);
        data.put(-234567890L, 5);
        data.put(-234567890, 3);
        data.put(-23456789, 3);
        data.put(-2345678, 6);
        data.put(-234567, 4);
        data.put(-23456, 5);
        data.put(-2345, 2);
        data.put(-234, 7);
        data.put(-23, 6);
        data.put(-2, 6);

        data.put(0x80000000, 7);
        data.put(0x7fffffff, 1);
        data.put(0x8000000000000000L, 7);
        data.put(0x7fffffffffffffffL, 7);

        data.put(+1.1, 2);
        data.put(-10.01, 0);
        data.put(+100.001, 2);
        data.put(-1000.0001, 0);
        data.put(+1.7976931348623157E+308, 6);
        data.put(-1.7976931348623157E+308, 1);
        data.put(+4.9E-324, 1);
        data.put(-4.9E-324, 1);

        boolean ok = true;

        for (Map.Entry<Object, Integer> entry : data.entrySet()) {
            int part = aff.partition(entry.getKey());

            UUID exp = nodes.get(entry.getValue()).id();
            UUID act = F.first(aff.nodes(part, nodes, 1)).id();

            if (!exp.equals(act)) {
                ok = false;

                info("Failed to validate affinity for key '" + entry.getKey() + "' [expected=" + exp +
                    ", actual=" + act + ".");
            }
        }

        if (ok)
            return;

        fail("Server partitioned affinity validation fails.");
    }

    /**
     * Create node with specified node id and replica count.
     *
     * @param nodeId Node id.
     * @param replicaCnt Node partitioned affinity replica count.
     * @return New node with specified node id and replica count.
     */
    private ClusterNode createNode(String nodeId, int replicaCnt) {
        GridTestNode node = new GridTestNode(UUID.fromString(nodeId));

        node.setAttribute(DFLT_REPLICA_COUNT_ATTR_NAME, replicaCnt);

        return node;
    }

    /** @throws Exception If failed. */
    public void testAffinity() throws Exception {
        waitTopologyUpdate();

        Object key = 12345;

        Collection<? extends ClusterNode> nodes = null;

        for (int i = 0; i < GRIDS; i++) {
            Collection<? extends ClusterNode> affNodes = nodes(affinity(grid(i)), key);

            info("Affinity picture for grid [i=" + i + ", aff=" + U.toShortString(affNodes));

            if (nodes == null)
                nodes = affNodes;
            else
                assert F.eqOrdered(nodes, affNodes);
        }
    }

    /**
     * @param g Grid.
     * @param keyCnt Key count.
     */
    private static synchronized void printAffinity(Ignite g, int keyCnt) {
        X.println(">>>");
        X.println(">>> Printing affinity for node: " + g.name());

        CacheAffinity<Object> aff = affinity(g);

        for (int i = 0; i < keyCnt; i++) {
            Collection<? extends ClusterNode> affNodes = nodes(aff, i);

            X.println(">>> Affinity nodes [key=" + i + ", partition=" + aff.partition(i) +
                ", nodes=" + U.nodes2names(affNodes) + ", ids=" + U.nodeIds(affNodes) + ']');
        }

        partitionMap(g);
    }

    /** @param g Grid. */
    private static void partitionMap(Ignite g) {
        X.println(">>> Full partition map for grid: " + g.name());
        X.println(">>> " + dht(g.jcache(null)).topology().partitionMap(false).toFullString());
    }

    /** @throws Exception If failed. */
    @SuppressWarnings("BusyWait")
    private void waitTopologyUpdate() throws Exception {
        GridTestUtils.waitTopologyUpdate(null, BACKUPS, log());
    }

    /** @throws Exception If failed. */
    public void testAffinityWithPut() throws Exception {
        waitTopologyUpdate();

        Ignite mg = grid(0);

        IgniteCache<Integer, String> mc = mg.jcache(null);

        int keyCnt = 10;

        printAffinity(mg, keyCnt);

        info("Registering event listener...");

        // Register event listener on remote nodes.
        compute(mg.cluster().forRemotes()).run(new ListenerJob(keyCnt, mg.name()));

        for (int i = 0; i < keyCnt; i++) {
            if (failFlag.get())
                fail("testAffinityWithPut failed.");

            info("Before putting key [key=" + i + ", grid=" + mg.name() + ']');

            mc.put(i, Integer.toString(i));

            if (failFlag.get())
                fail("testAffinityWithPut failed.");
        }

        Thread.sleep(1000);

        if (failFlag.get())
            fail("testAffinityWithPut failed.");
    }

    /**
     *
     */
    private static class ListenerJob implements IgniteRunnable {
        /** Grid. */
        @IgniteInstanceResource
        private Ignite ignite;

        /** Logger. */
        @LoggerResource
        private IgniteLogger log;

        /** */
        private int keyCnt;

        /** Master grid name. */
        private String master;

        /** */
        private AtomicInteger evtCnt = new AtomicInteger();

        /**
         *
         */
        private ListenerJob() {
            // No-op.
        }

        /**
         * @param keyCnt Key count.
         * @param master Master.
         */
        private ListenerJob(int keyCnt, String master) {
            this.keyCnt = keyCnt;
            this.master = master;
        }

        /** {@inheritDoc} */
        @Override public void run() {
            printAffinity(ignite, keyCnt);

            IgnitePredicate<Event> lsnr = new IgnitePredicate<Event>() {
                @Override public boolean apply(Event evt) {
                    CacheEvent e = (CacheEvent)evt;

                    switch (e.type()) {
                        case EVT_CACHE_OBJECT_PUT:
//                            new Exception("Dumping stack on grid [" + grid.name() + ", evtHash=" +
//                                System.identityHashCode(evt) + ']').printStackTrace(System.out);

                            log.info(">>> Grid cache event [grid=" + ignite.name() + ", name=" + e.name() +
                                ", key=" + e.key() + ", oldVal=" + e.oldValue() + ", newVal=" + e.newValue() +
                                ']');

                            evtCnt.incrementAndGet();

                            if (!ignite.name().equals(master) && evtCnt.get() > keyCnt * (BACKUPS + 1)) {
                                failFlag.set(true);

                                fail("Invalid put event count on grid [cnt=" + evtCnt.get() + ", grid=" +
                                    ignite.name() + ']');
                            }

                            Collection<? extends ClusterNode> affNodes = nodes(affinity(ignite), e.key());

                            if (!affNodes.contains(ignite.cluster().localNode())) {
                                failFlag.set(true);

                                fail("Key should not be mapped to node [key=" + e.key() + ", node=" + ignite.name() + ']');
                            }

                            break;

                        default:
                            failFlag.set(true);

                            fail("Invalid cache event [grid=" + ignite + ", evt=" + evt + ']');
                    }

                    return true;
                }
            };

            ignite.events().localListen(lsnr,
                EVT_CACHE_OBJECT_PUT,
                EVT_CACHE_OBJECT_READ,
                EVT_CACHE_OBJECT_REMOVED);
        }
    }
}
