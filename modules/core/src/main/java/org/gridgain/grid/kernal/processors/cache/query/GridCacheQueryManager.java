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

package org.gridgain.grid.kernal.processors.cache.query;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.events.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.resources.*;
import org.apache.ignite.spi.*;
import org.apache.ignite.spi.indexing.*;
import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.query.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.managers.eventstorage.*;
import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.kernal.processors.cache.datastructures.*;
import org.gridgain.grid.kernal.processors.cache.distributed.dht.*;
import org.gridgain.grid.kernal.processors.query.*;
import org.gridgain.grid.kernal.processors.task.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.io.*;
import org.gridgain.grid.util.lang.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jdk8.backport.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

import static org.gridgain.grid.cache.GridCacheMode.*;
import static org.apache.ignite.events.IgniteEventType.*;
import static org.gridgain.grid.kernal.GridClosureCallMode.*;
import static org.gridgain.grid.kernal.processors.cache.query.GridCacheQueryType.*;

/**
 * Query and index manager.
 */
@SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized")
public abstract class GridCacheQueryManager<K, V> extends GridCacheManagerAdapter<K, V> {
    /** */
    protected GridQueryProcessor idxProc;

    /** */
    private String space;

    /** */
    private int maxIterCnt;

    /** */
    private volatile GridCacheQueryMetricsAdapter metrics = new GridCacheQueryMetricsAdapter();

    /** */
    private final ConcurrentMap<UUID, Map<Long, GridFutureAdapter<QueryResult<K, V>>>> qryIters =
        new ConcurrentHashMap8<>();

    /** */
    private final ConcurrentMap<UUID, Map<Long, GridFutureAdapter<FieldsResult>>> fieldsQryRes =
        new ConcurrentHashMap8<>();

    /** */
    private volatile ConcurrentMap<Object, CachedResult<?>> qryResCache = new ConcurrentHashMap8<>();

    /** */
    private final GridSpinBusyLock busyLock = new GridSpinBusyLock();

    /** {@inheritDoc} */
    @Override public void start0() throws IgniteCheckedException {
        idxProc = cctx.kernalContext().query();
        space = cctx.name();
        maxIterCnt = cctx.config().getMaximumQueryIteratorCount();

        cctx.events().addListener(new GridLocalEventListener() {
            @Override public void onEvent(IgniteEvent evt) {
                UUID nodeId = ((IgniteDiscoveryEvent)evt).eventNode().id();

                Map<Long, GridFutureAdapter<QueryResult<K, V>>> futs = qryIters.remove(nodeId);

                if (futs != null) {
                    for (Map.Entry<Long, GridFutureAdapter<QueryResult<K, V>>> entry : futs.entrySet()) {
                        final Object recipient = recipient(nodeId, entry.getKey());

                        entry.getValue().listenAsync(new CIX1<IgniteFuture<QueryResult<K, V>>>() {
                            @Override public void applyx(IgniteFuture<QueryResult<K, V>> f) throws IgniteCheckedException {
                                f.get().closeIfNotShared(recipient);
                            }
                        });
                    }
                }

                Map<Long, GridFutureAdapter<FieldsResult>> fieldsFuts = fieldsQryRes.remove(nodeId);

                if (fieldsFuts != null) {
                    for (Map.Entry<Long, GridFutureAdapter<FieldsResult>> entry : fieldsFuts.entrySet()) {
                        final Object recipient = recipient(nodeId, entry.getKey());

                        entry.getValue().listenAsync(new CIX1<IgniteFuture<FieldsResult>>() {
                            @Override public void applyx(IgniteFuture<FieldsResult> f)
                                throws IgniteCheckedException {
                                f.get().closeIfNotShared(recipient);
                            }
                        });
                    }
                }
            }
        }, EVT_NODE_LEFT, EVT_NODE_FAILED);
    }

    /** {@inheritDoc} */
    @Override protected void onKernalStop0(boolean cancel) {
        busyLock.block();

        if (cancel)
            onCancelAtStop();
        else
            onWaitAtStop();
    }

    /**
     * @return {@code True} if entered busy state.
     */
    private boolean enterBusy() {
        return busyLock.enterBusy();
    }

    /**
     *  Leaves busy state.
     */
    private void leaveBusy() {
        busyLock.leaveBusy();
    }

    /**
     * Stops query manager.
     *
     * @param cancel Cancel queries.
     */
    @SuppressWarnings({"LockAcquiredButNotSafelyReleased"})
    @Override public final void stop0(boolean cancel) {
        if (log.isDebugEnabled())
            log.debug("Stopped cache query manager.");
    }

    /**
     * Gets number of objects of given type in index.
     *
     * @param valType Value type.
     * @return Number of objects or -1 if type was not indexed at all.
     * @throws IgniteCheckedException If failed.
     */
    public long size(Class<?> valType) throws IgniteCheckedException {
        if (!enterBusy())
            throw new IllegalStateException("Failed to get size (grid is stopping).");

        try {
            return idxProc.size(space, valType);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * Rebuilds all search indexes of given value type.
     *
     * @param valType Value type.
     * @return Future that will be completed when rebuilding of all indexes is finished.
     */
    public IgniteFuture<?> rebuildIndexes(Class<?> valType) {
        return rebuildIndexes(valType.getName());
    }

    /**
     * Rebuilds all search indexes of given value type.
     *
     * @param typeName Value type name.
     * @return Future that will be completed when rebuilding of all indexes is finished.
     */
    public IgniteFuture<?> rebuildIndexes(String typeName) {
        if (!enterBusy())
            throw new IllegalStateException("Failed to rebuild indexes (grid is stopping).");

        try {
            return idxProc.rebuildIndexes(space, typeName);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * Rebuilds all search indexes of all types.
     *
     * @return Future that will be completed when rebuilding of all indexes is finished.
     */
    public IgniteFuture<?> rebuildAllIndexes() {
        if (!enterBusy())
            throw new IllegalStateException("Failed to rebuild indexes (grid is stopping).");

        try {
            return idxProc.rebuildAllIndexes();
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * Marks this request as canceled.
     *
     * @param reqId Request id.
     */
    void onQueryFutureCanceled(long reqId) {
        // No-op.
    }

    /**
     * Cancel flag handler at stop.
     */
    void onCancelAtStop() {
        // No-op.
    }

    /**
     * Wait flag handler at stop.
     */
    void onWaitAtStop() {
        // No-op.
    }

    /**
     * Processes cache query request.
     *
     * @param sndId Sender node id.
     * @param req Query request.
     */
    void processQueryRequest(UUID sndId, GridCacheQueryRequest req) {
        // No-op.
    }

    /**
     * Entry for given key unswapped.
     *
     * @param swapSpaceName Swap space name.
     * @param key Key.
     * @throws IgniteCheckedException If failed.
     */
    public void onSwap(String swapSpaceName, K key) throws IgniteCheckedException {
        if (!enterBusy())
            return; // Ignore index update when node is stopping.

        try {
            idxProc.onSwap(space, key);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * Entry for given key unswapped.
     *
     * @param key Key.
     * @param val Value
     * @param valBytes Value bytes.
     * @throws IgniteCheckedException If failed.
     */
    public void onUnswap(K key, V val, byte[] valBytes) throws IgniteCheckedException {
        if (!enterBusy())
            return; // Ignore index update when node is stopping.

        try {
            idxProc.onUnswap(space, key, val, valBytes);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     *
     */
    private void invalidateResultCache() {
        if (!qryResCache.isEmpty())
            qryResCache = new ConcurrentHashMap8<>();
    }

    /**
     * Writes key-value pair to index.
     *
     * @param key Key.
     * @param keyBytes Byte array with key data.
     * @param val Value.
     * @param valBytes Value bytes.
     * @param ver Cache entry version.
     * @param expirationTime Expiration time or 0 if never expires.
     * @throws IgniteCheckedException In case of error.
     */
    public void store(K key, @Nullable byte[] keyBytes, @Nullable V val, @Nullable byte[] valBytes,
        GridCacheVersion ver, long expirationTime)
        throws IgniteCheckedException {
        assert key != null;
        assert val != null || valBytes != null;

        if (!cctx.config().isQueryIndexEnabled() && !(key instanceof GridCacheInternal))
            return; // No-op.

        if (!enterBusy())
            return; // Ignore index update when node is stopping.

        try {
            if (val == null)
                val = cctx.marshaller().unmarshal(valBytes, cctx.deploy().globalLoader());

            idxProc.store(space, key, keyBytes, val, valBytes, CU.versionToBytes(ver), expirationTime);
        }
        finally {
            invalidateResultCache();

            leaveBusy();
        }
    }

    /**
     * @param key Key.
     * @param keyBytes Byte array with key value.
     * @throws IgniteCheckedException Thrown in case of any errors.
     */
    @SuppressWarnings("SimplifiableIfStatement")
    public void remove(K key, @Nullable byte[] keyBytes) throws IgniteCheckedException {
        assert key != null;

        if (!cctx.config().isQueryIndexEnabled() && !(key instanceof GridCacheInternal))
            return; // No-op.

        if (!enterBusy())
            return; // Ignore index update when node is stopping.

        try {
            idxProc.remove(space, key);
        }
        finally {
            invalidateResultCache();

            leaveBusy();
        }
    }

    /**
     * Undeploys given class loader.
     *
     * @param ldr Class loader to undeploy.
     */
    public void onUndeploy(ClassLoader ldr) {
        if (!enterBusy())
            return; // Ignore index update when node is stopping.

        try {
            idxProc.onUndeploy(space, ldr);
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException(e);
        }
        finally {
            invalidateResultCache();

            leaveBusy();
        }
    }

    /**
     * Executes local query.
     *
     * @param qry Query.
     * @return Query future.
     */
    public abstract GridCacheQueryFuture<?> queryLocal(GridCacheQueryBean qry);

    /**
     * Executes distributed query.
     *
     * @param qry Query.
     * @param nodes Nodes.
     * @return Query future.
     */
    public abstract GridCacheQueryFuture<?> queryDistributed(GridCacheQueryBean qry, Collection<ClusterNode> nodes);

    /**
     * Loads page.
     *
     * @param id Query ID.
     * @param qry Query.
     * @param nodes Nodes.
     * @param all Whether to load all pages.
     */
    public abstract void loadPage(long id, GridCacheQueryAdapter<?> qry, Collection<ClusterNode> nodes, boolean all);

    /**
     * Executes distributed fields query.
     *
     * @param qry Query.
     * @return Query future.
     */
    public abstract GridCacheQueryFuture<?> queryFieldsLocal(GridCacheQueryBean qry);

    /**
     * Executes distributed fields query.
     *
     * @param qry Query.
     * @param nodes Nodes.
     * @return Query future.
     */
    public abstract GridCacheQueryFuture<?> queryFieldsDistributed(GridCacheQueryBean qry, Collection<ClusterNode> nodes);

    /**
     * Performs query.
     *
     * @param qry Query.
     * @param args Arguments.
     * @param loc Local query or not.
     * @param subjId Security subject ID.
     * @param taskName Task name.
     * @param recipient ID of the recipient.
     * @return Collection of found keys.
     * @throws IgniteCheckedException In case of error.
     */
    private QueryResult<K, V> executeQuery(GridCacheQueryAdapter<?> qry,
        @Nullable Object[] args, boolean loc, @Nullable UUID subjId, @Nullable String taskName, Object recipient)
        throws IgniteCheckedException {
        if (qry.type() == null) {
            assert !loc;

            throw new IgniteCheckedException("Received next page request after iterator was removed. " +
                "Consider increasing maximum number of stored iterators (see " +
                "GridCacheConfiguration.getMaximumQueryIteratorCount() configuration property).");
        }

        QueryResult<K, V> res;

        T3<String, String, List<Object>> resKey = null;

        if (qry.type() == SQL) {
            resKey = new T3<>(qry.queryClassName(), qry.clause(), F.asList(args));

            res = (QueryResult<K, V>)qryResCache.get(resKey);

            if (res != null && res.addRecipient(recipient))
                return res;

            res = new QueryResult<>(qry.type(), recipient);

            if (qryResCache.putIfAbsent(resKey, res) != null)
                resKey = null;
        }
        else
            res = new QueryResult<>(qry.type(), recipient);

        GridCloseableIterator<IgniteBiTuple<K, V>> iter;

        try {
            switch (qry.type()) {
                case SQL:
                    if (cctx.gridEvents().isRecordable(EVT_CACHE_QUERY_EXECUTED)) {
                        cctx.gridEvents().record(new IgniteCacheQueryExecutedEvent<>(
                            cctx.localNode(),
                            "SQL query executed.",
                            EVT_CACHE_QUERY_EXECUTED,
                            org.gridgain.grid.cache.query.GridCacheQueryType.SQL,
                            cctx.namex(),
                            qry.queryClassName(),
                            qry.clause(),
                            null,
                            null,
                            args,
                            subjId,
                            taskName));
                    }

                    iter = idxProc.query(space, qry.clause(), F.asList(args),
                        qry.queryClassName(), filter(qry));

                    break;

                case SCAN:
                    if (cctx.gridEvents().isRecordable(EVT_CACHE_QUERY_EXECUTED)) {
                        cctx.gridEvents().record(new IgniteCacheQueryExecutedEvent<>(
                            cctx.localNode(),
                            "Scan query executed.",
                            EVT_CACHE_QUERY_EXECUTED,
                            org.gridgain.grid.cache.query.GridCacheQueryType.SCAN,
                            cctx.namex(),
                            null,
                            null,
                            qry.scanFilter(),
                            null,
                            null,
                            subjId,
                            taskName));
                    }

                    iter = scanIterator(qry);

                    break;

                case TEXT:
                    if (cctx.gridEvents().isRecordable(EVT_CACHE_QUERY_EXECUTED)) {
                        cctx.gridEvents().record(new IgniteCacheQueryExecutedEvent<>(
                            cctx.localNode(),
                            "Full text query executed.",
                            EVT_CACHE_QUERY_EXECUTED,
                            org.gridgain.grid.cache.query.GridCacheQueryType.FULL_TEXT,
                            cctx.namex(),
                            qry.queryClassName(),
                            qry.clause(),
                            null,
                            null,
                            null,
                            subjId,
                            taskName));
                    }

                    iter = idxProc.queryText(space, qry.clause(), qry.queryClassName(), filter(qry));

                    break;

                case SET:
                    iter = setIterator(qry);

                    break;

                case SQL_FIELDS:
                    assert false : "SQL fields query is incorrectly processed.";

                default:
                    throw new IgniteCheckedException("Unknown query type: " + qry.type());
            }

            res.onDone(iter);
        }
        catch (Exception e) {
            res.onDone(e);
        }
        finally {
            if (resKey != null)
                qryResCache.remove(resKey, res);
        }

        return res;
    }

    /**
     * Performs fields query.
     *
     * @param qry Query.
     * @param args Arguments.
     * @param loc Local query or not.
     * @param subjId Security subject ID.
     * @param taskName Task name.
     * @param recipient ID of the recipient.
     * @return Collection of found keys.
     * @throws IgniteCheckedException In case of error.
     */
    private FieldsResult executeFieldsQuery(GridCacheQueryAdapter<?> qry, @Nullable Object[] args,
        boolean loc, @Nullable UUID subjId, @Nullable String taskName, Object recipient) throws IgniteCheckedException {
        assert qry != null;

        FieldsResult res;

        T2<String, List<Object>> resKey = null;

        if (qry.type() == SQL_FIELDS) {
            if (qry.clause() == null) {
                assert !loc;

                throw new IgniteCheckedException("Received next page request after iterator was removed. " +
                    "Consider increasing maximum number of stored iterators (see " +
                    "GridCacheConfiguration.getMaximumQueryIteratorCount() configuration property).");
            }

            if (cctx.gridEvents().isRecordable(EVT_CACHE_QUERY_EXECUTED)) {
                cctx.gridEvents().record(new IgniteCacheQueryExecutedEvent<>(
                    cctx.localNode(),
                    "SQL fields query executed.",
                    EVT_CACHE_QUERY_EXECUTED,
                    org.gridgain.grid.cache.query.GridCacheQueryType.SQL_FIELDS,
                    cctx.namex(),
                    null,
                    qry.clause(),
                    null,
                    null,
                    args,
                    subjId,
                    taskName));
            }

            // Attempt to get result from cache.
            resKey = new T2<>(qry.clause(), F.asList(args));

            res = (FieldsResult)qryResCache.get(resKey);

            if (res != null && res.addRecipient(recipient))
                return res; // Cached result found.

            res = new FieldsResult(recipient);

            if (qryResCache.putIfAbsent(resKey, res) != null)
                resKey = null; // Failed to cache result.
        }
        else {
            assert qry.type() == SPI;

            if (cctx.gridEvents().isRecordable(EVT_CACHE_QUERY_EXECUTED)) {
                cctx.gridEvents().record(new IgniteCacheQueryExecutedEvent<>(
                    cctx.localNode(),
                    "SPI query executed.",
                    EVT_CACHE_QUERY_EXECUTED,
                    org.gridgain.grid.cache.query.GridCacheQueryType.SPI,
                    cctx.namex(),
                    null,
                    null,
                    null,
                    null,
                    args,
                    subjId,
                    taskName));
            }

            res = new FieldsResult(recipient);
        }

        try {
            if (qry.type() == SPI) {
                IgniteSpiCloseableIterator<?> iter = cctx.kernalContext().indexing().query(space, F.asList(args),
                    filter(qry));

                res.onDone(iter);
            }
            else {
                assert qry.type() == SQL_FIELDS;

                GridQueryFieldsResult qryRes = idxProc.queryFields(space, qry.clause(), F.asList(args), filter(qry));

                res.metaData(qryRes.metaData());

                res.onDone(qryRes.iterator());
            }
        }
        catch (Exception e) {
            res.onDone(e);
        }
        finally {
            if (resKey != null)
                qryResCache.remove(resKey, res);
        }

        return res;
    }

    /**
     * @param qry Query.
     * @return Cache set items iterator.
     */
    private GridCloseableIterator<IgniteBiTuple<K, V>> setIterator(GridCacheQueryAdapter<?> qry) {
        final GridSetQueryPredicate filter = (GridSetQueryPredicate)qry.scanFilter();

        filter.init(cctx);

        IgniteUuid id = filter.setId();

        Collection<GridCacheSetItemKey> data = cctx.dataStructures().setData(id);

        if (data == null)
            data = Collections.emptyList();

        final GridIterator<IgniteBiTuple<K, V>> it = F.iterator(
            data,
            new C1<GridCacheSetItemKey, IgniteBiTuple<K, V>>() {
                @Override public IgniteBiTuple<K, V> apply(GridCacheSetItemKey e) {
                    return new IgniteBiTuple<>((K)e.item(), (V)Boolean.TRUE);
                }
            },
            true,
            new P1<GridCacheSetItemKey>() {
                @Override public boolean apply(GridCacheSetItemKey e) {
                    return filter.apply(e, null);
                }
            });

        return new GridCloseableIteratorAdapter<IgniteBiTuple<K, V>>() {
            @Override protected boolean onHasNext() {
                return it.hasNext();
            }

            @Override protected IgniteBiTuple<K, V> onNext() {
                return it.next();
            }

            @Override protected void onRemove() {
                it.remove();
            }

            @Override protected void onClose() {
                // No-op.
            }
        };
    }

    /**
     * @param qry Query.
     * @return Full-scan row iterator.
     * @throws IgniteCheckedException If failed to get iterator.
     */
    @SuppressWarnings({"unchecked"})
    private GridCloseableIterator<IgniteBiTuple<K, V>> scanIterator(final GridCacheQueryAdapter<?> qry)
        throws IgniteCheckedException {
        IgnitePredicate<GridCacheEntry<K, V>> filter = null;

        if (qry.projectionFilter() != null) {
            filter = new P1<GridCacheEntry<K, V>>() {
                @Override public boolean apply(GridCacheEntry<K, V> e) {
                    return qry.projectionFilter().apply((GridCacheEntry<Object, Object>)e);
                }
            };
        }

        GridCacheProjection<K, V> prj0 = filter != null ? cctx.cache().projection(filter) : cctx.cache();

        if (qry.keepPortable())
            prj0 = prj0.keepPortable();

        final GridCacheProjection<K, V> prj = prj0;

        final IgniteBiPredicate<K, V> keyValFilter = qry.scanFilter();

        injectResources(keyValFilter);

        GridIterator<IgniteBiTuple<K, V>> heapIt = new GridIteratorAdapter<IgniteBiTuple<K, V>>() {
            private IgniteBiTuple<K, V> next;

            private Iterator<K> iter = prj.keySet().iterator();

            {
                advance();
            }

            @Override public boolean hasNextX() {
                return next != null;
            }

            @Override public IgniteBiTuple<K, V> nextX() {
                if (next == null)
                    throw new NoSuchElementException();

                IgniteBiTuple<K, V> next0 = next;

                advance();

                return next0;
            }

            @Override public void removeX() {
                throw new UnsupportedOperationException();
            }

            private void advance() {
                IgniteBiTuple<K, V> next0 = null;

                while (iter.hasNext()) {
                    next0 = null;

                    K key = iter.next();

                    V val = prj.peek(key);

                    if (val != null) {
                        next0 = F.t(key, val);

                        if (checkPredicate(next0))
                            break;
                        else
                            next0 = null;
                    }
                }

                next = next0 != null ?
                    new IgniteBiTuple<>(next0.getKey(), next0.getValue()) :
                    null;
            }

            private boolean checkPredicate(Map.Entry<K, V> e) {
                if (keyValFilter != null) {
                    Map.Entry<K, V> e0 = (Map.Entry<K, V>)cctx.unwrapPortableIfNeeded(e, qry.keepPortable());

                    return keyValFilter.apply(e0.getKey(), e0.getValue());
                }

                return true;
            }
        };

        final GridIterator<IgniteBiTuple<K, V>> it;

        if (cctx.isSwapOrOffheapEnabled()) {
            List<GridIterator<IgniteBiTuple<K, V>>> iters = new ArrayList<>(3);

            iters.add(heapIt);

            if (cctx.isOffHeapEnabled())
                iters.add(offheapIterator(qry));

            if (cctx.swap().swapEnabled())
                iters.add(swapIterator(qry));

            it = new CompoundIterator<>(iters);
        }
        else
            it = heapIt;

        return new GridCloseableIteratorAdapter<IgniteBiTuple<K, V>>() {
            @Override protected boolean onHasNext() {
                return it.hasNext();
            }

            @Override protected IgniteBiTuple<K, V> onNext() {
                return it.next();
            }

            @Override protected void onRemove() {
                it.remove();
            }
        };
    }

    /**
     * @param qry Query.
     * @return Swap iterator.
     * @throws IgniteCheckedException If failed.
     */
    private GridIterator<IgniteBiTuple<K, V>> swapIterator(GridCacheQueryAdapter<?> qry)
        throws IgniteCheckedException {
        IgnitePredicate<GridCacheEntry<Object, Object>> prjPred = qry.projectionFilter();

        IgniteBiPredicate<K, V> filter = qry.scanFilter();

        Iterator<Map.Entry<byte[], byte[]>> it = cctx.swap().rawSwapIterator();

        return scanIterator(it, prjPred, filter, qry.keepPortable());
    }

    /**
     * @param qry Query.
     * @return Offheap iterator.
     */
    private GridIterator<IgniteBiTuple<K, V>> offheapIterator(GridCacheQueryAdapter<?> qry) {
        IgnitePredicate<GridCacheEntry<Object, Object>> prjPred = qry.projectionFilter();

        IgniteBiPredicate<K, V> filter = qry.scanFilter();

        if ((cctx.portableEnabled() && cctx.offheapTiered()) && (prjPred != null || filter != null)) {
            OffheapIteratorClosure c = new OffheapIteratorClosure(prjPred, filter, qry.keepPortable());

            return cctx.swap().rawOffHeapIterator(c);
        }
        else {
            Iterator<Map.Entry<byte[], byte[]>> it = cctx.swap().rawOffHeapIterator();

            return scanIterator(it, prjPred, filter, qry.keepPortable());
        }
    }

    /**
     * @param it Lazy swap or offheap iterator.
     * @param prjPred Projection predicate.
     * @param filter Scan filter.
     * @param keepPortable Keep portable flag.
     * @return Iterator.
     */
    private GridIteratorAdapter<IgniteBiTuple<K, V>> scanIterator(
        @Nullable final Iterator<Map.Entry<byte[], byte[]>> it,
        @Nullable final IgnitePredicate<GridCacheEntry<Object, Object>> prjPred,
        @Nullable final IgniteBiPredicate<K, V> filter,
        final boolean keepPortable) {
        if (it == null)
            return new GridEmptyCloseableIterator<>();

        return new GridIteratorAdapter<IgniteBiTuple<K, V>>() {
            private IgniteBiTuple<K, V> next;

            {
                advance();
            }

            @Override public boolean hasNextX() {
                return next != null;
            }

            @Override public IgniteBiTuple<K, V> nextX() {
                if (next == null)
                    throw new NoSuchElementException();

                IgniteBiTuple<K, V> next0 = next;

                advance();

                return next0;
            }

            @Override public void removeX() {
                throw new UnsupportedOperationException();
            }

            private void advance() {
                next = null;

                while (it.hasNext()) {
                    final LazySwapEntry e = new LazySwapEntry(it.next());

                    if (prjPred != null) {
                        GridCacheEntry<K, V> cacheEntry = new GridCacheScanSwapEntry(e);

                        if (!prjPred.apply((GridCacheEntry<Object, Object>)cacheEntry))
                            continue;
                    }

                    if (filter != null) {
                        K key = (K)cctx.unwrapPortableIfNeeded(e.key(), keepPortable);
                        V val = (V)cctx.unwrapPortableIfNeeded(e.value(), keepPortable);

                        if (!filter.apply(key, val))
                            continue;
                    }

                    next = new IgniteBiTuple<>(e.key(), e.value());

                    break;
                }
            }
        };
    }

    /**
     * @param o Object to inject resources to.
     * @throws IgniteCheckedException If failure occurred while injecting resources.
     */
    private void injectResources(@Nullable Object o) throws IgniteCheckedException {
        if (o != null) {
            GridKernalContext ctx = cctx.kernalContext();

            ClassLoader ldr = o.getClass().getClassLoader();

            if (ctx.deploy().isGlobalLoader(ldr))
                ctx.resource().inject(ctx.deploy().getDeployment(ctx.deploy().getClassLoaderId(ldr)), o.getClass(), o);
            else
                ctx.resource().inject(ctx.deploy().getDeployment(o.getClass().getName()), o.getClass(), o);
        }
    }

    /**
     * Processes fields query request.
     *
     * @param qryInfo Query info.
     */
    protected void runFieldsQuery(GridCacheQueryInfo qryInfo) {
        assert qryInfo != null;

        if (!enterBusy()) {
            if (cctx.localNodeId().equals(qryInfo.senderId()))
                throw new IllegalStateException("Failed to process query request (grid is stopping).");

            return; // Ignore remote requests when when node is stopping.
        }

        try {
            if (log.isDebugEnabled())
                log.debug("Running query: " + qryInfo);

            boolean rmvRes = true;

            try {
                // Preparing query closures.
                IgnitePredicate<GridCacheEntry<Object, Object>> prjFilter = qryInfo.projectionPredicate();
                IgniteClosure<Object, Object> trans = (IgniteClosure<Object, Object>)qryInfo.transformer();
                IgniteReducer<Object, Object> rdc = (IgniteReducer<Object, Object>)qryInfo.reducer();

                injectResources(prjFilter);
                injectResources(trans);
                injectResources(rdc);

                GridCacheQueryAdapter<?> qry = qryInfo.query();

                int pageSize = qry.pageSize();

                Collection<Object> data = null;
                Collection<Object> entities = null;

                if (qryInfo.local() || rdc != null || cctx.isLocalNode(qryInfo.senderId()))
                    data = new ArrayList<>(pageSize);
                else
                    entities = new ArrayList<>(pageSize);

                String taskName = cctx.kernalContext().task().resolveTaskName(qry.taskHash());

                FieldsResult res = qryInfo.local() ?
                    executeFieldsQuery(qry, qryInfo.arguments(), qryInfo.local(), qry.subjectId(), taskName,
                    recipient(qryInfo.senderId(), qryInfo.requestId())) :
                    fieldsQueryResult(qryInfo, taskName);

                // If metadata needs to be returned to user and cleaned from internal fields - copy it.
                List<GridQueryFieldMetadata> meta = qryInfo.includeMetaData() ?
                    (res.metaData() != null ? new ArrayList<GridQueryFieldMetadata>(res.metaData()) : null) :
                    res.metaData();

                if (!qryInfo.includeMetaData())
                    meta = null;

                GridCloseableIterator<?> it = new GridSpiCloseableIteratorWrapper<Object>(
                    res.iterator(recipient(qryInfo.senderId(), qryInfo.requestId())));

                if (log.isDebugEnabled())
                    log.debug("Received fields iterator [iterHasNext=" + it.hasNext() + ']');

                if (!it.hasNext()) {
                    if (rdc != null)
                        data = Collections.singletonList(rdc.reduce());

                    onFieldsPageReady(qryInfo.local(), qryInfo, meta, entities, data, true, null);

                    return;
                }

                int cnt = 0;
                boolean metaSent = false;

                while (!Thread.currentThread().isInterrupted() && it.hasNext()) {
                    Object row = it.next();

                    // Query is cancelled.
                    if (row == null) {
                        onPageReady(qryInfo.local(), qryInfo, null, true, null);

                        break;
                    }

                    if (cctx.gridEvents().isRecordable(EVT_CACHE_QUERY_OBJECT_READ)) {
                        cctx.gridEvents().record(new IgniteCacheQueryReadEvent<K, V>(
                            cctx.localNode(),
                            "SQL fields query result set row read.",
                            EVT_CACHE_QUERY_OBJECT_READ,
                            org.gridgain.grid.cache.query.GridCacheQueryType.SQL_FIELDS,
                            cctx.namex(),
                            null,
                            qry.clause(),
                            null,
                            null,
                            qryInfo.arguments(),
                            qry.subjectId(),
                            taskName,
                            null,
                            null,
                            null,
                            row));
                    }

                    if ((qryInfo.local() || rdc != null || cctx.isLocalNode(qryInfo.senderId()))) {
                        // Reduce.
                        if (rdc != null) {
                            if (!rdc.collect(row))
                                break;
                        }
                        else
                            data.add(row);
                    }
                    else
                        entities.add(row);

                    if (rdc == null && ((!qryInfo.allPages() && ++cnt == pageSize) || !it.hasNext())) {
                        onFieldsPageReady(qryInfo.local(), qryInfo, !metaSent ? meta : null,
                            entities, data, !it.hasNext(), null);

                        if (it.hasNext())
                            rmvRes = false;

                        if (!qryInfo.allPages())
                            return;
                    }
                }

                if (rdc != null) {
                    onFieldsPageReady(qryInfo.local(), qryInfo, meta, null,
                        Collections.singletonList(rdc.reduce()), true, null);
                }
            }
            catch (IgniteCheckedException e) {
                if (log.isDebugEnabled() || !e.hasCause(SQLException.class))
                    U.error(log, "Failed to run fields query [qry=" + qryInfo + ", node=" + cctx.nodeId() + ']', e);
                else {
                    if (e.hasCause(SQLException.class))
                        U.error(log, "Failed to run fields query [node=" + cctx.nodeId() +
                            ", msg=" + e.getCause(SQLException.class).getMessage() + ']');
                    else
                        U.error(log, "Failed to run fields query [node=" + cctx.nodeId() +
                            ", msg=" + e.getMessage() + ']');
                }

                onFieldsPageReady(qryInfo.local(), qryInfo, null, null, null, true, e);
            }
            catch (Throwable e) {
                U.error(log, "Failed to run fields query [qry=" + qryInfo + ", node=" + cctx.nodeId() + "]", e);

                onFieldsPageReady(qryInfo.local(), qryInfo, null, null, null, true, e);
            }
            finally {
                if (rmvRes)
                    removeFieldsQueryResult(qryInfo.senderId(), qryInfo.requestId());
            }
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * Processes cache query request.
     *
     * @param qryInfo Query info.
     */
    @SuppressWarnings("unchecked")
    protected void runQuery(GridCacheQueryInfo qryInfo) {
        assert qryInfo != null;

        if (!enterBusy()) {
            if (cctx.localNodeId().equals(qryInfo.senderId()))
                throw new IllegalStateException("Failed to process query request (grid is stopping).");

            return; // Ignore remote requests when when node is stopping.
        }

        try {
            boolean loc = qryInfo.local();

            if (log.isDebugEnabled())
                log.debug("Running query: " + qryInfo);

            boolean rmvIter = true;

            try {
                // Preparing query closures.
                IgnitePredicate<GridCacheEntry<Object, Object>> prjFilter = qryInfo.projectionPredicate();
                IgniteClosure<Map.Entry<K, V>, Object> trans = (IgniteClosure<Map.Entry<K, V>, Object>)qryInfo.transformer();
                IgniteReducer<Map.Entry<K, V>, Object> rdc = (IgniteReducer<Map.Entry<K, V>, Object>)qryInfo.reducer();

                injectResources(prjFilter);
                injectResources(trans);
                injectResources(rdc);

                GridCacheQueryAdapter<?> qry = qryInfo.query();

                int pageSize = qry.pageSize();

                boolean incBackups = qry.includeBackups();

                String taskName = cctx.kernalContext().task().resolveTaskName(qry.taskHash());

                IgniteSpiCloseableIterator<IgniteBiTuple<K, V>> iter;
                GridCacheQueryType type;

                QueryResult<K, V> res;

                res = loc ?
                    executeQuery(qry, qryInfo.arguments(), loc, qry.subjectId(), taskName,
                    recipient(qryInfo.senderId(), qryInfo.requestId())) :
                    queryResult(qryInfo, taskName);

                iter = res.iterator(recipient(qryInfo.senderId(), qryInfo.requestId()));
                type = res.type();

                GridCacheAdapter<K, V> cache = cctx.cache();

                if (log.isDebugEnabled())
                    log.debug("Received index iterator [iterHasNext=" + iter.hasNext() +
                        ", cacheSize=" + cache.size() + ']');

                int cnt = 0;

                boolean stop = false;
                boolean pageSent = false;

                Collection<Object> data = new ArrayList<>(pageSize);

                long topVer = cctx.affinity().affinityTopologyVersion();

                while (!Thread.currentThread().isInterrupted() && iter.hasNext()) {
                    IgniteBiTuple<K, V> row = iter.next();

                    // Query is cancelled.
                    if (row == null) {
                        onPageReady(loc, qryInfo, null, true, null);

                        break;
                    }

                    K key = row.getKey();

                    // Filter backups for SCAN queries. Other types are filtered in indexing manager.
                    if (!cctx.isReplicated() && cctx.config().getCacheMode() != LOCAL && qry.type() == SCAN &&
                        !incBackups && !cctx.affinity().primary(cctx.localNode(), key, topVer)) {
                        if (log.isDebugEnabled())
                            log.debug("Ignoring backup element [row=" + row +
                                ", cacheMode=" + cctx.config().getCacheMode() + ", incBackups=" + incBackups +
                                ", primary=" + cctx.affinity().primary(cctx.localNode(), key, topVer) + ']');

                        continue;
                    }

                    V val = row.getValue();

                    if (log.isDebugEnabled())
                        log.debug("Record [key=" + key + ", val=" + val + ", incBackups=" +
                            incBackups + "priNode=" + U.id8(CU.primaryNode(cctx, key).id()) +
                            ", node=" + U.id8(cctx.grid().localNode().id()) + ']');

                    if (val == null) {
                        if (log.isDebugEnabled())
                            log.debug("Unsuitable record value: " + val);

                        continue;
                    }

                    switch (type) {
                        case SQL:
                            if (cctx.gridEvents().isRecordable(EVT_CACHE_QUERY_OBJECT_READ)) {
                                cctx.gridEvents().record(new IgniteCacheQueryReadEvent<>(
                                    cctx.localNode(),
                                    "SQL query entry read.",
                                    EVT_CACHE_QUERY_OBJECT_READ,
                                    org.gridgain.grid.cache.query.GridCacheQueryType.SQL,
                                    cctx.namex(),
                                    qry.queryClassName(),
                                    qry.clause(),
                                    null,
                                    null,
                                    qryInfo.arguments(),
                                    qry.subjectId(),
                                    taskName,
                                    key,
                                    val,
                                    null,
                                    null));
                            }

                            break;

                        case TEXT:
                            if (cctx.gridEvents().isRecordable(EVT_CACHE_QUERY_OBJECT_READ)) {
                                cctx.gridEvents().record(new IgniteCacheQueryReadEvent<>(
                                    cctx.localNode(),
                                    "Full text query entry read.",
                                    EVT_CACHE_QUERY_OBJECT_READ,
                                    org.gridgain.grid.cache.query.GridCacheQueryType.FULL_TEXT,
                                    cctx.namex(),
                                    qry.queryClassName(),
                                    qry.clause(),
                                    null,
                                    null,
                                    null,
                                    qry.subjectId(),
                                    taskName,
                                    key,
                                    val,
                                    null,
                                    null));
                            }

                            break;

                        case SCAN:
                            if (cctx.gridEvents().isRecordable(EVT_CACHE_QUERY_OBJECT_READ)) {
                                cctx.gridEvents().record(new IgniteCacheQueryReadEvent<>(
                                    cctx.localNode(),
                                    "Scan query entry read.",
                                    EVT_CACHE_QUERY_OBJECT_READ,
                                    org.gridgain.grid.cache.query.GridCacheQueryType.SCAN,
                                    cctx.namex(),
                                    null,
                                    null,
                                    qry.scanFilter(),
                                    null,
                                    null,
                                    qry.subjectId(),
                                    taskName,
                                    key,
                                    val,
                                    null,
                                    null));
                            }

                            break;
                    }

                    Map.Entry<K, V> entry = F.t(key, val);

                    // Unwrap entry for reducer or transformer only.
                    if (rdc != null || trans != null)
                        entry = (Map.Entry<K, V>)cctx.unwrapPortableIfNeeded(entry, qry.keepPortable());

                    // Reduce.
                    if (rdc != null) {
                        if (!rdc.collect(entry) || !iter.hasNext()) {
                            onPageReady(loc, qryInfo, Collections.singletonList(rdc.reduce()), true, null);

                            pageSent = true;

                            break;
                        }
                        else
                            continue;
                    }

                    data.add(trans != null ? trans.apply(entry) :
                        !loc ? new GridCacheQueryResponseEntry<>(key, val) : F.t(key, val));

                    if (!loc) {
                        if (++cnt == pageSize || !iter.hasNext()) {
                            boolean finished = !iter.hasNext();

                            onPageReady(loc, qryInfo, data, finished, null);

                            pageSent = true;

                            if (!finished)
                                rmvIter = false;

                            if (!qryInfo.allPages())
                                return;

                            data = new ArrayList<>(pageSize);

                            if (stop)
                                break; // while
                        }
                    }
                }

                if (!pageSent) {
                    if (rdc == null)
                        onPageReady(loc, qryInfo, data, true, null);
                    else
                        onPageReady(loc, qryInfo, Collections.singletonList(rdc.reduce()), true, null);
                }
            }
            catch (Throwable e) {
                U.error(log, "Failed to run query [qry=" + qryInfo + ", node=" + cctx.nodeId() + "]", e);

                onPageReady(loc, qryInfo, null, true, e);
            }
            finally {
                if (rmvIter)
                    removeQueryResult(qryInfo.senderId(), qryInfo.requestId());
            }
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param qryInfo Info.
     * @return Iterator.
     * @throws IgniteCheckedException In case of error.
     */
    private QueryResult<K, V> queryResult(GridCacheQueryInfo qryInfo, String taskName) throws IgniteCheckedException {
        final UUID sndId = qryInfo.senderId();

        assert sndId != null;

        Map<Long, GridFutureAdapter<QueryResult<K, V>>> futs = qryIters.get(sndId);

        if (futs == null) {
            futs = new LinkedHashMap<Long, GridFutureAdapter<QueryResult<K, V>>>(
                16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Long, GridFutureAdapter<QueryResult<K, V>>> e) {
                    boolean rmv = size() > maxIterCnt;

                    if (rmv) {
                        try {
                            e.getValue().get().closeIfNotShared(recipient(sndId, e.getKey()));
                        }
                        catch (IgniteCheckedException ex) {
                            U.error(log, "Failed to close query iterator.", ex);
                        }
                    }

                    return rmv;
                }
            };

            Map<Long, GridFutureAdapter<QueryResult<K, V>>> old = qryIters.putIfAbsent(sndId, futs);

            if (old != null)
                futs = old;
        }

        return queryResult(futs, qryInfo, taskName);
    }

    /**
     * @param futs Futures map.
     * @param qryInfo Info.
     * @return Iterator.
     * @throws IgniteCheckedException In case of error.
     */
    @SuppressWarnings({"SynchronizationOnLocalVariableOrMethodParameter",
        "NonPrivateFieldAccessedInSynchronizedContext"})
    private QueryResult<K, V> queryResult(Map<Long, GridFutureAdapter<QueryResult<K, V>>> futs,
        GridCacheQueryInfo qryInfo, String taskName) throws IgniteCheckedException {
        assert futs != null;
        assert qryInfo != null;

        GridFutureAdapter<QueryResult<K, V>> fut;

        boolean exec = false;

        synchronized (futs) {
            fut = futs.get(qryInfo.requestId());

            if (fut == null) {
                futs.put(qryInfo.requestId(), fut = new GridFutureAdapter<>(cctx.kernalContext()));

                exec = true;
            }
        }

        if (exec) {
            try {
                fut.onDone(executeQuery(qryInfo.query(), qryInfo.arguments(), false,
                    qryInfo.query().subjectId(), taskName, recipient(qryInfo.senderId(), qryInfo.requestId())));
            }
            catch (Error e) {
                fut.onDone(e);

                throw e;
            }
            catch (Throwable e) {
                fut.onDone(e);
            }
        }

        return fut.get();
    }

    /**
     * @param sndId Sender node ID.
     * @param reqId Request ID.
     */
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    protected void removeQueryResult(@Nullable UUID sndId, long reqId) {
        if (sndId == null)
            return;

        Map<Long, GridFutureAdapter<QueryResult<K, V>>> futs = qryIters.get(sndId);

        if (futs != null) {
            IgniteFuture<QueryResult<K, V>> fut;

            synchronized (futs) {
                fut = futs.remove(reqId);
            }

            if (fut != null) {
                try {
                    fut.get().closeIfNotShared(recipient(sndId, reqId));
                }
                catch (IgniteCheckedException e) {
                    U.error(log, "Failed to close iterator.", e);
                }
            }
        }
    }

    /**
     * @param sndId Sender node ID.
     * @param reqId Request ID.
     * @return Recipient ID.
     */
    private static Object recipient(UUID sndId, long reqId) {
        assert sndId != null;

        return new IgniteBiTuple<>(sndId, reqId);
    }

    /**
     * @param qryInfo Info.
     * @return Iterator.
     * @throws IgniteCheckedException In case of error.
     */
    private FieldsResult fieldsQueryResult(GridCacheQueryInfo qryInfo, String taskName)
        throws IgniteCheckedException {
        final UUID sndId = qryInfo.senderId();

        assert sndId != null;

        Map<Long, GridFutureAdapter<FieldsResult>> iters = fieldsQryRes.get(sndId);

        if (iters == null) {
            iters = new LinkedHashMap<Long, GridFutureAdapter<FieldsResult>>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Long,
                    GridFutureAdapter<FieldsResult>> e) {
                    boolean rmv = size() > maxIterCnt;

                    if (rmv) {
                        try {
                            e.getValue().get().closeIfNotShared(recipient(sndId, e.getKey()));
                        }
                        catch (IgniteCheckedException ex) {
                            U.error(log, "Failed to close fields query iterator.", ex);
                        }
                    }

                    return rmv;
                }

                @Override public boolean equals(Object o) {
                    return o == this;
                }
            };

            Map<Long, GridFutureAdapter<FieldsResult>> old = fieldsQryRes.putIfAbsent(sndId, iters);

            if (old != null)
                iters = old;
        }

        return fieldsQueryResult(iters, qryInfo, taskName);
    }

    /**
     * @param resMap Results map.
     * @param qryInfo Info.
     * @return Fields query result.
     * @throws IgniteCheckedException In case of error.
     */
    @SuppressWarnings({"SynchronizationOnLocalVariableOrMethodParameter",
        "NonPrivateFieldAccessedInSynchronizedContext"})
    private FieldsResult fieldsQueryResult(Map<Long, GridFutureAdapter<FieldsResult>> resMap,
        GridCacheQueryInfo qryInfo, String taskName) throws IgniteCheckedException {
        assert resMap != null;
        assert qryInfo != null;

        GridFutureAdapter<FieldsResult> fut;

        boolean exec = false;

        synchronized (resMap) {
            fut = resMap.get(qryInfo.requestId());

            if (fut == null) {
                resMap.put(qryInfo.requestId(), fut =
                    new GridFutureAdapter<>(cctx.kernalContext()));

                exec = true;
            }
        }

        if (exec) {
            try {
                fut.onDone(executeFieldsQuery(qryInfo.query(), qryInfo.arguments(), false,
                    qryInfo.query().subjectId(), taskName, recipient(qryInfo.senderId(), qryInfo.requestId())));
            }
            catch (IgniteCheckedException e) {
                fut.onDone(e);
            }
        }

        return fut.get();
    }

    /**
     * @param sndId Sender node ID.
     * @param reqId Request ID.
     */
    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    protected void removeFieldsQueryResult(@Nullable UUID sndId, long reqId) {
        if (sndId == null)
            return;

        Map<Long, GridFutureAdapter<FieldsResult>> futs = fieldsQryRes.get(sndId);

        if (futs != null) {
            IgniteFuture<FieldsResult> fut;

            synchronized (futs) {
                fut = futs.remove(reqId);
            }

            if (fut != null) {
                assert fut.isDone();

                try {
                    fut.get().closeIfNotShared(recipient(sndId, reqId));
                }
                catch (IgniteCheckedException e) {
                    U.error(log, "Failed to close iterator.", e);
                }
            }
        }
    }

    /**
     * Called when data for page is ready.
     *
     * @param loc Local query or not.
     * @param qryInfo Query info.
     * @param data Result data.
     * @param finished Last page or not.
     * @param e Exception in case of error.
     * @return {@code true} if page was processed right.
     */
    protected abstract boolean onPageReady(boolean loc, GridCacheQueryInfo qryInfo,
        @Nullable Collection<?> data, boolean finished, @Nullable Throwable e);

    /**
     * @param loc Local query or not.
     * @param qryInfo Query info.
     * @param metaData Meta data.
     * @param entities Indexing entities.
     * @param data Data.
     * @param finished Last page or not.
     * @param e Exception in case of error.
     * @return {@code true} if page was processed right.
     */
    protected abstract boolean onFieldsPageReady(boolean loc, GridCacheQueryInfo qryInfo,
        @Nullable List<GridQueryFieldMetadata> metaData,
        @Nullable Collection<?> entities,
        @Nullable Collection<?> data,
        boolean finished, @Nullable Throwable e);

    /**
     * Gets cache queries metrics.
     *
     * @return Cache queries metrics.
     */
    public GridCacheQueryMetrics metrics() {
        return metrics.copy();
    }

    /**
     * Resets metrics.
     */
    public void resetMetrics() {
        metrics = new GridCacheQueryMetricsAdapter();
    }

    /**
     * @param duration Execution duration.
     * @param fail {@code true} if execution failed.
     */
    public void onMetricsUpdate(long duration, boolean fail) {
        metrics.onQueryExecute(duration, fail);
    }

    /**
     * Gets SQL metadata.
     *
     * @return SQL metadata.
     * @throws IgniteCheckedException In case of error.
     */
    public Collection<GridCacheSqlMetadata> sqlMetadata() throws IgniteCheckedException {
        if (!enterBusy())
            throw new IllegalStateException("Failed to get metadata (grid is stopping).");

        try {
            Callable<Collection<CacheSqlMetadata>> job = new MetadataJob();

            // Remote nodes that have current cache.
            Collection<ClusterNode> nodes = F.view(cctx.discovery().remoteNodes(), new P1<ClusterNode>() {
                @Override public boolean apply(ClusterNode n) {
                    return U.hasCache(n, space);
                }
            });

            Collection<Collection<CacheSqlMetadata>> res = new ArrayList<>(nodes.size() + 1);

            IgniteFuture<Collection<Collection<CacheSqlMetadata>>> rmtFut = null;

            // Get metadata from remote nodes.
            if (!nodes.isEmpty())
                rmtFut = cctx.closures().callAsyncNoFailover(BROADCAST, F.asSet(job), nodes, true);

            // Get local metadata.
            IgniteFuture<Collection<CacheSqlMetadata>> locFut = cctx.closures().callLocalSafe(job, true);

            if (rmtFut != null)
                res.addAll(rmtFut.get());

            res.add(locFut.get());

            Map<String, Collection<CacheSqlMetadata>> map = new HashMap<>();

            for (Collection<CacheSqlMetadata> col : res) {
                for (CacheSqlMetadata meta : col) {
                    String name = meta.cacheName();

                    Collection<CacheSqlMetadata> cacheMetas = map.get(name);

                    if (cacheMetas == null)
                        map.put(name, cacheMetas = new LinkedList<>());

                    cacheMetas.add(meta);
                }
            }

            Collection<GridCacheSqlMetadata> col = new ArrayList<>(map.size());

            // Metadata for current cache must be first in list.
            col.add(new CacheSqlMetadata(map.remove(space)));

            for (Collection<CacheSqlMetadata> metas : map.values())
                col.add(new CacheSqlMetadata(metas));

            return col;
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * Gets projection filter for query.
     *
     * @param qry Query.
     * @return Filter.
     */
    @SuppressWarnings("unchecked")
    @Nullable private GridIndexingQueryFilter projectionFilter(GridCacheQueryAdapter<?> qry) {
        assert qry != null;

        final IgnitePredicate<GridCacheEntry<Object, Object>> prjFilter = qry.projectionFilter();

        if (prjFilter == null || F.isAlwaysTrue(prjFilter))
            return null;

        return new GridIndexingQueryFilter() {
            @Nullable @Override public IgniteBiPredicate<K, V> forSpace(String spaceName) {
                if (!F.eq(space, spaceName))
                    return null;

                return new IgniteBiPredicate<K, V>() {
                    @Override public boolean apply(K k, V v) {
                        try {
                            GridCacheEntry<K, V> entry = context().cache().entry(k);

                            return entry != null && prjFilter.apply((GridCacheEntry<Object, Object>)entry);
                        }
                        catch (GridDhtInvalidPartitionException ignore) {
                            return false;
                        }
                    }
                };
            }
        };
    }

    /**
     * @param <K> Key type.
     * @param <V> Value type.
     * @return Predicate.
     * @param includeBackups Include backups.
     */
    @SuppressWarnings("unchecked")
    @Nullable public <K, V> GridIndexingQueryFilter backupsFilter(boolean includeBackups) {
        if (includeBackups)
            return null;

        return new GridIndexingQueryFilter() {
            @Nullable @Override public IgniteBiPredicate<K, V> forSpace(final String spaceName) {
                final GridKernalContext ctx = cctx.kernalContext();

                final GridCacheAdapter<Object, Object> cache = ctx.cache().internalCache(spaceName);

                if (cache.context().isReplicated() || cache.configuration().getBackups() == 0)
                    return null;

                return new IgniteBiPredicate<K, V>() {
                    @Override public boolean apply(K k, V v) {
                        return cache.context().affinity().primary(ctx.discovery().localNode(), k, -1);
                    }
                };
            }
        };
    }

    /**
     * @param qry Query.
     * @return Filter.
     */
    private GridIndexingQueryFilter filter(GridCacheQueryAdapter<?> qry) {
        return and(backupsFilter(qry.includeBackups()), projectionFilter(qry));
    }

    /**
     * @param f1 First filter.
     * @param f2 Second filter.
     * @return And filter of the given two.
     */
    @Nullable private static GridIndexingQueryFilter and(@Nullable final GridIndexingQueryFilter f1,
        @Nullable final GridIndexingQueryFilter f2) {
        if (f1 == null)
            return f2;

        if (f2 == null)
            return f1;

        return new GridIndexingQueryFilter() {
            @Nullable @Override public <K, V> IgniteBiPredicate<K, V> forSpace(String spaceName) {
                final IgniteBiPredicate<K, V> fltr1 = f1.forSpace(spaceName);
                final IgniteBiPredicate<K, V> fltr2 = f2.forSpace(spaceName);

                if (fltr1 == null)
                    return fltr2;

                if (fltr2 == null)
                    return fltr1;

                return new IgniteBiPredicate<K, V>() {
                    @Override public boolean apply(K k, V v) {
                        return fltr1.apply(k, v) && fltr2.apply(k, v);
                    }
                };
            }
        };
    }

    /**
     * Prints memory statistics for debugging purposes.
     */
    @Override public void printMemoryStats() {
        X.println(">>>");
        X.println(">>> Query manager memory stats [grid=" + cctx.gridName() + ", cache=" + cctx.name() + ']');
    }

    /**
     * FOR TESTING ONLY
     *
     * @return Indexing space for this query manager.
     */
    public String space() {
        return space;
    }

    /**
     * Metadata job.
     */
    @GridInternal
    private static class MetadataJob implements IgniteCallable<Collection<CacheSqlMetadata>> {
        /** */
        private static final long serialVersionUID = 0L;

        /** Grid */
        @IgniteInstanceResource
        private Ignite ignite;

        /** {@inheritDoc} */
        @Override public Collection<CacheSqlMetadata> call() {
            final GridKernalContext ctx = ((GridKernal) ignite).context();

            Collection<String> cacheNames = F.viewReadOnly(ctx.cache().caches(),
                new C1<GridCache<?, ?>, String>() {
                    @Override public String apply(GridCache<?, ?> c) {
                        return c.name();
                    }
                },
                new P1<GridCache<?, ?>>() {
                    @Override public boolean apply(GridCache<?, ?> c) {
                        return !CU.UTILITY_CACHE_NAME.equals(c.name());
                    }
                }
            );

            return F.transform(cacheNames, new C1<String, CacheSqlMetadata>() {
                @Override public CacheSqlMetadata apply(String cacheName) {
                    Collection<GridQueryTypeDescriptor> types = ctx.query().types(cacheName);

                    Collection<String> names = U.newHashSet(types.size());
                    Map<String, String> keyClasses = U.newHashMap(types.size());
                    Map<String, String> valClasses = U.newHashMap(types.size());
                    Map<String, Map<String, String>> fields = U.newHashMap(types.size());
                    Map<String, Collection<GridCacheSqlIndexMetadata>> indexes = U.newHashMap(types.size());

                    for (GridQueryTypeDescriptor type : types) {
                        // Filter internal types (e.g., data structures).
                        if (type.name().startsWith("GridCache"))
                            continue;

                        names.add(type.name());

                        keyClasses.put(type.name(), type.keyClass().getName());
                        valClasses.put(type.name(), type.valueClass().getName());

                        int size = 2 + type.keyFields().size() + type.valueFields().size();

                        Map<String, String> fieldsMap = U.newLinkedHashMap(size);

                        // _KEY and _VAL are not included in GridIndexingTypeDescriptor.valueFields
                        fieldsMap.put("_KEY", type.keyClass().getName());
                        fieldsMap.put("_VAL", type.valueClass().getName());

                        for (Map.Entry<String, Class<?>> e : type.keyFields().entrySet())
                            fieldsMap.put(e.getKey().toUpperCase(), e.getValue().getName());

                        for (Map.Entry<String, Class<?>> e : type.valueFields().entrySet())
                            fieldsMap.put(e.getKey().toUpperCase(), e.getValue().getName());

                        fields.put(type.name(), fieldsMap);

                        Collection<GridCacheSqlIndexMetadata> indexesCol =
                            new ArrayList<>(type.indexes().size());

                        for (Map.Entry<String, GridQueryIndexDescriptor> e : type.indexes().entrySet()) {
                            GridQueryIndexDescriptor desc = e.getValue();

                            // Add only SQL indexes.
                            if (desc.type() == GridQueryIndexType.SORTED) {
                                Collection<String> idxFields = e.getValue().fields();
                                Collection<String> descendings = new LinkedList<>();

                                for (String idxField : idxFields)
                                    if (desc.descending(idxField))
                                        descendings.add(idxField);

                                indexesCol.add(new CacheSqlIndexMetadata(e.getKey().toUpperCase(),
                                    idxFields, descendings, false));
                            }
                        }

                        indexes.put(type.name(), indexesCol);
                    }

                    return new CacheSqlMetadata(cacheName, names, keyClasses, valClasses, fields, indexes);
                }
            });
        }
    }

    /**
     * Cache metadata.
     */
    private static class CacheSqlMetadata implements GridCacheSqlMetadata {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private String cacheName;

        /** */
        private Collection<String> types;

        /** */
        private Map<String, String> keyClasses;

        /** */
        private Map<String, String> valClasses;

        /** */
        private Map<String, Map<String, String>> fields;

        /** */
        private Map<String, Collection<GridCacheSqlIndexMetadata>> indexes;

        /**
         * Required by {@link Externalizable}.
         */
        public CacheSqlMetadata() {
            // No-op.
        }

        /**
         * @param cacheName Cache name.
         * @param types Types.
         * @param keyClasses Key classes map.
         * @param valClasses Value classes map.
         * @param fields Fields maps.
         * @param indexes Indexes.
         */
        CacheSqlMetadata(@Nullable String cacheName, Collection<String> types, Map<String, String> keyClasses,
            Map<String, String> valClasses, Map<String, Map<String, String>> fields,
            Map<String, Collection<GridCacheSqlIndexMetadata>> indexes) {
            assert types != null;
            assert keyClasses != null;
            assert valClasses != null;
            assert fields != null;
            assert indexes != null;

            this.cacheName = cacheName;
            this.types = types;
            this.keyClasses = keyClasses;
            this.valClasses = valClasses;
            this.fields = fields;
            this.indexes = indexes;
        }

        /**
         * @param metas Meta data instances from different nodes.
         */
        CacheSqlMetadata(Iterable<CacheSqlMetadata> metas) {
            types = new HashSet<>();
            keyClasses = new HashMap<>();
            valClasses = new HashMap<>();
            fields = new HashMap<>();
            indexes = new HashMap<>();

            for (CacheSqlMetadata meta : metas) {
                if (cacheName == null)
                    cacheName = meta.cacheName;
                else
                    assert F.eq(cacheName, meta.cacheName);

                types.addAll(meta.types);
                keyClasses.putAll(meta.keyClasses);
                valClasses.putAll(meta.valClasses);
                fields.putAll(meta.fields);
                indexes.putAll(meta.indexes);
            }
        }

        /** {@inheritDoc} */
        @Override public String cacheName() {
            return cacheName;
        }

        /** {@inheritDoc} */
        @Override public Collection<String> types() {
            return types;
        }

        /** {@inheritDoc} */
        @Override public String keyClass(String type) {
            return keyClasses.get(type);
        }

        /** {@inheritDoc} */
        @Override public String valueClass(String type) {
            return valClasses.get(type);
        }

        /** {@inheritDoc} */
        @Override public Map<String, String> fields(String type) {
            return fields.get(type);
        }

        /** {@inheritDoc} */
        @Override public Collection<GridCacheSqlIndexMetadata> indexes(String type) {
            return indexes.get(type);
        }

        /** {@inheritDoc} */
        @Override public void writeExternal(ObjectOutput out) throws IOException {
            U.writeString(out, cacheName);
            U.writeCollection(out, types);
            U.writeMap(out, keyClasses);
            U.writeMap(out, valClasses);
            U.writeMap(out, fields);
            U.writeMap(out, indexes);
        }

        /** {@inheritDoc} */
        @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            cacheName = U.readString(in);
            types = U.readCollection(in);
            keyClasses = U.readMap(in);
            valClasses = U.readMap(in);
            fields = U.readMap(in);
            indexes = U.readMap(in);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(CacheSqlMetadata.class, this);
        }
    }

    /**
     * Cache metadata index.
     */
    private static class CacheSqlIndexMetadata implements GridCacheSqlIndexMetadata {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private String name;

        /** */
        private Collection<String> fields;

        /** */
        private Collection<String> descendings;

        /** */
        private boolean unique;

        /**
         * Required by {@link Externalizable}.
         */
        public CacheSqlIndexMetadata() {
            // No-op.
        }

        /**
         * @param name Index name.
         * @param fields Fields.
         * @param descendings Descendings.
         * @param unique Unique flag.
         */
        CacheSqlIndexMetadata(String name, Collection<String> fields, Collection<String> descendings,
            boolean unique) {
            assert name != null;
            assert fields != null;
            assert descendings != null;

            this.name = name;
            this.fields = fields;
            this.descendings = descendings;
            this.unique = unique;
        }

        /** {@inheritDoc} */
        @Override public String name() {
            return name;
        }

        /** {@inheritDoc} */
        @Override public Collection<String> fields() {
            return fields;
        }

        /** {@inheritDoc} */
        @Override public boolean descending(String field) {
            return descendings.contains(field);
        }

        /** {@inheritDoc} */
        @Override public boolean unique() {
            return unique;
        }

        /** {@inheritDoc} */
        @Override public void writeExternal(ObjectOutput out) throws IOException {
            U.writeString(out, name);
            U.writeCollection(out, fields);
            U.writeCollection(out, descendings);
            out.writeBoolean(unique);
        }

        /** {@inheritDoc} */
        @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            name = U.readString(in);
            fields = U.readCollection(in);
            descendings = U.readCollection(in);
            unique = in.readBoolean();
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(CacheSqlIndexMetadata.class, this);
        }
    }

    /**
     *
     */
    private static class QueryResult<K, V> extends CachedResult<IgniteBiTuple<K, V>> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private final GridCacheQueryType type;

        /**
         * @param type Query type.
         * @param recipient ID of the recipient.
         */
        private QueryResult(GridCacheQueryType type, Object recipient) {
            super(recipient);

            this.type = type;
        }

        /**
         * @return Type.
         */
        public GridCacheQueryType type() {
            return type;
        }
    }

    /**
     *
     */
    private static class FieldsResult<Q> extends CachedResult<Q> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        private List<GridQueryFieldMetadata> meta;

        /**
         * @param recipient ID of the recipient.
         */
        FieldsResult(Object recipient) {
            super(recipient);
        }

        /**
         * @return Metadata.
         */
        public List<GridQueryFieldMetadata> metaData() throws IgniteCheckedException {
            get(); // Ensure that result is ready.

            return meta;
        }

        /**
         * @param meta Metadata.
         */
        public void metaData(List<GridQueryFieldMetadata> meta) {
            this.meta = meta;
        }
    }

    /**
     *
     */
    private abstract class AbstractLazySwapEntry {
        /** */
        private K key;

        /** */
        private V val;

        /**
         * @return Key bytes.
         */
        protected abstract byte[] keyBytes();

        /**
         * @return Value.
         * @throws IgniteCheckedException If failed.
         */
        protected abstract V unmarshalValue() throws IgniteCheckedException;

        /**
         * @return Key.
         */
        K key() {
            try {
                if (key != null)
                    return key;

                key = cctx.marshaller().unmarshal(keyBytes(), cctx.deploy().globalLoader());

                return key;
            }
            catch (IgniteCheckedException e) {
                throw new IgniteException(e);
            }
        }

        /**
         * @return Value.
         */
        V value() {
            try {
                if (val != null)
                    return val;

                val = unmarshalValue();

                return val;
            }
            catch (IgniteCheckedException e) {
                throw new IgniteException(e);
            }
        }

        /**
         * @return TTL.
         */
        abstract long timeToLive();

        /**
         * @return Expire time.
         */
        abstract long expireTime();

        /**
         * @return Version.
         */
        abstract GridCacheVersion version();
    }

    /**
     *
     */
    private class LazySwapEntry extends AbstractLazySwapEntry {
        /** */
        private final Map.Entry<byte[], byte[]> e;

        /**
         * @param e Entry with
         */
        LazySwapEntry(Map.Entry<byte[], byte[]> e) {
            this.e = e;
        }

        /** {@inheritDoc} */
        @Override protected byte[] keyBytes() {
            return e.getKey();
        }

        /** {@inheritDoc} */
        @SuppressWarnings("IfMayBeConditional")
        @Override protected V unmarshalValue() throws IgniteCheckedException {
            byte[] bytes = e.getValue();

            byte[] val = GridCacheSwapEntryImpl.getValueIfByteArray(bytes);

            if (val != null)
                return (V)val;

            if (cctx.offheapTiered() && cctx.portableEnabled())
                return (V)cctx.portable().unmarshal(bytes, GridCacheSwapEntryImpl.valueOffset(bytes));
            else {
                GridByteArrayInputStream in = new GridByteArrayInputStream(bytes,
                    GridCacheSwapEntryImpl.valueOffset(bytes),
                    bytes.length);

                return cctx.marshaller().unmarshal(in, cctx.deploy().globalLoader());
            }
        }

        /** {@inheritDoc} */
        @Override long timeToLive() {
            return GridCacheSwapEntryImpl.timeToLive(e.getValue());
        }

        /** {@inheritDoc} */
        @Override long expireTime() {
            return GridCacheSwapEntryImpl.expireTime(e.getValue());
        }

        /** {@inheritDoc} */
        @Override GridCacheVersion version() {
            return GridCacheSwapEntryImpl.version(e.getValue());
        }
    }

    /**
     *
     */
    private class LazyOffheapEntry extends AbstractLazySwapEntry {
        /** */
        private final T2<Long, Integer> keyPtr;

        /** */
        private final T2<Long, Integer> valPtr;

        /**
         * @param keyPtr Key address.
         * @param valPtr Value address.
         */
        private LazyOffheapEntry(T2<Long, Integer> keyPtr, T2<Long, Integer> valPtr) {
            assert keyPtr != null;
            assert valPtr != null;

            this.keyPtr = keyPtr;
            this.valPtr = valPtr;
        }

        /** {@inheritDoc} */
        @Override protected byte[] keyBytes() {
            return U.copyMemory(keyPtr.get1(), keyPtr.get2());
        }

        /** {@inheritDoc} */
        @Override protected V unmarshalValue() throws IgniteCheckedException {
            long ptr = GridCacheOffheapSwapEntry.valueAddress(valPtr.get1(), valPtr.get2());

            V val = (V)cctx.portable().unmarshal(ptr, false);

            assert val != null;

            return val;
        }

        /** {@inheritDoc} */
        @Override long timeToLive() {
            return GridCacheOffheapSwapEntry.timeToLive(valPtr.get1());
        }

        /** {@inheritDoc} */
        @Override long expireTime() {
            return GridCacheOffheapSwapEntry.expireTime(valPtr.get1());
        }

        /** {@inheritDoc} */
        @Override GridCacheVersion version() {
            return GridCacheOffheapSwapEntry.version(valPtr.get1());
        }
    }

    /**
     *
     */
    private class OffheapIteratorClosure
        extends CX2<T2<Long, Integer>, T2<Long, Integer>, IgniteBiTuple<K, V>> {
        /** */
        private static final long serialVersionUID = 7410163202728985912L;

        /** */
        private IgnitePredicate<GridCacheEntry<Object, Object>> prjPred;

        /** */
        private IgniteBiPredicate<K, V> filter;

        /** */
        private boolean keepPortable;

        /**
         * @param prjPred Projection filter.
         * @param filter Filter.
         * @param keepPortable Keep portable flag.
         */
        private OffheapIteratorClosure(
            @Nullable IgnitePredicate<GridCacheEntry<Object, Object>> prjPred,
            @Nullable IgniteBiPredicate<K, V> filter,
            boolean keepPortable) {
            assert prjPred != null || filter != null;

            this.prjPred = prjPred;
            this.filter = filter;
            this.keepPortable = keepPortable;
        }

        /** {@inheritDoc} */
        @Nullable @Override public IgniteBiTuple<K, V> applyx(T2<Long, Integer> keyPtr,
            T2<Long, Integer> valPtr)
            throws IgniteCheckedException {
            LazyOffheapEntry e = new LazyOffheapEntry(keyPtr, valPtr);

            if (prjPred != null) {
                GridCacheEntry<K, V> cacheEntry = new GridCacheScanSwapEntry(e);

                if (!prjPred.apply((GridCacheEntry<Object, Object>)cacheEntry))
                    return null;
            }

            if (filter != null) {
                K key = (K)cctx.unwrapPortableIfNeeded(e.key(), keepPortable);
                V val = (V)cctx.unwrapPortableIfNeeded(e.value(), keepPortable);

                if (!filter.apply(key, val))
                    return null;
            }

            return new IgniteBiTuple<>(e.key(), (V)cctx.unwrapTemporary(e.value())) ;
        }
    }

    /**
     *
     */
    private static class CompoundIterator<T> extends GridIteratorAdapter<T> {
        /** */
        private static final long serialVersionUID = 4585888051556166304L;

        /** */
        private final List<GridIterator<T>> iters;

        /** */
        private int idx;

        /** */
        private GridIterator<T> iter;

        /**
         * @param iters Iterators.
         */
        private CompoundIterator(List<GridIterator<T>> iters) {
            if (iters.isEmpty())
                throw new IllegalArgumentException();

            this.iters = iters;

            iter = F.first(iters);
        }

        /** {@inheritDoc} */
        @Override public boolean hasNextX() throws IgniteCheckedException {
            if (iter.hasNextX())
                return true;

            idx++;

            while(idx < iters.size()) {
                iter = iters.get(idx);

                if (iter.hasNextX())
                    return true;

                idx++;
            }

            return false;
        }

        /** {@inheritDoc} */
        @Override public T nextX() throws IgniteCheckedException {
            if (!hasNextX())
                throw new NoSuchElementException();

            return iter.nextX();
        }

        /** {@inheritDoc} */
        @Override public void removeX() throws IgniteCheckedException {
            throw new UnsupportedOperationException();
        }
    }

    /**
     *
     */
    private class GridCacheScanSwapEntry implements GridCacheEntry<K, V> {
        /** */
        private static final long serialVersionUID = 1262515168518736214L;

        /** */
        private final AbstractLazySwapEntry e;

        /**
         * @param e Entry.
         */
        private GridCacheScanSwapEntry(AbstractLazySwapEntry e) {
            this.e = e;
        }

        /** {@inheritDoc} */
        @Nullable @Override public V peek() {
            return e.value();
        }

        /** {@inheritDoc} */
        @Override public Object version() {
            return e.version();
        }

        /** {@inheritDoc} */
        @Override public long expirationTime() {
            return e.expireTime();
        }

        /** {@inheritDoc} */
        @Override public long timeToLive() {
            return e.timeToLive();
        }

        /** {@inheritDoc} */
        @Nullable @Override public V getValue() {
            return e.value();
        }

        /** {@inheritDoc} */
        @Override public K getKey() {
            return e.key();
        }

        /** {@inheritDoc} */
        @Override public GridCacheProjection<K, V> projection() {
            return null;
        }

        /** {@inheritDoc} */
        @Nullable @Override public V peek(@Nullable Collection<GridCachePeekMode> modes) throws IgniteCheckedException {
            return null;
        }

        /** {@inheritDoc} */
        @Nullable @Override public V reload() {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public IgniteFuture<V> reloadAsync() {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public boolean isLocked() {
            return false;
        }

        /** {@inheritDoc} */
        @Override public boolean isLockedByThread() {
            return false;
        }

        /** {@inheritDoc} */
        @Override public void timeToLive(long ttl) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public boolean primary() {
            return cctx.affinity().
                primary(cctx.localNode(), getKey(), cctx.affinity().affinityTopologyVersion());
        }

        /** {@inheritDoc} */
        @Override public boolean backup() {
            return cctx.affinity().
                backups(getKey(), cctx.affinity().affinityTopologyVersion()).contains(cctx.localNode());
        }

        /** {@inheritDoc} */
        @Override public int partition() {
            return cctx.affinity().partition(getKey());
        }

        /** {@inheritDoc} */
        @Nullable @Override public V get() {
            return getValue();
        }

        /** {@inheritDoc} */
        @Override public IgniteFuture<V> getAsync() {
            return new GridFinishedFuture<V>(cctx.kernalContext(), getValue());
        }

        /** {@inheritDoc} */
        @Nullable @Override public V setValue(V val) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Nullable @Override public V set(V val, IgnitePredicate<GridCacheEntry<K, V>>... filter) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public IgniteFuture<V> setAsync(V val, IgnitePredicate<GridCacheEntry<K, V>>... filter) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Nullable @Override public V setIfAbsent(V val) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public IgniteFuture<V> setIfAbsentAsync(V val) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public boolean setx(V val, @Nullable IgnitePredicate<GridCacheEntry<K, V>>... filter) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public IgniteFuture<Boolean> setxAsync(V val, @Nullable IgnitePredicate<GridCacheEntry<K, V>>... filter) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public boolean setxIfAbsent(@Nullable V val) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public IgniteFuture<Boolean> setxIfAbsentAsync(V val) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public void transform(IgniteClosure<V, V> transformer) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public IgniteFuture<?> transformAsync(IgniteClosure<V, V> transformer) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Nullable @Override public V replace(V val) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public IgniteFuture<V> replaceAsync(V val) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public boolean replacex(V val) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public IgniteFuture<Boolean> replacexAsync(V val) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public boolean replace(V oldVal, V newVal) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public IgniteFuture<Boolean> replaceAsync(V oldVal, V newVal) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Nullable @Override public V remove(@Nullable IgnitePredicate<GridCacheEntry<K, V>>... filter) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public IgniteFuture<V> removeAsync(IgnitePredicate<GridCacheEntry<K, V>>... filter) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public boolean removex(IgnitePredicate<GridCacheEntry<K, V>>... filter) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public IgniteFuture<Boolean> removexAsync(@Nullable IgnitePredicate<GridCacheEntry<K, V>>... filter) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public boolean remove(V val) throws IgniteCheckedException {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public IgniteFuture<Boolean> removeAsync(V val) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public boolean evict() {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public boolean clear() {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public boolean compact() {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public boolean lock(long timeout, @Nullable IgnitePredicate<GridCacheEntry<K, V>>... filter) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public IgniteFuture<Boolean> lockAsync(long timeout,
            @Nullable IgnitePredicate<GridCacheEntry<K, V>>... filter) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public void unlock(IgnitePredicate<GridCacheEntry<K, V>>... filter) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public boolean isCached() {
            return true;
        }

        /** {@inheritDoc} */
        @Override public int memorySize() {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public void copyMeta(GridMetadataAware from) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public void copyMeta(Map<String, ?> data) {
            throw new UnsupportedOperationException();

        }

        /** {@inheritDoc} */
        @Nullable @Override public <V> V addMeta(String name, V val) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Nullable @Override public <V> V putMetaIfAbsent(String name, V val) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Nullable @Override public <V> V putMetaIfAbsent(String name, Callable<V> c) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public <V> V addMetaIfAbsent(String name, V val) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Nullable @Override public <V> V addMetaIfAbsent(String name, @Nullable Callable<V> c) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public <V> V meta(String name) {
            return null;
        }

        /** {@inheritDoc} */
        @Override public <V> V removeMeta(String name) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public <V> boolean removeMeta(String name, V val) {
            throw new UnsupportedOperationException();
        }

        /** {@inheritDoc} */
        @Override public <V> Map<String, V> allMeta() {
            return null;
        }

        /** {@inheritDoc} */
        @Override public boolean hasMeta(String name) {
            return false;
        }

        /** {@inheritDoc} */
        @Override public <V> boolean hasMeta(String name, V val) {
            return false;
        }

        /** {@inheritDoc} */
        @Override public <V> boolean replaceMeta(String name, V curVal, V newVal) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Cached result.
     */
    private abstract static class CachedResult<R> extends GridFutureAdapter<IgniteSpiCloseableIterator<R>> {
        /** */
        private CircularQueue<R> queue;

        /** */
        private int pruned;

        /** Absolute position of each recipient. */
        private final Map<Object, QueueIterator> recipients = new GridLeanMap<>(1);

        /**
         * @param recipient ID of the recipient.
         */
        protected CachedResult(Object recipient) {
            boolean res = addRecipient(recipient);

            assert res;
        }


        /**
         * Close if this result does not have any other recipients.
         *
         * @param recipient ID of the recipient.
         * @throws IgniteCheckedException If failed.
         */
        public void closeIfNotShared(Object recipient) throws IgniteCheckedException {
            assert isDone();

            synchronized (recipients) {
                if (recipients.isEmpty())
                    return;

                recipients.remove(recipient);

                if (recipients.isEmpty())
                    get().close();
            }
        }

        /**
         * @param recipient ID of the recipient.
         * @return {@code true} If the recipient successfully added.
         */
        public boolean addRecipient(Object recipient) {
            synchronized (recipients) {
                if (isDone())
                    return false;

                assert !recipients.containsKey(recipient) : recipient + " -> " + recipients;

                recipients.put(recipient, new QueueIterator(recipient));
            }

            return true;
        }

        /** {@inheritDoc} */
        @Override public boolean onDone(@Nullable IgniteSpiCloseableIterator<R> res, @Nullable Throwable err) {
            assert !isDone();

            synchronized (recipients) {
                if (recipients.size() > 1) {
                    queue = new CircularQueue<>(128);

                    for (QueueIterator it : recipients.values())
                        it.init();
                }

                return super.onDone(res, err);
            }
        }

        /**
         *
         */
        private void pruneQueue() {
            assert !recipients.isEmpty();
            assert Thread.holdsLock(recipients);

            int minPos = Collections.min(recipients.values()).pos;

            if (minPos > pruned) {
                queue.remove(minPos - pruned);

                pruned = minPos;
            }
        }

        /**
         * @param recipient ID of the recipient.
         * @throws IgniteCheckedException If failed.
         */
        public IgniteSpiCloseableIterator<R> iterator(Object recipient) throws IgniteCheckedException {
            assert recipient != null;

            IgniteSpiCloseableIterator<R> it = get();

            assert it != null;

            synchronized (recipients) {
                return queue == null ? it : recipients.get(recipient);
            }
        }

        /**
         *
         */
        @SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
        private class QueueIterator implements IgniteSpiCloseableIterator<R>, Comparable<QueueIterator> {
            /** */
            private static final long serialVersionUID = 0L;

            /** */
            private static final int NEXT_SIZE = 64;

            /** */
            private final Object recipient;

            /** */
            private int pos;

            /** */
            private Queue<R> next;

            /**
             * @param recipient ID of the recipient.
             */
            private QueueIterator(Object recipient) {
                this.recipient = recipient;
            }

            /**
             */
            public void init() {
                assert next == null;

                next = new ArrayDeque<>(NEXT_SIZE);
            }

            /** {@inheritDoc} */
            @Override public void close() throws IgniteCheckedException {
                closeIfNotShared(recipient);
            }

            /** {@inheritDoc} */
            @Override public boolean hasNext() {
                return !next.isEmpty() || fillNext();
            }

            /** {@inheritDoc} */
            @SuppressWarnings("IteratorNextCanNotThrowNoSuchElementException") // It can actually.
            @Override public R next() {
                return next.remove();
            }

            /**
             * @return {@code true} If elements were fetched into local queue of the iterator.
             */
            private boolean fillNext() {
                assert next.isEmpty();

                IgniteSpiCloseableIterator<R> it;

                try {
                    it = get();
                }
                catch (IgniteCheckedException e) {
                    throw new IgniteException(e);
                }

                synchronized (recipients) {
                    for (int i = 0; i < NEXT_SIZE; i++) {
                        R res;

                        int off = pos - pruned; // Offset of current iterator relative to queue begin.

                        if (off == queue.size()) { // We are leading the race.
                            if (!it.hasNext())
                                break; // Happy end.

                            res = it.next();

                            queue.add(res);
                        }
                        else // Someone fetched result into queue before us.
                            res = queue.get(off);

                        assert res != null;

                        pos++;
                        next.add(res);
                    }

                    pruneQueue();
                }

                return !next.isEmpty();
            }

            /** {@inheritDoc} */
            @Override public void remove() {
                throw new UnsupportedOperationException();
            }

            /** {@inheritDoc} */
            @Override public int compareTo(QueueIterator o) {
                return Integer.compare(pos, o.pos);
            }
        }
    }

    /**
     * Queue.
     */
    @SuppressWarnings("PackageVisibleInnerClass")
    static class CircularQueue<R> {
        /** */
        private int off;

        /** */
        private int size;

        /** */
        private R[] arr;

        /**
         * @param cap Initial capacity.
         */
        CircularQueue(int cap) {
            assert U.isPow2(cap);

            arr = (R[])new Object[cap];
        }

        /**
         * @param o Object to add.
         */
        public void add(R o) {
            if (size == arr.length) { // Resize.
                Object[] newArr = new Object[arr.length << 1];

                int tailSize = arr.length - off;

                System.arraycopy(arr, off, newArr, 0, tailSize);

                if (off != 0) {
                    System.arraycopy(arr, 0, newArr, tailSize, off);

                    off = 0;
                }

                arr = (R[])newArr;
            }

            int idx = (off + size) & (arr.length - 1);

            assert arr[idx] == null;

            arr[idx] = o;

            size++;
        }

        /**
         * @param n Number of elements to remove.
         */
        public void remove(int n) {
            assert n > 0 : n;
            assert n <= size : n + " " + size;

            int mask = arr.length - 1;

            for (int i = 0; i < n; i++) {
                int idx = (off + i) & mask;

                assert arr[idx] != null;

                arr[idx] = null;
            }

            size -= n;
            off += n;

            if (off >= arr.length)
                off -= arr.length;
        }

        /**
         * @param idx Index in queue.
         * @return Element at the given index.
         */
        public R get(int idx) {
            assert idx >= 0 : idx;
            assert idx < size : idx + " " + size;

            R res = arr[(idx + off) & (arr.length - 1)];

            assert res != null;

            return res;
        }

        /**
         * @return Size.
         */
        public int size() {
            return size;
        }
    }
}
