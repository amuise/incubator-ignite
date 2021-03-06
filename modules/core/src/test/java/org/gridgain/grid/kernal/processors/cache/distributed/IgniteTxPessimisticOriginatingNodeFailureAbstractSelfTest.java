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

package org.gridgain.grid.kernal.processors.cache.distributed;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.resources.*;
import org.apache.ignite.spi.*;
import org.apache.ignite.transactions.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.managers.communication.*;
import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.kernal.processors.cache.distributed.near.*;
import org.apache.ignite.spi.communication.tcp.*;
import org.gridgain.grid.kernal.processors.cache.transactions.*;
import org.gridgain.grid.util.direct.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.lang.*;
import org.gridgain.testframework.*;

import java.util.*;
import java.util.concurrent.*;

import static org.apache.ignite.transactions.IgniteTxConcurrency.*;

/**
 * Abstract test for originating node failure.
 */
public abstract class IgniteTxPessimisticOriginatingNodeFailureAbstractSelfTest extends GridCacheAbstractSelfTest {
    /** */
    protected static final int GRID_CNT = 5;

    /** Ignore node ID. */
    private volatile Collection<UUID> ignoreMsgNodeIds;

    /** Ignore message class. */
    private Collection<Class<?>> ignoreMsgCls;

    /** Failing node ID. */
    private UUID failingNodeId;

    /**
     * @throws Exception If failed.
     */
    public void testManyKeysCommit() throws Exception {
        Collection<Integer> keys = new ArrayList<>(200);

        for (int i = 0; i < 200; i++)
            keys.add(i);

        testTxOriginatingNodeFails(keys, false);
    }

    /**
     * @throws Exception If failed.
     */
    public void testManyKeysRollback() throws Exception {
        Collection<Integer> keys = new ArrayList<>(200);

        for (int i = 0; i < 200; i++)
            keys.add(i);

        testTxOriginatingNodeFails(keys, true);
    }

    /**
     * @throws Exception If failed.
     */
    public void testPrimaryNodeFailureCommit() throws Exception {
        checkPrimaryNodeCrash(true);
    }

    /**
     * @throws Exception If failed.
     */
    public void testPrimaryNodeFailureRollback() throws Exception {
        checkPrimaryNodeCrash(false);
    }

    /**
     * @return Index of node starting transaction.
     */
    protected int originatingNode() {
        return 0;
    }

    /**
     * Ignores messages to given node of given type.
     *
     * @param dstNodeIds Destination node IDs.
     * @param msgCls Message type.
     */
    protected void ignoreMessages(Collection<Class<?>> msgCls, Collection<UUID> dstNodeIds) {
        ignoreMsgNodeIds = dstNodeIds;
        ignoreMsgCls = msgCls;
    }

    /**
     * Gets ignore message class to simulate partial prepare message.
     *
     * @return Ignore message class.
     */
    protected abstract Collection<Class<?>> ignoreMessageClasses();

    /**
     * @param keys Keys to update.
     * @param fullFailure Flag indicating whether to simulate rollback state.
     * @throws Exception If failed.
     */
    protected void testTxOriginatingNodeFails(Collection<Integer> keys, final boolean fullFailure) throws Exception {
        assertFalse(keys.isEmpty());

        final Collection<GridKernal> grids = new ArrayList<>();

        ClusterNode txNode = grid(originatingNode()).localNode();

        for (int i = 1; i < gridCount(); i++)
            grids.add((GridKernal)grid(i));

        failingNodeId = grid(0).localNode().id();

        final Map<Integer, String> map = new HashMap<>();

        final String initVal = "initialValue";

        for (Integer key : keys) {
            grid(originatingNode()).cache(null).put(key, initVal);

            map.put(key, String.valueOf(key));
        }

        Map<Integer, Collection<ClusterNode>> nodeMap = new HashMap<>();

        GridCacheAdapter<Integer, String> cache = ((GridKernal)grid(1)).internalCache();

        info("Node being checked: " + grid(1).localNode().id());

        for (Integer key : keys) {
            Collection<ClusterNode> nodes = new ArrayList<>();

            nodes.addAll(cache.affinity().mapKeyToPrimaryAndBackups(key));

            nodes.remove(txNode);

            nodeMap.put(key, nodes);
        }

        info("Starting tx [values=" + map + ", topVer=" +
            ((GridKernal)grid(1)).context().discovery().topologyVersion() + ']');

        if (fullFailure)
            ignoreMessages(ignoreMessageClasses(), allNodeIds());
        else
            ignoreMessages(ignoreMessageClasses(), F.asList(grid(1).localNode().id()));

        final GridEx originatingNodeGrid = grid(originatingNode());

        GridTestUtils.runAsync(new Callable<Void>() {
            @Override public Void call() throws Exception {
                GridCache<Integer, String> cache = originatingNodeGrid.cache(null);

                assertNotNull(cache);

                IgniteTx tx = cache.txStart();

                try {
                    cache.putAll(map);

                    info("Before commitAsync");

                    tx = (IgniteTx)tx.enableAsync();

                    tx.commit();

                    IgniteFuture<IgniteTx> fut = tx.future();

                    info("Got future for commitAsync().");

                    fut.get(3, TimeUnit.SECONDS);
                }
                catch (IgniteFutureTimeoutException ignored) {
                    info("Failed to wait for commit future completion [fullFailure=" + fullFailure + ']');
                }

                return null;
            }
        }).get();

        info(">>> Stopping originating node " + txNode);

        G.stop(grid(originatingNode()).name(), true);

        ignoreMessages(Collections.<Class<?>>emptyList(), Collections.<UUID>emptyList());

        info(">>> Stopped originating node: " + txNode.id());

        boolean txFinished = GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                for (GridKernal g : grids) {
                    GridCacheAdapter<?, ?> cache = g.internalCache();

                    IgniteTxManager txMgr = cache.isNear() ?
                        ((GridNearCacheAdapter)cache).dht().context().tm() :
                        cache.context().tm();

                    int txNum = txMgr.idMapSize();

                    if (txNum != 0)
                        return false;
                }

                return true;
            }
        }, 10000);

        assertTrue(txFinished);

        info("Transactions finished.");

        for (Map.Entry<Integer, Collection<ClusterNode>> e : nodeMap.entrySet()) {
            final Integer key = e.getKey();

            final String val = map.get(key);

            assertFalse(e.getValue().isEmpty());

            for (ClusterNode node : e.getValue()) {
                final UUID checkNodeId = node.id();

                compute(G.ignite(checkNodeId).cluster().forNode(node)).call(new Callable<Void>() {
                    /** */
                    @IgniteInstanceResource
                    private Ignite ignite;

                    @Override public Void call() throws Exception {
                        GridCache<Integer, String> cache = ignite.cache(null);

                        assertNotNull(cache);

                        assertEquals("Failed to check entry value on node: " + checkNodeId,
                            fullFailure ? initVal : val, cache.peek(key));

                        return null;
                    }
                });
            }
        }

        for (Map.Entry<Integer, String> e : map.entrySet()) {
            for (Ignite g : G.allGrids())
                assertEquals(fullFailure ? initVal : e.getValue(), g.cache(null).get(e.getKey()));
        }
    }

    /**
     * Checks tx data consistency in case when primary node crashes.
     *
     * @param commmit Whether to commit or rollback a transaction.
     * @throws Exception If failed.
     */
    private void checkPrimaryNodeCrash(final boolean commmit) throws Exception {
        Collection<Integer> keys = new ArrayList<>(20);

        for (int i = 0; i < 20; i++)
            keys.add(i);

        final Collection<GridKernal> grids = new ArrayList<>();

        ClusterNode primaryNode = grid(1).localNode();

        for (int i = 0; i < gridCount(); i++) {
            if (i != 1)
                grids.add((GridKernal)grid(i));
        }

        failingNodeId = primaryNode.id();

        final Map<Integer, String> map = new HashMap<>();

        final String initVal = "initialValue";

        for (Integer key : keys) {
            grid(originatingNode()).cache(null).put(key, initVal);

            map.put(key, String.valueOf(key));
        }

        Map<Integer, Collection<ClusterNode>> nodeMap = new HashMap<>();

        GridCache<Integer, String> cache = grid(0).cache(null);

        info("Failing node ID: " + grid(1).localNode().id());

        for (Integer key : keys) {
            Collection<ClusterNode> nodes = new ArrayList<>();

            nodes.addAll(cache.affinity().mapKeyToPrimaryAndBackups(key));

            nodes.remove(primaryNode);

            nodeMap.put(key, nodes);
        }

        info("Starting tx [values=" + map + ", topVer=" +
            ((GridKernal)grid(1)).context().discovery().topologyVersion() + ']');

        assertNotNull(cache);

        try (IgniteTx tx = cache.txStart()) {
            cache.getAll(keys);

            // Should not send any messages.
            cache.putAll(map);

            // Fail the node in the middle of transaction.
            info(">>> Stopping primary node " + primaryNode);

            G.stop(G.ignite(primaryNode.id()).name(), true);

            info(">>> Stopped originating node, finishing transaction: " + primaryNode.id());

            if (commmit)
                tx.commit();
            else
                tx.rollback();
        }

        boolean txFinished = GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                for (GridKernal g : grids) {
                    GridCacheAdapter<?, ?> cache = g.internalCache();

                    IgniteTxManager txMgr = cache.isNear() ?
                        ((GridNearCacheAdapter)cache).dht().context().tm() :
                        cache.context().tm();

                    int txNum = txMgr.idMapSize();

                    if (txNum != 0)
                        return false;
                }

                return true;
            }
        }, 10000);

        assertTrue(txFinished);

        info("Transactions finished.");

        for (Map.Entry<Integer, Collection<ClusterNode>> e : nodeMap.entrySet()) {
            final Integer key = e.getKey();

            final String val = map.get(key);

            assertFalse(e.getValue().isEmpty());

            for (ClusterNode node : e.getValue()) {
                final UUID checkNodeId = node.id();

                compute(G.ignite(checkNodeId).cluster().forNode(node)).call(new Callable<Void>() {
                    /** */
                    @IgniteInstanceResource
                    private Ignite ignite;

                    @Override public Void call() throws Exception {
                        GridCache<Integer, String> cache = ignite.cache(null);

                        assertNotNull(cache);

                        assertEquals("Failed to check entry value on node: " + checkNodeId,
                            !commmit ? initVal : val, cache.peek(key));

                        return null;
                    }
                });
            }
        }

        for (Map.Entry<Integer, String> e : map.entrySet()) {
            for (Ignite g : G.allGrids())
                assertEquals(!commmit ? initVal : e.getValue(), g.cache(null).get(e.getKey()));
        }
    }

    /**
     * @return All node IDs.
     */
    private Collection<UUID> allNodeIds() {
        Collection<UUID> nodeIds = new ArrayList<>(gridCount());

        for (int i = 0; i < gridCount(); i++)
            nodeIds.add(grid(i).localNode().id());

        return nodeIds;
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        cfg.setCommunicationSpi(new TcpCommunicationSpi() {
            @Override public void sendMessage(ClusterNode node, GridTcpCommunicationMessageAdapter msg)
                throws IgniteSpiException {
                if (getSpiContext().localNode().id().equals(failingNodeId)) {
                    if (ignoredMessage((GridIoMessage)msg) && ignoreMsgNodeIds != null) {
                        for (UUID ignored : ignoreMsgNodeIds) {
                            if (node.id().equals(ignored))
                                return;
                        }
                    }
                }

                super.sendMessage(node, msg);
            }
        });

        cfg.getTransactionsConfiguration().setDefaultTxConcurrency(PESSIMISTIC);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected GridCacheConfiguration cacheConfiguration(String gridName) throws Exception {
        GridCacheConfiguration cfg = super.cacheConfiguration(gridName);

        cfg.setStore(null);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected int gridCount() {
        return GRID_CNT;
    }

    /** {@inheritDoc} */
    @Override protected abstract GridCacheMode cacheMode();

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        startGridsMultiThreaded(GRID_CNT);
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        // No-op
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();

        ignoreMsgCls = null;
        ignoreMsgNodeIds = null;
    }

    /**
     * Checks if message should be ignored.
     *
     * @param msg Message.
     * @return {@code True} if message should be ignored.
     */
    private boolean ignoredMessage(GridIoMessage msg) {
        Collection<Class<?>> ignoreClss = ignoreMsgCls;

        if (ignoreClss != null) {
            for (Class<?> ignoreCls : ignoreClss) {
                if (ignoreCls.isAssignableFrom(msg.message().getClass()))
                    return true;
            }

            return false;
        }
        else
            return false;
    }
}
