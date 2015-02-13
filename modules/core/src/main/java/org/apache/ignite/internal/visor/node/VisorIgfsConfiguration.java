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

package org.apache.ignite.internal.visor.node;

import org.apache.ignite.configuration.*;
import org.apache.ignite.igfs.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;

import static org.apache.ignite.internal.visor.util.VisorTaskUtils.*;

/**
 * Data transfer object for IGFS configuration properties.
 */
public class VisorIgfsConfiguration implements Serializable {
    /** Property name for path to Hadoop configuration. */
    public static final String SECONDARY_FS_CONFIG_PATH = "SECONDARY_FS_CONFIG_PATH";

    /** Property name for URI of file system. */
    public static final String SECONDARY_FS_URI = "SECONDARY_FS_URI";

    /** */
    private static final long serialVersionUID = 0L;

    /** IGFS instance name. */
    private String name;

    /** Cache name to store IGFS meta information. */
    private String metaCacheName;

    /** Cache name to store IGFS data. */
    private String dataCacheName;

    /** File's data block size. */
    private int blockSize;

    /** Number of pre-fetched blocks if specific file's chunk is requested. */
    private int prefetchBlocks;

    /** Read/write buffer size for IGFS stream operations in bytes. */
    private int streamBufferSize;

    /** Number of file blocks buffered on local node before sending batch to remote node. */
    private int perNodeBatchSize;

    /** Number of batches that can be concurrently sent to remote node. */
    private int perNodeParallelBatchCount;

    /** URI of the secondary Hadoop file system. */
    private String secondaryHadoopFileSystemUri;

    /** Path for the secondary hadoop file system config. */
    private String secondaryHadoopFileSystemConfigPath;

    /** IGFS instance mode. */
    private IgfsMode defaultMode;

    /** Map of paths to IGFS modes. */
    private Map<String, IgfsMode> pathModes;

    /** Dual mode PUT operations executor service. */
    private String dualModePutExecutorService;

    /** Dual mode PUT operations executor service shutdown flag. */
    private boolean dualModePutExecutorServiceShutdown;

    /** Maximum amount of data in pending puts. */
    private long dualModeMaxPendingPutsSize;

    /** Maximum range length. */
    private long maxTaskRangeLength;

    /** Fragmentizer concurrent files. */
    private int fragmentizerConcurrentFiles;

    /** Fragmentizer local writes ratio. */
    private float fragmentizerLocWritesRatio;

    /** Fragmentizer enabled flag. */
    private boolean fragmentizerEnabled;

    /** Fragmentizer throttling block length. */
    private long fragmentizerThrottlingBlockLen;

    /** Fragmentizer throttling delay. */
    private long fragmentizerThrottlingDelay;

    /** IPC endpoint config (in JSON format) to publish IGFS over. */
    private String ipcEndpointCfg;

    /** IPC endpoint enabled flag. */
    private boolean ipcEndpointEnabled;

    /** Maximum space. */
    private long maxSpace;

    /** Management port. */
    private int mgmtPort;

    /** Amount of sequential block reads before prefetch is triggered. */
    private int seqReadsBeforePrefetch;

    /** Trash purge await timeout. */
    private long trashPurgeTimeout;

    /**
     * @param igfs IGFS configuration.
     * @return Data transfer object for IGFS configuration properties.
     */
    public static VisorIgfsConfiguration from(IgfsConfiguration igfs) {
        VisorIgfsConfiguration cfg = new VisorIgfsConfiguration();

        cfg.name(igfs.getName());
        cfg.metaCacheName(igfs.getMetaCacheName());
        cfg.dataCacheName(igfs.getDataCacheName());
        cfg.blockSize(igfs.getBlockSize());
        cfg.prefetchBlocks(igfs.getPrefetchBlocks());
        cfg.streamBufferSize(igfs.getStreamBufferSize());
        cfg.perNodeBatchSize(igfs.getPerNodeBatchSize());
        cfg.perNodeParallelBatchCount(igfs.getPerNodeParallelBatchCount());

        Igfs secFs = igfs.getSecondaryFileSystem();

        if (secFs != null) {
            Map<String, String> props = secFs.properties();

            cfg.secondaryHadoopFileSystemUri(props.get(SECONDARY_FS_URI));
            cfg.secondaryHadoopFileSystemConfigPath(props.get(SECONDARY_FS_CONFIG_PATH));
        }

        cfg.defaultMode(igfs.getDefaultMode());
        cfg.pathModes(igfs.getPathModes());
        cfg.dualModePutExecutorService(compactClass(igfs.getDualModePutExecutorService()));
        cfg.dualModePutExecutorServiceShutdown(igfs.getDualModePutExecutorServiceShutdown());
        cfg.dualModeMaxPendingPutsSize(igfs.getDualModeMaxPendingPutsSize());
        cfg.maxTaskRangeLength(igfs.getMaximumTaskRangeLength());
        cfg.fragmentizerConcurrentFiles(igfs.getFragmentizerConcurrentFiles());
        cfg.fragmentizerLocalWritesRatio(igfs.getFragmentizerLocalWritesRatio());
        cfg.fragmentizerEnabled(igfs.isFragmentizerEnabled());
        cfg.fragmentizerThrottlingBlockLength(igfs.getFragmentizerThrottlingBlockLength());
        cfg.fragmentizerThrottlingDelay(igfs.getFragmentizerThrottlingDelay());

        Map<String, String> endpointCfg = igfs.getIpcEndpointConfiguration();
        cfg.ipcEndpointConfiguration(endpointCfg != null ? endpointCfg.toString() : null);

        cfg.ipcEndpointEnabled(igfs.isIpcEndpointEnabled());
        cfg.maxSpace(igfs.getMaxSpaceSize());
        cfg.managementPort(igfs.getManagementPort());
        cfg.sequenceReadsBeforePrefetch(igfs.getSequentialReadsBeforePrefetch());
        cfg.trashPurgeTimeout(igfs.getTrashPurgeTimeout());

        return cfg;
    }

    /**
     * Construct data transfer object for igfs configurations properties.
     *
     * @param igfss Igfs configurations.
     * @return igfs configurations properties.
     */
    public static Iterable<VisorIgfsConfiguration> list(IgfsConfiguration[] igfss) {
        if (igfss == null)
            return Collections.emptyList();

        final Collection<VisorIgfsConfiguration> cfgs = new ArrayList<>(igfss.length);

        for (IgfsConfiguration igfs : igfss)
            cfgs.add(from(igfs));

        return cfgs;
    }

    /**
     * @return IGFS instance name.
     */
    @Nullable public String name() {
        return name;
    }

    /**
     * @param name New IGFS instance name.
     */
    public void name(@Nullable String name) {
        this.name = name;
    }

    /**
     * @return Cache name to store IGFS meta information.
     */
    @Nullable public String metaCacheName() {
        return metaCacheName;
    }

    /**
     * @param metaCacheName New cache name to store IGFS meta information.
     */
    public void metaCacheName(@Nullable String metaCacheName) {
        this.metaCacheName = metaCacheName;
    }

    /**
     * @return Cache name to store IGFS data.
     */
    @Nullable public String dataCacheName() {
        return dataCacheName;
    }

    /**
     * @param dataCacheName New cache name to store IGFS data.
     */
    public void dataCacheName(@Nullable String dataCacheName) {
        this.dataCacheName = dataCacheName;
    }

    /**
     * @return File's data block size.
     */
    public int blockSize() {
        return blockSize;
    }

    /**
     * @param blockSize New file's data block size.
     */
    public void blockSize(int blockSize) {
        this.blockSize = blockSize;
    }

    /**
     * @return Number of pre-fetched blocks if specific file's chunk is requested.
     */
    public int prefetchBlocks() {
        return prefetchBlocks;
    }

    /**
     * @param prefetchBlocks New number of pre-fetched blocks if specific file's chunk is requested.
     */
    public void prefetchBlocks(int prefetchBlocks) {
        this.prefetchBlocks = prefetchBlocks;
    }

    /**
     * @return Read/write buffer size for IGFS stream operations in bytes.
     */
    public int streamBufferSize() {
        return streamBufferSize;
    }

    /**
     * @param streamBufSize New read/write buffer size for IGFS stream operations in bytes.
     */
    public void streamBufferSize(int streamBufSize) {
        streamBufferSize = streamBufSize;
    }

    /**
     * @return Number of file blocks buffered on local node before sending batch to remote node.
     */
    public int perNodeBatchSize() {
        return perNodeBatchSize;
    }

    /**
     * @param perNodeBatchSize New number of file blocks buffered on local node before sending batch to remote node.
     */
    public void perNodeBatchSize(int perNodeBatchSize) {
        this.perNodeBatchSize = perNodeBatchSize;
    }

    /**
     * @return Number of batches that can be concurrently sent to remote node.
     */
    public int perNodeParallelBatchCount() {
        return perNodeParallelBatchCount;
    }

    /**
     * @param perNodeParallelBatchCnt New number of batches that can be concurrently sent to remote node.
     */
    public void perNodeParallelBatchCount(int perNodeParallelBatchCnt) {
        perNodeParallelBatchCount = perNodeParallelBatchCnt;
    }

    /**
     * @return URI of the secondary Hadoop file system.
     */
    @Nullable public String secondaryHadoopFileSystemUri() {
        return secondaryHadoopFileSystemUri;
    }

    /**
     * @param secondaryHadoopFileSysUri New URI of the secondary Hadoop file system.
     */
    public void secondaryHadoopFileSystemUri(@Nullable String secondaryHadoopFileSysUri) {
        secondaryHadoopFileSystemUri = secondaryHadoopFileSysUri;
    }

    /**
     * @return Path for the secondary hadoop file system config.
     */
    @Nullable public String secondaryHadoopFileSystemConfigPath() {
        return secondaryHadoopFileSystemConfigPath;
    }

    /**
     * @param secondaryHadoopFileSysCfgPath New path for the secondary hadoop file system config.
     */
    public void secondaryHadoopFileSystemConfigPath(@Nullable String secondaryHadoopFileSysCfgPath) {
        secondaryHadoopFileSystemConfigPath = secondaryHadoopFileSysCfgPath;
    }

    /**
     * @return IGFS instance mode.
     */
    public IgfsMode defaultMode() {
        return defaultMode;
    }

    /**
     * @param dfltMode New IGFS instance mode.
     */
    public void defaultMode(IgfsMode dfltMode) {
        defaultMode = dfltMode;
    }

    /**
     * @return Map of paths to IGFS modes.
     */
    @Nullable public Map<String, IgfsMode> pathModes() {
        return pathModes;
    }

    /**
     * @param pathModes New map of paths to IGFS modes.
     */
    public void pathModes(@Nullable Map<String, IgfsMode> pathModes) {
        this.pathModes = pathModes;
    }

    /**
     * @return Dual mode PUT operations executor service.
     */
    public String dualModePutExecutorService() {
        return dualModePutExecutorService;
    }

    /**
     * @param dualModePutExecutorSrvc New dual mode PUT operations executor service.
     */
    public void dualModePutExecutorService(String dualModePutExecutorSrvc) {
        dualModePutExecutorService = dualModePutExecutorSrvc;
    }

    /**
     * @return Dual mode PUT operations executor service shutdown flag.
     */
    public boolean dualModePutExecutorServiceShutdown() {
        return dualModePutExecutorServiceShutdown;
    }

    /**
     * @param dualModePutExecutorSrvcShutdown New dual mode PUT operations executor service shutdown flag.
     */
    public void dualModePutExecutorServiceShutdown(boolean dualModePutExecutorSrvcShutdown) {
        dualModePutExecutorServiceShutdown = dualModePutExecutorSrvcShutdown;
    }

    /**
     * @return Maximum amount of data in pending puts.
     */
    public long dualModeMaxPendingPutsSize() {
        return dualModeMaxPendingPutsSize;
    }

    /**
     * @param dualModeMaxPendingPutsSize New maximum amount of data in pending puts.
     */
    public void dualModeMaxPendingPutsSize(long dualModeMaxPendingPutsSize) {
        this.dualModeMaxPendingPutsSize = dualModeMaxPendingPutsSize;
    }

    /**
     * @return Maximum range length.
     */
    public long maxTaskRangeLength() {
        return maxTaskRangeLength;
    }

    /**
     * @param maxTaskRangeLen New maximum range length.
     */
    public void maxTaskRangeLength(long maxTaskRangeLen) {
        maxTaskRangeLength = maxTaskRangeLen;
    }

    /**
     * @return Fragmentizer concurrent files.
     */
    public int fragmentizerConcurrentFiles() {
        return fragmentizerConcurrentFiles;
    }

    /**
     * @param fragmentizerConcurrentFiles New fragmentizer concurrent files.
     */
    public void fragmentizerConcurrentFiles(int fragmentizerConcurrentFiles) {
        this.fragmentizerConcurrentFiles = fragmentizerConcurrentFiles;
    }

    /**
     * @return Fragmentizer local writes ratio.
     */
    public float fragmentizerLocalWritesRatio() {
        return fragmentizerLocWritesRatio;
    }

    /**
     * @param fragmentizerLocWritesRatio New fragmentizer local writes ratio.
     */
    public void fragmentizerLocalWritesRatio(float fragmentizerLocWritesRatio) {
        this.fragmentizerLocWritesRatio = fragmentizerLocWritesRatio;
    }

    /**
     * @return Fragmentizer enabled flag.
     */
    public boolean fragmentizerEnabled() {
        return fragmentizerEnabled;
    }

    /**
     * @param fragmentizerEnabled New fragmentizer enabled flag.
     */
    public void fragmentizerEnabled(boolean fragmentizerEnabled) {
        this.fragmentizerEnabled = fragmentizerEnabled;
    }

    /**
     * @return Fragmentizer throttling block length.
     */
    public long fragmentizerThrottlingBlockLength() {
        return fragmentizerThrottlingBlockLen;
    }

    /**
     * @param fragmentizerThrottlingBlockLen New fragmentizer throttling block length.
     */
    public void fragmentizerThrottlingBlockLength(long fragmentizerThrottlingBlockLen) {
        this.fragmentizerThrottlingBlockLen = fragmentizerThrottlingBlockLen;
    }

    /**
     * @return Fragmentizer throttling delay.
     */
    public long fragmentizerThrottlingDelay() {
        return fragmentizerThrottlingDelay;
    }

    /**
     * @param fragmentizerThrottlingDelay New fragmentizer throttling delay.
     */
    public void fragmentizerThrottlingDelay(long fragmentizerThrottlingDelay) {
        this.fragmentizerThrottlingDelay = fragmentizerThrottlingDelay;
    }

    /**
     * @return IPC endpoint config (in JSON format) to publish IGFS over.
     */
    @Nullable public String ipcEndpointConfiguration() {
        return ipcEndpointCfg;
    }

    /**
     * @param ipcEndpointCfg New IPC endpoint config (in JSON format) to publish IGFS over.
     */
    public void ipcEndpointConfiguration(@Nullable String ipcEndpointCfg) {
        this.ipcEndpointCfg = ipcEndpointCfg;
    }

    /**
     * @return IPC endpoint enabled flag.
     */
    public boolean ipcEndpointEnabled() {
        return ipcEndpointEnabled;
    }

    /**
     * @param ipcEndpointEnabled New iPC endpoint enabled flag.
     */
    public void ipcEndpointEnabled(boolean ipcEndpointEnabled) {
        this.ipcEndpointEnabled = ipcEndpointEnabled;
    }

    /**
     * @return Maximum space.
     */
    public long maxSpace() {
        return maxSpace;
    }

    /**
     * @param maxSpace New maximum space.
     */
    public void maxSpace(long maxSpace) {
        this.maxSpace = maxSpace;
    }

    /**
     * @return Management port.
     */
    public int managementPort() {
        return mgmtPort;
    }

    /**
     * @param mgmtPort New management port.
     */
    public void managementPort(int mgmtPort) {
        this.mgmtPort = mgmtPort;
    }

    /**
     * @return Amount of sequential block reads before prefetch is triggered.
     */
    public int sequenceReadsBeforePrefetch() {
        return seqReadsBeforePrefetch;
    }

    /**
     * @param seqReadsBeforePrefetch New amount of sequential block reads before prefetch is triggered.
     */
    public void sequenceReadsBeforePrefetch(int seqReadsBeforePrefetch) {
        this.seqReadsBeforePrefetch = seqReadsBeforePrefetch;
    }

    /**
     * @return Trash purge await timeout.
     */
    public long trashPurgeTimeout() {
        return trashPurgeTimeout;
    }

    /**
     * @param trashPurgeTimeout New trash purge await timeout.
     */
    public void trashPurgeTimeout(long trashPurgeTimeout) {
        this.trashPurgeTimeout = trashPurgeTimeout;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(VisorIgfsConfiguration.class, this);
    }

}
