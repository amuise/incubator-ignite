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

package org.gridgain.grid.util;

import org.apache.ignite.cluster.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.spi.discovery.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.testframework.*;
import org.gridgain.testframework.junits.common.*;

import java.util.*;

import static org.gridgain.grid.kernal.GridNodeAttributes.*;

/**
 * Tests for calculation logic for topology heap size.
 */
public class GridTopologyHeapSizeSelfTest extends GridCommonAbstractTest {
    /** IP finder. */
    private static final TcpDiscoveryIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder(true);

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        TcpDiscoverySpi disco = new TcpDiscoverySpi();

        disco.setIpFinder(IP_FINDER);

        cfg.setDiscoverySpi(disco);

        return cfg;
    }

    /**
     * @throws Exception If failed.
     */
    public void testTopologyHeapSizeInOneJvm() throws Exception {
        try {
            ClusterNode node1 = startGrid(1).cluster().node();
            ClusterNode node2 = startGrid(2).cluster().node();

            double allSize = U.heapSize(F.asList(node1, node2), 10);

            double size1 = U.heapSize(node1, 10);

            assertEquals(size1, allSize, 1E-5);
        }
        finally {
            stopAllGrids();
        }
    }

    /** */
    public void testTopologyHeapSizeForNodesWithDifferentPids() {
        GridTestNode node1 = getNode("123456789ABC", 1000);
        GridTestNode node2 = getNode("123456789ABC", 1001);

        double size1 = U.heapSize(node1, 10);
        double size2 = U.heapSize(node2, 10);

        double allSize = U.heapSize(F.asList((ClusterNode)node1, node2), 10);

        assertEquals(size1 + size2, allSize, 1E-5);
    }

    /** */
    public void testTopologyHeapSizeForNodesWithDifferentMacs() {
        GridTestNode node1 = getNode("123456789ABC", 1000);
        GridTestNode node2 = getNode("CBA987654321", 1000);

        double size1 = U.heapSize(node1, 10);
        double size2 = U.heapSize(node2, 10);

        double allSize = U.heapSize(F.asList((ClusterNode)node1, node2), 10);

        assertEquals(size1 + size2, allSize, 1E-5);
    }

    /**
     * Creates test node with specified attributes.
     *
     * @param mac Node mac addresses.
     * @param pid Node PID.
     * @return Node.
     */
    private GridTestNode getNode(String mac, int pid) {
        DiscoveryNodeMetricsAdapter metrics = new DiscoveryNodeMetricsAdapter();

        metrics.setHeapMemoryMaximum(1024 * 1024 * 1024);
        metrics.setHeapMemoryInitialized(1024 * 1024 * 1024);

        GridTestNode node = new GridTestNode(UUID.randomUUID(), metrics);

        node.addAttribute(ATTR_MACS, mac);
        node.addAttribute(ATTR_JVM_PID, pid);

        return node;
    }
}
