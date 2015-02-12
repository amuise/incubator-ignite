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

package org.apache.ignite.internal.processors.cache.query.continuous;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.query.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.continuous.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.plugin.security.*;
import org.jdk8.backport.*;

import javax.cache.configuration.*;
import javax.cache.event.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static javax.cache.event.EventType.*;
import static org.apache.ignite.cache.CacheDistributionMode.*;
import static org.apache.ignite.events.EventType.*;
import static org.apache.ignite.internal.GridTopic.*;

/**
 * Continuous queries manager.
 */
public class CacheContinuousQueryManager<K, V> extends GridCacheManagerAdapter<K, V> {
    /** */
    private static final byte CREATED_FLAG = 0b0001;

    /** */
    private static final byte UPDATED_FLAG = 0b0010;

    /** */
    private static final byte REMOVED_FLAG = 0b0100;

    /** */
    private static final byte EXPIRED_FLAG = 0b1000;

    /** Listeners. */
    private final ConcurrentMap<UUID, CacheContinuousQueryListener<K, V>> lsnrs = new ConcurrentHashMap8<>();

    /** Listeners count. */
    private final AtomicInteger lsnrCnt = new AtomicInteger();

    /** Internal entries listeners. */
    private final ConcurrentMap<UUID, CacheContinuousQueryListener<K, V>> intLsnrs = new ConcurrentHashMap8<>();

    /** Internal listeners count. */
    private final AtomicInteger intLsnrCnt = new AtomicInteger();

    /** Query sequence number for message topic. */
    private final AtomicLong seq = new AtomicLong();

    /** JCache listeners. */
    private final ConcurrentMap<CacheEntryListenerConfiguration, JCacheQuery> jCacheLsnrs =
        new ConcurrentHashMap8<>();

    /** Ordered topic prefix. */
    private String topicPrefix;

    /** {@inheritDoc} */
    @Override protected void start0() throws IgniteCheckedException {
        // Append cache name to the topic.
        topicPrefix = "CONTINUOUS_QUERY" + (cctx.name() == null ? "" : "_" + cctx.name());
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override protected void onKernalStart0() throws IgniteCheckedException {
        Iterable<CacheEntryListenerConfiguration<K, V>> cfgs = cctx.config().getCacheEntryListenerConfigurations();

        if (cfgs != null) {
            for (CacheEntryListenerConfiguration<K, V> cfg : cfgs)
                executeJCacheQuery(cfg, true);
        }
    }

    /** {@inheritDoc} */
    @Override protected void onKernalStop0(boolean cancel) {
        super.onKernalStop0(cancel);

        for (JCacheQuery lsnr : jCacheLsnrs.values()) {
            try {
                lsnr.cancel();
            }
            catch (IgniteCheckedException e) {
                if (log.isDebugEnabled())
                    log.debug("Failed to stop JCache entry listener: " + e.getMessage());
            }
        }
    }

    /**
     * @param e Cache entry.
     * @param key Key.
     * @param newVal New value.
     * @param newBytes New value bytes.
     * @param oldVal Old value.
     * @param oldBytes Old value bytes.
     * @throws IgniteCheckedException In case of error.
     */
    public void onEntryUpdated(GridCacheEntryEx<K, V> e, K key, V newVal, GridCacheValueBytes newBytes,
        V oldVal, GridCacheValueBytes oldBytes, EventType type) throws IgniteCheckedException {
        assert e != null;
        assert key != null;

        ConcurrentMap<UUID, CacheContinuousQueryListener<K, V>> lsnrCol;

        if (e.isInternal())
            lsnrCol = intLsnrCnt.get() > 0 ? intLsnrs : null;
        else
            lsnrCol = lsnrCnt.get() > 0 ? lsnrs : null;

        if (F.isEmpty(lsnrCol))
            return;

        oldVal = cctx.unwrapTemporary(oldVal);

        CacheContinuousQueryEntry<K, V> e0 = new CacheContinuousQueryEntry<>(key, newVal, newBytes, oldVal, oldBytes);

        e0.initValue(cctx.marshaller(), cctx.deploy().globalLoader());

        CacheContinuousQueryEvent<K, V> evt = new CacheContinuousQueryEvent<>(
            cctx.kernalContext().grid().jcache(cctx.name()), type, e0);

        boolean primary = cctx.affinity().primary(cctx.localNode(), key, -1);
        boolean recordIgniteEvt = !e.isInternal() && cctx.gridEvents().isRecordable(EVT_CACHE_QUERY_OBJECT_READ);

        for (CacheContinuousQueryListener<K, V> lsnr : lsnrCol.values())
            lsnr.onEntryUpdated(evt, primary, recordIgniteEvt);
    }

    /**
     * @param e Entry.
     * @param key Key.
     * @param oldVal Old value.
     * @param oldBytes Old value bytes.
     */
    public void onEntryExpired(GridCacheEntryEx<K, V> e, K key, V oldVal, GridCacheValueBytes oldBytes) {
        assert e != null;
        assert key != null;

        if (e.isInternal())
            return;

        ConcurrentMap<UUID, CacheContinuousQueryListener<K, V>> lsnrCol = lsnrs;

        if (F.isEmpty(lsnrCol))
            return;

        if (cctx.isReplicated() || cctx.affinity().primary(cctx.localNode(), key, -1)) {
            CacheContinuousQueryEntry<K, V> e0 = new CacheContinuousQueryEntry<>(key, null, null, oldVal, oldBytes);

            CacheContinuousQueryEvent<K, V> evt = new CacheContinuousQueryEvent<>(
                cctx.kernalContext().grid().jcache(cctx.name()), EXPIRED, e0);

            boolean primary = cctx.affinity().primary(cctx.localNode(), key, -1);
            boolean recordIgniteEvt = cctx.gridEvents().isRecordable(EVT_CACHE_QUERY_OBJECT_READ);

            for (CacheContinuousQueryListener<K, V> lsnr : lsnrCol.values())
                lsnr.onEntryUpdated(evt, primary, recordIgniteEvt);
        }
    }

    /**
     * @param locLsnr Local listener.
     * @param rmtFilter Remote filter.
     * @param bufSize Buffer size.
     * @param timeInterval Time interval.
     * @param autoUnsubscribe Auto unsubscribe flag.
     * @param grp Cluster group.
     * @return Continuous routine ID.
     * @throws IgniteCheckedException In case of error.
     */
    public UUID executeQuery(CacheEntryUpdatedListener<K, V> locLsnr, CacheEntryEventFilter<K, V> rmtFilter,
        int bufSize, long timeInterval, boolean autoUnsubscribe, ClusterGroup grp) throws IgniteCheckedException {
        return executeQuery0(
            locLsnr,
            rmtFilter,
            bufSize,
            timeInterval,
            autoUnsubscribe,
            false,
            true,
            false,
            true,
            grp);
    }

    /**
     * @param locLsnr Local listener.
     * @param rmtFilter Remote filter.
     * @param loc Local flag.
     * @return Continuous routine ID.
     * @throws IgniteCheckedException In case of error.
     */
    public UUID executeInternalQuery(CacheEntryUpdatedListener<K, V> locLsnr, CacheEntryEventFilter<K, V> rmtFilter,
        boolean loc) throws IgniteCheckedException {
        return executeQuery0(
            locLsnr,
            rmtFilter,
            ContinuousQuery.DFLT_BUF_SIZE,
            ContinuousQuery.DFLT_TIME_INTERVAL,
            ContinuousQuery.DFLT_AUTO_UNSUBSCRIBE,
            true,
            true,
            false,
            true,
            loc ? cctx.grid().forLocal() : null);
    }

    public void cancelInternalQuery(UUID routineId) {
        try {
            cctx.kernalContext().continuous().stopRoutine(routineId).get();
        }
        catch (IgniteCheckedException e) {
            if (log.isDebugEnabled())
                log.debug("Failed to stop internal continuous query: " + e.getMessage());
        }
    }

    /**
     * @param cfg Listener configuration.
     * @param onStart Whether listener is created on node start.
     * @throws IgniteCheckedException
     */
    public void executeJCacheQuery(CacheEntryListenerConfiguration<K, V> cfg, boolean onStart)
        throws IgniteCheckedException {
        JCacheQuery lsnr = new JCacheQuery(cfg, onStart);

        JCacheQuery old = jCacheLsnrs.putIfAbsent(cfg, lsnr);

        if (old != null)
            throw new IgniteCheckedException("Listener is already registered for configuration: " + cfg);

        try {
            lsnr.execute();
        }
        catch (IgniteCheckedException e) {
            cancelJCacheQuery(cfg);

            throw e;
        }
    }

    /**
     * @param cfg Listener configuration.
     * @throws IgniteCheckedException In case of error.
     */
    public void cancelJCacheQuery(CacheEntryListenerConfiguration<K, V> cfg) throws IgniteCheckedException {
        JCacheQuery lsnr = jCacheLsnrs.remove(cfg);

        if (lsnr != null)
            lsnr.cancel();
    }

    /**
     * @param locLsnr Local listener.
     * @param rmtFilter Remote filter.
     * @param bufSize Buffer size.
     * @param timeInterval Time interval.
     * @param autoUnsubscribe Auto unsubscribe flag.
     * @param internal Internal flag.
     * @param oldValRequired Old value required flag.
     * @param sync Synchronous flag.
     * @param ignoreExpired Ignore expired event flag.
     * @param grp Cluster group.
     * @return Continuous routine ID.
     * @throws IgniteCheckedException In case of error.
     */
    private UUID executeQuery0(CacheEntryUpdatedListener<K, V> locLsnr, CacheEntryEventFilter<K, V> rmtFilter,
        int bufSize, long timeInterval, boolean autoUnsubscribe, boolean internal, boolean oldValRequired,
        boolean sync, boolean ignoreExpired, ClusterGroup grp) throws IgniteCheckedException {
        cctx.checkSecurity(GridSecurityPermission.CACHE_READ);

        if (grp == null)
            grp = cctx.kernalContext().grid();

        Collection<ClusterNode> nodes = grp.nodes();

        if (nodes.isEmpty())
            throw new ClusterTopologyException("Failed to execute continuous query (empty cluster group is " +
                "provided).");

        boolean skipPrimaryCheck = false;

        switch (cctx.config().getCacheMode()) {
            case LOCAL:
                if (!nodes.contains(cctx.localNode()))
                    throw new ClusterTopologyException("Continuous query for LOCAL cache can be executed " +
                        "only locally (provided projection contains remote nodes only).");
                else if (nodes.size() > 1)
                    U.warn(log, "Continuous query for LOCAL cache will be executed locally (provided projection is " +
                        "ignored).");

                grp = grp.forNode(cctx.localNode());

                break;

            case REPLICATED:
                if (nodes.size() == 1 && F.first(nodes).equals(cctx.localNode())) {
                    CacheDistributionMode distributionMode = cctx.config().getDistributionMode();

                    if (distributionMode == PARTITIONED_ONLY || distributionMode == NEAR_PARTITIONED)
                        skipPrimaryCheck = true;
                }

                break;
        }

        int taskNameHash = !internal && cctx.kernalContext().security().enabled() ?
            cctx.kernalContext().job().currentTaskNameHash() : 0;

        GridContinuousHandler hnd = new CacheContinuousQueryHandler<>(
            cctx.name(),
            TOPIC_CACHE.topic(topicPrefix, cctx.localNodeId(), seq.getAndIncrement()),
            locLsnr,
            rmtFilter,
            internal,
            oldValRequired,
            sync,
            ignoreExpired,
            taskNameHash,
            skipPrimaryCheck);

        return cctx.kernalContext().continuous().startRoutine(hnd, bufSize, timeInterval,
            autoUnsubscribe, grp.predicate()).get();
    }

    /**
     * @param lsnrId Listener ID.
     * @param lsnr Listener.
     * @param internal Internal flag.
     * @return Whether listener was actually registered.
     */
    boolean registerListener(UUID lsnrId,
        CacheContinuousQueryListener<K, V> lsnr,
        boolean internal) {
        boolean added;

        if (internal) {
            added = intLsnrs.putIfAbsent(lsnrId, lsnr) == null;

            if (added)
                intLsnrCnt.incrementAndGet();
        }
        else {
            added = lsnrs.putIfAbsent(lsnrId, lsnr) == null;

            if (added) {
                lsnrCnt.incrementAndGet();

                lsnr.onExecution();
            }
        }

        return added;
    }

    /**
     * @param internal Internal flag.
     * @param id Listener ID.
     */
    void unregisterListener(boolean internal, UUID id) {
        CacheContinuousQueryListener<K, V> lsnr;

        if (internal) {
            if ((lsnr = intLsnrs.remove(id)) != null) {
                intLsnrCnt.decrementAndGet();

                lsnr.onUnregister();
            }
        }
        else {
            if ((lsnr = lsnrs.remove(id)) != null) {
                lsnrCnt.decrementAndGet();

                lsnr.onUnregister();
            }
        }
    }

    /**
     */
    private class JCacheQuery {
        /** */
        private final CacheEntryListenerConfiguration<K, V> cfg;

        /** */
        private final boolean onStart;

        /** */
        private volatile UUID routineId;

        /**
         * @param cfg Listener configuration.
         */
        private JCacheQuery(CacheEntryListenerConfiguration<K, V> cfg, boolean onStart) {
            this.cfg = cfg;
            this.onStart = onStart;
        }

        /**
         * @throws IgniteCheckedException In case of error.
         */
        @SuppressWarnings("unchecked")
        void execute() throws IgniteCheckedException {
            if (!onStart)
                cctx.config().addCacheEntryListenerConfiguration(cfg);

            CacheEntryListener<? super K, ? super V> locLsnrImpl = cfg.getCacheEntryListenerFactory().create();

            if (locLsnrImpl == null)
                throw new IgniteCheckedException("Local CacheEntryListener is mandatory and can't be null.");

            byte types = 0;

            types |= locLsnrImpl instanceof CacheEntryCreatedListener ? CREATED_FLAG : 0;
            types |= locLsnrImpl instanceof CacheEntryUpdatedListener ? UPDATED_FLAG : 0;
            types |= locLsnrImpl instanceof CacheEntryRemovedListener ? REMOVED_FLAG : 0;
            types |= locLsnrImpl instanceof CacheEntryExpiredListener ? EXPIRED_FLAG : 0;

            if (types == 0)
                throw new IgniteCheckedException("Listener must implement one of CacheEntryListener sub-interfaces.");

            CacheEntryUpdatedListener<K, V> locLsnr = (CacheEntryUpdatedListener<K, V>)new JCacheQueryLocalListener<>(
                locLsnrImpl);

            CacheEntryEventFilter<K, V> rmtFilter = (CacheEntryEventFilter<K, V>)new JCacheQueryRemoteFilter<>(
                cfg.getCacheEntryEventFilterFactory().create(), types);

            routineId = executeQuery0(
                locLsnr,
                rmtFilter,
                ContinuousQuery.DFLT_BUF_SIZE,
                ContinuousQuery.DFLT_TIME_INTERVAL,
                ContinuousQuery.DFLT_AUTO_UNSUBSCRIBE,
                false,
                cfg.isOldValueRequired(),
                cfg.isSynchronous(),
                false,
                null);
        }

        /**
         * @throws IgniteCheckedException In case of error.
         */
        @SuppressWarnings("unchecked")
        void cancel() throws IgniteCheckedException {
            UUID routineId0 = routineId;

            assert routineId0 != null;

            cctx.kernalContext().continuous().stopRoutine(routineId0).get();

            cctx.config().removeCacheEntryListenerConfiguration(cfg);
        }
    }

    /**
     */
    private static class JCacheQueryLocalListener<K, V> implements CacheEntryUpdatedListener<K, V> {
        /** */
        private CacheEntryListener<K, V> impl;

        /**
         * @param impl Listener.
         */
        private JCacheQueryLocalListener(CacheEntryListener<K, V> impl) {
            assert impl != null;

            this.impl = impl;
        }

        /** {@inheritDoc} */
        @Override public void onUpdated(Iterable<CacheEntryEvent<? extends K, ? extends V>> evts) {
            for (CacheEntryEvent<? extends K, ? extends V> evt : evts) {
                switch (evt.getEventType()) {
                    case CREATED:
                        assert impl instanceof CacheEntryCreatedListener;

                        ((CacheEntryCreatedListener<K, V>)impl).onCreated(singleton(evt));

                        break;

                    case UPDATED:
                        assert impl instanceof CacheEntryUpdatedListener;

                        ((CacheEntryUpdatedListener<K, V>)impl).onUpdated(singleton(evt));

                        break;

                    case REMOVED:
                        assert impl instanceof CacheEntryRemovedListener;

                        ((CacheEntryRemovedListener<K, V>)impl).onRemoved(singleton(evt));

                        break;

                    case EXPIRED:
                        assert impl instanceof CacheEntryExpiredListener;

                        ((CacheEntryExpiredListener<K, V>)impl).onExpired(singleton(evt));

                        break;

                    default:
                        throw new IllegalStateException("Unknown type: " + evt.getEventType());
                }
            }
        }

        /**
         * @param evt Event.
         * @return Singleton iterable.
         */
        private Iterable<CacheEntryEvent<? extends K, ? extends V>> singleton(
            CacheEntryEvent<? extends K, ? extends V> evt) {
            Collection<CacheEntryEvent<? extends K, ? extends V>> evts = new ArrayList<>(1);

            evts.add(evt);

            return evts;
        }
    }

    /**
     */
    private static class JCacheQueryRemoteFilter<K, V> implements CacheEntryEventFilter<K, V>, Externalizable {
        /** */
        private CacheEntryEventFilter<K, V> impl;

        /** */
        private byte types;

        /**
         * For {@link Externalizable}.
         */
        public JCacheQueryRemoteFilter() {
            // no-op.
        }

        /**
         * @param impl Filter.
         * @param types Types.
         */
        JCacheQueryRemoteFilter(CacheEntryEventFilter<K, V> impl, byte types) {
            assert types != 0;

            this.impl = impl;
            this.types = types;
        }

        /** {@inheritDoc} */
        @Override public boolean evaluate(CacheEntryEvent<? extends K, ? extends V> evt) {
            return (types & flag(evt.getEventType())) != 0 && (impl == null || impl.evaluate(evt));
        }

        /** {@inheritDoc} */
        @Override public void writeExternal(ObjectOutput out) throws IOException {
            out.writeObject(impl);
            out.writeByte(types);
        }

        /** {@inheritDoc} */
        @SuppressWarnings("unchecked")
        @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            impl = (CacheEntryEventFilter<K, V>)in.readObject();
            types = in.readByte();
        }

        /**
         * @param evtType Type.
         * @return Flag value.
         */
        private byte flag(EventType evtType) {
            switch (evtType) {
                case CREATED:
                    return CREATED_FLAG;

                case UPDATED:
                    return UPDATED_FLAG;

                case REMOVED:
                    return REMOVED_FLAG;

                case EXPIRED:
                    return EXPIRED_FLAG;

                default:
                    throw new IllegalStateException("Unknown type: " + evtType);
            }
        }
    }
}
