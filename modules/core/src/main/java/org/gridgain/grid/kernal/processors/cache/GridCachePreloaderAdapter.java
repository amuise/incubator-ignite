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

import org.apache.ignite.*;
import org.apache.ignite.lang.*;
import org.gridgain.grid.*;
import org.gridgain.grid.cache.affinity.*;
import org.gridgain.grid.kernal.processors.cache.distributed.dht.preloader.*;
import org.gridgain.grid.util.future.*;
import org.jetbrains.annotations.*;

import java.util.*;

/**
 * Adapter for preloading which always assumes that preloading finished.
 */
public class GridCachePreloaderAdapter<K, V> implements GridCachePreloader<K, V> {
    /** Cache context. */
    protected final GridCacheContext<K, V> cctx;

    /** Logger.*/
    protected final IgniteLogger log;

    /** Affinity. */
    protected final GridCacheAffinityFunction aff;

    /** Start future (always completed by default). */
    private final IgniteFuture finFut;

    /** Preload predicate. */
    protected IgnitePredicate<GridCacheEntryInfo<K, V>> preloadPred;

    /**
     * @param cctx Cache context.
     */
    public GridCachePreloaderAdapter(GridCacheContext<K, V> cctx) {
        assert cctx != null;

        this.cctx = cctx;

        log = cctx.logger(getClass());
        aff = cctx.config().getAffinity();

        finFut = new GridFinishedFuture(cctx.kernalContext());
    }

    /** {@inheritDoc} */
    @Override public void start() throws IgniteCheckedException {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void stop() {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void onKernalStart() throws IgniteCheckedException {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void onKernalStop() {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void forcePreload() {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void preloadPredicate(IgnitePredicate<GridCacheEntryInfo<K, V>> preloadPred) {
        this.preloadPred = preloadPred;
    }

    /** {@inheritDoc} */
    @Override public IgnitePredicate<GridCacheEntryInfo<K, V>> preloadPredicate() {
        return preloadPred;
    }

    /** {@inheritDoc} */
    @Override public IgniteFuture<Object> startFuture() {
        return finFut;
    }

    /** {@inheritDoc} */
    @Override public IgniteFuture<?> syncFuture() {
        return finFut;
    }

    /** {@inheritDoc} */
    @Override public void unwindUndeploys() {
        cctx.deploy().unwind();
    }

    /** {@inheritDoc} */
    @Override public IgniteFuture<Object> request(Collection<? extends K> keys, long topVer) {
        return new GridFinishedFuture<>(cctx.kernalContext());
    }

    /** {@inheritDoc} */
    @Override public void onInitialExchangeComplete(@Nullable Throwable err) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void onExchangeFutureAdded() {
        // No-op.
    }

    @Override public void updateLastExchangeFuture(GridDhtPartitionsExchangeFuture<K, V> lastFut) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public GridDhtPreloaderAssignments<K, V> assign(GridDhtPartitionsExchangeFuture<K, V> exchFut) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public void addAssignments(GridDhtPreloaderAssignments<K, V> assignments, boolean forcePreload) {
        // No-op.
    }
}
