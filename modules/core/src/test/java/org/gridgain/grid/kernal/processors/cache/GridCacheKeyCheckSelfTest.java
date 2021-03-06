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

import org.apache.ignite.configuration.*;
import org.gridgain.grid.cache.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;

import static org.gridgain.grid.cache.GridCacheAtomicityMode.*;
import static org.gridgain.grid.cache.GridCacheDistributionMode.*;
import static org.gridgain.grid.cache.GridCacheMode.*;
import static org.gridgain.grid.cache.GridCacheWriteSynchronizationMode.*;

/**
 * Tests for cache key check.
 */
public class GridCacheKeyCheckSelfTest extends GridCacheAbstractSelfTest {
    /** IP finder. */
    private static final TcpDiscoveryIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder(true);

    /** Atomicity mode. */
    private GridCacheAtomicityMode atomicityMode;

    /** {@inheritDoc} */
    @Override protected int gridCount() {
        return 2;
    }

    /** {@inheritDoc} */
    @Override protected GridCacheDistributionMode distributionMode() {
        return PARTITIONED_ONLY;
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        TcpDiscoverySpi discoSpi = new TcpDiscoverySpi();

        discoSpi.setIpFinder(IP_FINDER);

        cfg.setDiscoverySpi(discoSpi);

        cfg.setCacheConfiguration(cacheConfiguration());

        return cfg;
    }

    /**
     * @return Cache configuration.
     */
    protected GridCacheConfiguration cacheConfiguration() {
        GridCacheConfiguration cfg = defaultCacheConfiguration();

        cfg.setCacheMode(PARTITIONED);
        cfg.setBackups(1);
        cfg.setDistributionMode(distributionMode());
        cfg.setWriteSynchronizationMode(FULL_SYNC);
        cfg.setQueryIndexEnabled(false);
        cfg.setAtomicityMode(atomicityMode);

        return cfg;
    }

    /**
     * @throws Exception If failed.
     */
    public void testGetTransactional() throws Exception {
        checkGet(TRANSACTIONAL);
    }

    /**
     * @throws Exception If failed.
     */
    public void testGetAtomic() throws Exception {
        checkGet(ATOMIC);
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutTransactional() throws Exception {
        checkPut(TRANSACTIONAL);
    }

    /**
     * @throws Exception If failed.
     */
    public void testPutAtomic() throws Exception {
        checkPut(ATOMIC);
    }

    /**
     * @throws Exception If failed.
     */
    public void testRemoveTransactional() throws Exception {
        checkRemove(TRANSACTIONAL);
    }

    /**
     * @throws Exception If failed.
     */
    public void testRemoveAtomic() throws Exception {
        checkRemove(ATOMIC);
    }

    /**
     * @throws Exception If failed.
     */
    private void checkGet(GridCacheAtomicityMode atomicityMode) throws Exception {
        this.atomicityMode = atomicityMode;

        try {
            GridCache<IncorrectCacheKey, String> cache = grid(0).cache(null);

            cache.get(new IncorrectCacheKey(0));

            fail("Key without hashCode()/equals() was successfully retrieved from cache.");
        }
        catch (IllegalArgumentException e) {
            info("Catched expected exception: " + e.getMessage());

            assertTrue(e.getMessage().startsWith("Cache key must override hashCode() and equals() methods"));
        }
    }

    /**
     * @throws Exception If failed.
     */
    private void checkPut(GridCacheAtomicityMode atomicityMode) throws Exception {
        this.atomicityMode = atomicityMode;

        try {
            GridCache<IncorrectCacheKey, String> cache = grid(0).cache(null);

            cache.put(new IncorrectCacheKey(0), "test_value");

            fail("Key without hashCode()/equals() was successfully inserted to cache.");
        }
        catch (IllegalArgumentException e) {
            info("Catched expected exception: " + e.getMessage());

            assertTrue(e.getMessage().startsWith("Cache key must override hashCode() and equals() methods"));
        }
    }

    /**
     * @throws Exception If failed.
     */
    private void checkRemove(GridCacheAtomicityMode atomicityMode) throws Exception {
        this.atomicityMode = atomicityMode;

        try {
            GridCache<IncorrectCacheKey, String> cache = grid(0).cache(null);

            cache.remove(new IncorrectCacheKey(0));

            fail("Key without hashCode()/equals() was successfully used for remove operation.");
        }
        catch (IllegalArgumentException e) {
            info("Catched expected exception: " + e.getMessage());

            assertTrue(e.getMessage().startsWith("Cache key must override hashCode() and equals() methods"));
        }
    }

    /**
     * Cache key that doesn't override hashCode()/equals().
     */
    private static final class IncorrectCacheKey {
        /** */
        private int someVal;

        /**
         * @param someVal Some test value.
         */
        private IncorrectCacheKey(int someVal) {
            this.someVal = someVal;
        }

        /**
         * @return Test value.
         */
        public int getSomeVal() {
            return someVal;
        }
    }
}
