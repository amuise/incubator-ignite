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

package org.gridgain.grid.kernal.processors.cache.datastructures;

import org.apache.ignite.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.resources.*;
import org.gridgain.grid.*;
import org.gridgain.grid.cache.datastructures.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.testframework.junits.common.*;

import java.io.*;
import java.util.*;

/**
 * Sequence multi node tests.
 */
public abstract class GridCacheSequenceMultiNodeAbstractSelfTest extends GridCommonAbstractTest
    implements Externalizable {
    /** */
    protected static final int GRID_CNT = 4;

    /**  */
    protected static final int BATCH_SIZE = 33;

    /** */
    protected static TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /** */
    protected static final int RETRIES = 1111;

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        startGridsMultiThreaded(GRID_CNT);
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        stopAllGrids();
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        TcpDiscoverySpi spi = new TcpDiscoverySpi();

        spi.setIpFinder(ipFinder);

        cfg.setDiscoverySpi(spi);

        cfg.setLocalHost("127.0.0.1");

        return cfg;
    }

    /**
     * JUnit.
     *
     * @throws Exception If failed.
     */
    public void testIncrementAndGet() throws Exception {
        Collection<Long> res = new HashSet<>();

        String seqName = UUID.randomUUID().toString();

        for (int i = 0; i < GRID_CNT; i++) {
            Set<Long> retVal = compute(grid(i).forLocal()).
                call(new IncrementAndGetJob(seqName, RETRIES));

            for (Long l : retVal)
                assert !res.contains(l) : "Value already was used " + l;

            res.addAll(retVal);
        }

        assert res.size() == GRID_CNT * RETRIES;

        int gapSize = 0;

        for (long i = 1; i <= GRID_CNT * RETRIES; i++) {
            if (!res.contains(i))
                gapSize++;
            else
                gapSize = 0;

            assert gapSize <= BATCH_SIZE :
                "Gap above id  " + i + " is " + gapSize + "more than batch size: " + BATCH_SIZE;
        }
    }

    /**
     * JUnit.
     *
     * @throws Exception If failed.
     */
    public void testGetAndIncrement() throws Exception {
        Collection<Long> res = new HashSet<>();

        String seqName = UUID.randomUUID().toString();

        for (int i = 0; i < GRID_CNT; i++) {
            Set<Long> retVal = compute(grid(i).forLocal()).
                call(new GetAndIncrementJob(seqName, RETRIES));

            for (Long l : retVal)
                assert !res.contains(l) : "Value already was used " + l;

            res.addAll(retVal);
        }

        assert res.size() == GRID_CNT * RETRIES;

        int gapSize = 0;

        for (long i = 0; i < GRID_CNT * RETRIES; i++) {
            if (!res.contains(i))
                gapSize++;
            else
                gapSize = 0;

            assert gapSize <= BATCH_SIZE + 1 :
                "Gap above id  " + i + " is " + gapSize + " more than batch size: " + (BATCH_SIZE + 1);
        }
    }

    /**
     * JUnit.
     *
     * @throws Exception If failed.
     */
    public void testMarshalling() throws Exception {
        String seqName = UUID.randomUUID().toString();

        final GridCacheAtomicSequence seq = grid(0).cache(null).dataStructures().atomicSequence(seqName, 0, true);

        grid(1).compute().run(new CAX() {
            @Override public void applyx() throws IgniteCheckedException {
                assertNotNull(seq);

                for (int i = 0; i < RETRIES; i++)
                    seq.incrementAndGet();
            }
        });
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        // No-op.
    }

    /**
     * Test job with method cache name annotation.
     */
    private static class IncrementAndGetJob implements IgniteCallable<Set<Long>> {
        /** */
        @IgniteInstanceResource
        private Ignite ignite;

        /** */
        @IgniteLoggerResource
        private IgniteLogger log;

        /* Sequence name. */
        private final String seqName;

        /* */
        private final int retries;

        /**
         * @param seqName Sequence name.
         * @param retries  Number of operations.
         */
        IncrementAndGetJob(String seqName, int retries) {
            this.seqName = seqName;
            this.retries = retries;
        }

        /** {@inheritDoc} */
        @Override public Set<Long> call() throws IgniteCheckedException {
            assert ignite != null;

            if (log.isInfoEnabled())
                log.info("Running IncrementAndGetJob on node: " + ignite.cluster().localNode().id());

            GridCacheAtomicSequence seq = ignite.cache(null).dataStructures().atomicSequence(seqName, 0, true);

            assert seq != null;

            // Result set.
            Set<Long> resSet = new HashSet<>();

            // Get sequence value and try to put it result set.
            for (int i = 0; i < retries; i++) {
                long val = seq.incrementAndGet();

                assert !resSet.contains(val) : "Element already in set : " + val;

                resSet.add(val);
            }

            return resSet;
        }
    }

    /**
     * Test job with method cache name annotation.
     */
    private static class GetAndIncrementJob implements IgniteCallable<Set<Long>> {
        /** */
        @IgniteInstanceResource
        private Ignite ignite;

        /** */
        @IgniteLoggerResource
        private IgniteLogger log;

        /** Sequence name. */
        private final String seqName;

        /** */
        private final int retries;

        /**
         * @param seqName Sequence name.
         * @param retries  Number of operations.
         */
        GetAndIncrementJob(String seqName, int retries) {
            this.seqName = seqName;
            this.retries = retries;
        }

        /** {@inheritDoc} */
        @Override public Set<Long> call() throws IgniteCheckedException {
            assert ignite != null;

            if (log.isInfoEnabled())
                log.info("Running GetAndIncrementJob on node: " + ignite.cluster().localNode().id());

            GridCacheAtomicSequence seq = ignite.cache(null).dataStructures().atomicSequence(seqName, 0, true);

            assert seq != null;

            // Result set.
            Set<Long> resSet = new HashSet<>();

            // Get sequence value and try to put it result set.
            for (int i = 0; i < retries; i++) {
                long val = seq.getAndIncrement();

                assert !resSet.contains(val) : "Element already in set : " + val;

                resSet.add(val);
            }

            return resSet;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(GetAndIncrementJob.class, this);
        }
    }
}
