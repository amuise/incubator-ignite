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

package org.gridgain.grid.kernal.processors.ggfs.split;

import org.apache.ignite.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.fs.*;
import org.apache.ignite.fs.mapreduce.*;
import org.gridgain.grid.cache.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.testframework.junits.common.*;

import java.util.*;

import static org.gridgain.grid.cache.GridCacheAtomicityMode.*;
import static org.gridgain.grid.cache.GridCacheDistributionMode.*;
import static org.gridgain.grid.cache.GridCacheMode.*;
import static org.gridgain.grid.cache.GridCacheWriteSynchronizationMode.*;
import static org.apache.ignite.fs.IgniteFsMode.*;

/**
 * Base class for all split resolvers
 */
public class GridGgfsAbstractRecordResolverSelfTest extends GridCommonAbstractTest {
    /** File path. */
    protected static final IgniteFsPath FILE = new IgniteFsPath("/file");

    /** Shared IP finder. */
    private final TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /** GGFS. */
    protected static IgniteFs ggfs;

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        IgniteFsConfiguration ggfsCfg = new IgniteFsConfiguration();

        ggfsCfg.setDataCacheName("dataCache");
        ggfsCfg.setMetaCacheName("metaCache");
        ggfsCfg.setName("ggfs");
        ggfsCfg.setBlockSize(512);
        ggfsCfg.setDefaultMode(PRIMARY);

        GridCacheConfiguration dataCacheCfg = new GridCacheConfiguration();

        dataCacheCfg.setName("dataCache");
        dataCacheCfg.setCacheMode(PARTITIONED);
        dataCacheCfg.setAtomicityMode(TRANSACTIONAL);
        dataCacheCfg.setDistributionMode(NEAR_PARTITIONED);
        dataCacheCfg.setWriteSynchronizationMode(FULL_SYNC);
        dataCacheCfg.setAffinityMapper(new IgniteFsGroupDataBlocksKeyMapper(128));
        dataCacheCfg.setBackups(0);
        dataCacheCfg.setQueryIndexEnabled(false);

        GridCacheConfiguration metaCacheCfg = new GridCacheConfiguration();

        metaCacheCfg.setName("metaCache");
        metaCacheCfg.setCacheMode(REPLICATED);
        metaCacheCfg.setAtomicityMode(TRANSACTIONAL);
        metaCacheCfg.setWriteSynchronizationMode(FULL_SYNC);
        metaCacheCfg.setQueryIndexEnabled(false);

        IgniteConfiguration cfg = new IgniteConfiguration();

        cfg.setGridName("grid");

        TcpDiscoverySpi discoSpi = new TcpDiscoverySpi();

        discoSpi.setIpFinder(ipFinder);

        cfg.setDiscoverySpi(discoSpi);
        cfg.setCacheConfiguration(dataCacheCfg, metaCacheCfg);
        cfg.setGgfsConfiguration(ggfsCfg);

        Ignite g = G.start(cfg);

        ggfs = g.fileSystem("ggfs");
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        stopAllGrids(false);
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        ggfs.format();
    }

    /**
     * Convenient method for wrapping some bytes into byte array.
     *
     * @param data Data bytes.
     * @return Byte array.
     */
    protected static byte[] wrap(int... data) {
        byte[] res = new byte[data.length];

        for (int i = 0; i < data.length; i++)
            res[i] = (byte)data[i];

        return res;
    }

    /**
     * Create byte array consisting of the given chunks.
     *
     * @param chunks Array of chunks where the first value is the byte array and the second value is amount of repeats.
     * @return Byte array.
     */
    protected static byte[] array(Map.Entry<byte[], Integer>... chunks) {
        int totalSize = 0;

        for (Map.Entry<byte[], Integer> chunk : chunks)
            totalSize += chunk.getKey().length * chunk.getValue();

        byte[] res = new byte[totalSize];

        int pos = 0;

        for (Map.Entry<byte[], Integer> chunk : chunks) {
            for (int i = 0; i < chunk.getValue(); i++) {
                System.arraycopy(chunk.getKey(), 0, res, pos, chunk.getKey().length);

                pos += chunk.getKey().length;
            }
        }

        return res;
    }

    /**
     * Open file for read and return input stream.
     *
     * @return Input stream.
     * @throws Exception In case of exception.
     */
    protected IgniteFsInputStream read() throws Exception {
        return ggfs.open(FILE);
    }

    /**
     * Write data to the file.
     *
     * @param chunks Data chunks.
     * @throws Exception In case of exception.
     */
    protected void write(byte[]... chunks) throws Exception {
        IgniteFsOutputStream os =  ggfs.create(FILE, true);

        if (chunks != null) {
            for (byte[] chunk : chunks)
                os.write(chunk);
        }

        os.close();
    }

    /**
     * Create split.
     *
     * @param start Start position.
     * @param len Length.
     * @return Split.
     */
    protected IgniteFsFileRange split(long start, long len) {
        return new IgniteFsFileRange(FILE, start, len);
    }
}
