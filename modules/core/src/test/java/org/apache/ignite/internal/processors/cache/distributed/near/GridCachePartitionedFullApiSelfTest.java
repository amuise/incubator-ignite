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
import org.apache.ignite.cache.affinity.consistenthash.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.util.typedef.*;

import javax.cache.*;
import java.util.*;

import static org.apache.ignite.cache.CacheMode.*;

/**
 * Tests for partitioned cache.
 */
public class GridCachePartitionedFullApiSelfTest extends GridCacheAbstractFullApiSelfTest {
    /** {@inheritDoc} */
    @Override protected CacheMode cacheMode() {
        return PARTITIONED;
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        cfg.getTransactionConfiguration().setTxSerializableEnabled(true);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected CacheConfiguration cacheConfiguration(String gridName) throws Exception {
        CacheConfiguration cfg = super.cacheConfiguration(gridName);

        cfg.setEvictNearSynchronized(false);
        cfg.setEvictSynchronized(false);

        cfg.setAtomicityMode(atomicityMode());
        cfg.setSwapEnabled(true);

        return cfg;
    }

    /**
     * @throws Exception If failed.
     */
    public void testPartitionEntrySetToString() throws Exception {
        GridCacheAdapter<String, Integer> cache = ((IgniteKernal)grid(0)).internalCache();

        for (int i = 0; i < 100; i++) {
            String key = String.valueOf(i);

            cache.put(key, i);
        }

        CacheConsistentHashAffinityFunction aff = (CacheConsistentHashAffinityFunction)cache.configuration().getAffinity();

        for (int i = 0 ; i < aff.getPartitions(); i++)
            String.valueOf(cache.entrySet(i));
    }
}
