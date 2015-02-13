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

package org.apache.ignite.internal.processors.igfs;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.eviction.igfs.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.igfs.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.util.lang.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.testframework.*;

import java.util.*;
import java.util.concurrent.*;

import static org.apache.ignite.cache.CacheAtomicityMode.*;
import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.igfs.IgfsMode.*;

/**
 * Tests for IGFS per-block LR eviction policy.
 */
@SuppressWarnings({"ConstantConditions", "ThrowableResultOfMethodCallIgnored"})
public class GridCacheIgfsPerBlockLruEvictionPolicySelfTest extends IgfsCommonAbstractTest {
    /** Primary IGFS name. */
    private static final String IGFS_PRIMARY = "igfs-primary";

    /** Primary IGFS name. */
    private static final String IGFS_SECONDARY = "igfs-secondary";

    /** Secondary file system REST endpoint configuration map. */
    private static final Map<String, String> SECONDARY_REST_CFG = new HashMap<String, String>() {{
        put("type", "tcp");
        put("port", "11500");
    }};

    /** File working in PRIMARY mode. */
    public static final IgfsPath FILE = new IgfsPath("/file");

    /** File working in DUAL mode. */
    public static final IgfsPath FILE_RMT = new IgfsPath("/fileRemote");

    /** Primary IGFS instances. */
    private static IgfsImpl igfsPrimary;

    /** Secondary IGFS instance. */
    private static IgniteFs secondaryFs;

    /** Primary file system data cache. */
    private static GridCacheAdapter<IgfsBlockKey, byte[]> dataCache;

    /** Eviction policy */
    private static CacheIgfsPerBlockLruEvictionPolicy evictPlc;

    /**
     * Start a grid with the primary file system.
     *
     * @throws Exception If failed.
     */
    private void startPrimary() throws Exception {
        IgfsConfiguration igfsCfg = new IgfsConfiguration();

        igfsCfg.setDataCacheName("dataCache");
        igfsCfg.setMetaCacheName("metaCache");
        igfsCfg.setName(IGFS_PRIMARY);
        igfsCfg.setBlockSize(512);
        igfsCfg.setDefaultMode(PRIMARY);
        igfsCfg.setPrefetchBlocks(1);
        igfsCfg.setSequentialReadsBeforePrefetch(Integer.MAX_VALUE);
        igfsCfg.setSecondaryFileSystem(secondaryFs);

        Map<String, IgfsMode> pathModes = new HashMap<>();

        pathModes.put(FILE_RMT.toString(), DUAL_SYNC);

        igfsCfg.setPathModes(pathModes);

        CacheConfiguration dataCacheCfg = defaultCacheConfiguration();

        dataCacheCfg.setName("dataCache");
        dataCacheCfg.setCacheMode(PARTITIONED);
        dataCacheCfg.setDistributionMode(CacheDistributionMode.PARTITIONED_ONLY);
        dataCacheCfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);
        dataCacheCfg.setAtomicityMode(TRANSACTIONAL);

        evictPlc = new CacheIgfsPerBlockLruEvictionPolicy();

        dataCacheCfg.setEvictionPolicy(evictPlc);
        dataCacheCfg.setAffinityMapper(new IgfsGroupDataBlocksKeyMapper(128));
        dataCacheCfg.setBackups(0);
        dataCacheCfg.setQueryIndexEnabled(false);

        CacheConfiguration metaCacheCfg = defaultCacheConfiguration();

        metaCacheCfg.setName("metaCache");
        metaCacheCfg.setCacheMode(REPLICATED);
        metaCacheCfg.setDistributionMode(CacheDistributionMode.PARTITIONED_ONLY);
        metaCacheCfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);
        metaCacheCfg.setQueryIndexEnabled(false);
        metaCacheCfg.setAtomicityMode(TRANSACTIONAL);

        IgniteConfiguration cfg = new IgniteConfiguration();

        cfg.setGridName("grid-primary");

        TcpDiscoverySpi discoSpi = new TcpDiscoverySpi();

        discoSpi.setIpFinder(new TcpDiscoveryVmIpFinder(true));

        cfg.setDiscoverySpi(discoSpi);
        cfg.setCacheConfiguration(dataCacheCfg, metaCacheCfg);
        cfg.setIgfsConfiguration(igfsCfg);

        cfg.setLocalHost("127.0.0.1");
        cfg.setConnectorConfiguration(null);

        Ignite g = G.start(cfg);

        igfsPrimary = (IgfsImpl)g.fileSystem(IGFS_PRIMARY);

        dataCache = igfsPrimary.context().kernalContext().cache().internalCache(
            igfsPrimary.context().configuration().getDataCacheName());
    }

    /**
     * Start a grid with the secondary file system.
     *
     * @throws Exception If failed.
     */
    private void startSecondary() throws Exception {
        IgfsConfiguration igfsCfg = new IgfsConfiguration();

        igfsCfg.setDataCacheName("dataCache");
        igfsCfg.setMetaCacheName("metaCache");
        igfsCfg.setName(IGFS_SECONDARY);
        igfsCfg.setBlockSize(512);
        igfsCfg.setDefaultMode(PRIMARY);
        igfsCfg.setIpcEndpointConfiguration(SECONDARY_REST_CFG);

        CacheConfiguration dataCacheCfg = defaultCacheConfiguration();

        dataCacheCfg.setName("dataCache");
        dataCacheCfg.setCacheMode(PARTITIONED);
        dataCacheCfg.setDistributionMode(CacheDistributionMode.PARTITIONED_ONLY);
        dataCacheCfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);
        dataCacheCfg.setAffinityMapper(new IgfsGroupDataBlocksKeyMapper(128));
        dataCacheCfg.setBackups(0);
        dataCacheCfg.setQueryIndexEnabled(false);
        dataCacheCfg.setAtomicityMode(TRANSACTIONAL);

        CacheConfiguration metaCacheCfg = defaultCacheConfiguration();

        metaCacheCfg.setName("metaCache");
        metaCacheCfg.setCacheMode(REPLICATED);
        metaCacheCfg.setDistributionMode(CacheDistributionMode.PARTITIONED_ONLY);
        metaCacheCfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);
        metaCacheCfg.setQueryIndexEnabled(false);
        metaCacheCfg.setAtomicityMode(TRANSACTIONAL);

        IgniteConfiguration cfg = new IgniteConfiguration();

        cfg.setGridName("grid-secondary");

        TcpDiscoverySpi discoSpi = new TcpDiscoverySpi();

        discoSpi.setIpFinder(new TcpDiscoveryVmIpFinder(true));

        cfg.setDiscoverySpi(discoSpi);
        cfg.setCacheConfiguration(dataCacheCfg, metaCacheCfg);
        cfg.setIgfsConfiguration(igfsCfg);

        cfg.setLocalHost("127.0.0.1");
        cfg.setConnectorConfiguration(null);

        Ignite g = G.start(cfg);

        secondaryFs = g.fileSystem(IGFS_SECONDARY);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        try {
            // Cleanup.
            igfsPrimary.format();

            while (!dataCache.isEmpty())
                U.sleep(100);

            checkEvictionPolicy(0, 0);
        }
        finally {
            stopAllGrids(false);
        }
    }

    /**
     * Startup primary and secondary file systems.
     *
     * @throws Exception If failed.
     */
    private void start() throws Exception {
        startSecondary();
        startPrimary();

        evictPlc.setMaxBlocks(0);
        evictPlc.setMaxSize(0);
        evictPlc.setExcludePaths(null);
    }

    /**
     * Test how evictions are handled for a file working in PRIMARY mode.
     *
     * @throws Exception If failed.
     */
    public void testFilePrimary() throws Exception {
        start();

        // Create file in primary mode. It must not be propagated to eviction policy.
        igfsPrimary.create(FILE, true).close();

        checkEvictionPolicy(0, 0);

        int blockSize = igfsPrimary.info(FILE).blockSize();

        append(FILE, blockSize);

        checkEvictionPolicy(0, 0);

        read(FILE, 0, blockSize);

        checkEvictionPolicy(0, 0);
    }

    /**
     * Test how evictions are handled for a file working in PRIMARY mode.
     *
     * @throws Exception If failed.
     */
    public void testFileDual() throws Exception {
        start();

        igfsPrimary.create(FILE_RMT, true).close();

        checkEvictionPolicy(0, 0);

        int blockSize = igfsPrimary.info(FILE_RMT).blockSize();

        // File write.
        append(FILE_RMT, blockSize);

        checkEvictionPolicy(1, blockSize);

        // One more write.
        append(FILE_RMT, blockSize);

        checkEvictionPolicy(2, blockSize * 2);

        // Read.
        read(FILE_RMT, 0, blockSize);

        checkEvictionPolicy(2, blockSize * 2);
    }

    /**
     * Ensure that a DUAL mode file is not propagated to eviction policy
     *
     * @throws Exception If failed.
     */
    public void testFileDualExclusion() throws Exception {
        start();

        evictPlc.setExcludePaths(Collections.singleton(FILE_RMT.toString()));

        // Create file in primary mode. It must not be propagated to eviction policy.
        igfsPrimary.create(FILE_RMT, true).close();

        checkEvictionPolicy(0, 0);

        int blockSize = igfsPrimary.info(FILE_RMT).blockSize();

        append(FILE_RMT, blockSize);

        checkEvictionPolicy(0, 0);

        read(FILE_RMT, 0, blockSize);

        checkEvictionPolicy(0, 0);
    }

    /**
     * Ensure that exception is thrown in case we are trying to rename file with one exclude setting to the file with
     * another.
     *
     * @throws Exception If failed.
     */
    public void testRenameDifferentExcludeSettings() throws Exception {
        start();

        GridTestUtils.assertThrows(log, new Callable<Object>() {
            @Override public Object call() throws Exception {
                igfsPrimary.rename(FILE, FILE_RMT);

                return null;
            }
        }, IgfsInvalidPathException.class, "Cannot move file to a path with different eviction exclude setting " +
            "(need to copy and remove)");

        GridTestUtils.assertThrows(log, new Callable<Object>() {
            @Override public Object call() throws Exception {
                igfsPrimary.rename(FILE_RMT, FILE);

                return null;
            }
        }, IgfsInvalidPathException.class, "Cannot move file to a path with different eviction exclude setting " +
            "(need to copy and remove)");
    }

    /**
     * Test eviction caused by too much blocks.
     *
     * @throws Exception If failed.
     */
    public void testBlockCountEviction() throws Exception {
        start();

        int blockCnt = 3;

        evictPlc.setMaxBlocks(blockCnt);

        igfsPrimary.create(FILE_RMT, true).close();

        checkEvictionPolicy(0, 0);

        int blockSize = igfsPrimary.info(FILE_RMT).blockSize();

        // Write blocks up to the limit.
        append(FILE_RMT, blockSize * blockCnt);

        checkEvictionPolicy(blockCnt, blockCnt * blockSize);

        // Write one more block what should cause eviction.
        append(FILE_RMT, blockSize);

        checkEvictionPolicy(blockCnt, blockCnt * blockSize);

        // Read the first block.
        read(FILE_RMT, 0, blockSize);

        checkEvictionPolicy(blockCnt, blockCnt * blockSize);
        checkMetrics(1, 1);
    }

    /**
     * Test eviction caused by too big data size.
     *
     * @throws Exception If failed.
     */
    public void testDataSizeEviction() throws Exception {
        start();

        igfsPrimary.create(FILE_RMT, true).close();

        int blockCnt = 3;
        int blockSize = igfsPrimary.info(FILE_RMT).blockSize();

        evictPlc.setMaxSize(blockSize * blockCnt);

        // Write blocks up to the limit.
        append(FILE_RMT, blockSize * blockCnt);

        checkEvictionPolicy(blockCnt, blockCnt * blockSize);

        // Reset metrics.
        igfsPrimary.resetMetrics();

        // Read the first block what should cause reordering.
        read(FILE_RMT, 0, blockSize);

        checkMetrics(1, 0);
        checkEvictionPolicy(blockCnt, blockCnt * blockSize);

        // Write one more block what should cause eviction of the block 2.
        append(FILE_RMT, blockSize);

        checkEvictionPolicy(blockCnt, blockCnt * blockSize);

        // Read the first block.
        read(FILE_RMT, 0, blockSize);

        checkMetrics(2, 0);
        checkEvictionPolicy(blockCnt, blockCnt * blockSize);

        // Read the second block (which was evicted).
        read(FILE_RMT, blockSize, blockSize);

        checkMetrics(3, 1);
        checkEvictionPolicy(blockCnt, blockCnt * blockSize);
    }

    /**
     * Read some data from the given file with the given offset.
     *
     * @param path File path.
     * @param off Offset.
     * @param len Length.
     * @throws Exception If failed.
     */
    private void read(IgfsPath path, int off, int len) throws Exception {
        IgfsInputStream is = igfsPrimary.open(path);

        is.readFully(off, new byte[len]);

        is.close();
    }

    /**
     * Append some data to the given file.
     *
     * @param path File path.
     * @param len Data length.
     * @throws Exception If failed.
     */
    private void append(IgfsPath path, int len) throws Exception {
        IgfsOutputStream os = igfsPrimary.append(path, false);

        os.write(new byte[len]);

        os.close();
    }

    /**
     * Check metrics counters.
     *
     * @param blocksRead Expected blocks read.
     * @param blocksReadRmt Expected blocks read remote.
     * @throws Exception If failed.
     */
    public void checkMetrics(final long blocksRead, final long blocksReadRmt) throws Exception {
        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                IgfsMetrics metrics = igfsPrimary.metrics();

                return metrics.blocksReadTotal() == blocksRead && metrics.blocksReadRemote() == blocksReadRmt;
            }
        }, 5000) : "Unexpected metrics [expectedBlocksReadTotal=" + blocksRead + ", actualBlocksReadTotal=" +
            igfsPrimary.metrics().blocksReadTotal() + ", expectedBlocksReadRemote=" + blocksReadRmt +
            ", actualBlocksReadRemote=" + igfsPrimary.metrics().blocksReadRemote() + ']';
    }

    /**
     * Check eviction policy state.
     *
     * @param curBlocks Current blocks.
     * @param curBytes Current bytes.
     */
    private void checkEvictionPolicy(final int curBlocks, final long curBytes) throws IgniteInterruptedCheckedException {
        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return evictPlc.getCurrentBlocks() == curBlocks && evictPlc.getCurrentSize() == curBytes;
            }
        }, 5000) : "Unexpected counts [expectedBlocks=" + curBlocks + ", actualBlocks=" + evictPlc.getCurrentBlocks() +
            ", expectedBytes=" + curBytes + ", currentBytes=" + curBytes + ']';
    }
}
