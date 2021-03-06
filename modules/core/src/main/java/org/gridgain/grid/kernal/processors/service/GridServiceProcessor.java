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

package org.gridgain.grid.kernal.processors.service;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.events.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.managed.*;
import org.apache.ignite.marshaller.*;
import org.apache.ignite.thread.*;
import org.apache.ignite.transactions.*;
import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.query.GridCacheContinuousQueryEntry;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.managers.eventstorage.*;
import org.gridgain.grid.kernal.processors.*;
import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.kernal.processors.cache.query.continuous.*;
import org.gridgain.grid.kernal.processors.timeout.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jdk8.backport.*;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.*;

import static java.util.Map.*;
import static org.apache.ignite.configuration.IgniteDeploymentMode.*;
import static org.apache.ignite.events.IgniteEventType.*;
import static org.apache.ignite.transactions.IgniteTxConcurrency.*;
import static org.apache.ignite.transactions.IgniteTxIsolation.*;
import static org.gridgain.grid.kernal.processors.cache.GridCacheUtils.*;

/**
 * Grid service processor.
 */
@SuppressWarnings({"SynchronizationOnLocalVariableOrMethodParameter", "ConstantConditions"})
public class GridServiceProcessor extends GridProcessorAdapter {
    /** Time to wait before reassignment retries. */
    private static final long RETRY_TIMEOUT = 1000;

    /** Local service instances. */
    private final Map<String, Collection<ManagedServiceContextImpl>> locSvcs = new HashMap<>();

    /** Deployment futures. */
    private final ConcurrentMap<String, GridServiceDeploymentFuture> depFuts = new ConcurrentHashMap8<>();

    /** Deployment futures. */
    private final ConcurrentMap<String, GridFutureAdapter<?>> undepFuts = new ConcurrentHashMap8<>();

    /** Deployment executor service. */
    private final ExecutorService depExe = Executors.newSingleThreadExecutor();

    /** Busy lock. */
    private final GridSpinBusyLock busyLock = new GridSpinBusyLock();

    /** Thread factory. */
    private ThreadFactory threadFactory = new IgniteThreadFactory(ctx.gridName());

    /** Thread local for service name. */
    private ThreadLocal<String> svcName = new ThreadLocal<>();

    /** Service cache. */
    private GridCacheProjectionEx<Object, Object> cache;

    /** Topology listener. */
    private GridLocalEventListener topLsnr = new TopologyListener();

    /** Deployment listener. */
    private GridCacheContinuousQueryAdapter<Object, Object> cfgQry;

    /** Assignment listener. */
    private GridCacheContinuousQueryAdapter<Object, Object> assignQry;

    /**
     * @param ctx Kernal context.
     */
    public GridServiceProcessor(GridKernalContext ctx) {
        super(ctx);
    }

    /** {@inheritDoc} */
    @Override public void start() throws IgniteCheckedException {
        if (ctx.isDaemon())
            return;

        IgniteConfiguration cfg = ctx.config();

        IgniteDeploymentMode depMode = cfg.getDeploymentMode();

        if (cfg.isPeerClassLoadingEnabled() && (depMode == PRIVATE || depMode == ISOLATED) &&
            !F.isEmpty(cfg.getServiceConfiguration()))
            throw new IgniteCheckedException("Cannot deploy services in PRIVATE or ISOLATED deployment mode: " + depMode);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public void onKernalStart() throws IgniteCheckedException {
        if (ctx.isDaemon())
            return;

        cache = (GridCacheProjectionEx<Object, Object>)ctx.cache().utilityCache();

        ctx.event().addLocalEventListener(topLsnr, EVTS_DISCOVERY);

        try {
            if (ctx.deploy().enabled())
                ctx.cache().context().deploy().ignoreOwnership(true);

            cfgQry = (GridCacheContinuousQueryAdapter<Object, Object>)cache.queries().createContinuousQuery();

            cfgQry.localCallback(new DeploymentListener());

            cfgQry.execute(ctx.grid().forLocal(), true);

            assignQry = (GridCacheContinuousQueryAdapter<Object, Object>)cache.queries().createContinuousQuery();

            assignQry.localCallback(new AssignmentListener());

            assignQry.execute(ctx.grid().forLocal(), true);
        }
        finally {
            if (ctx.deploy().enabled())
                ctx.cache().context().deploy().ignoreOwnership(false);
        }

        ManagedServiceConfiguration[] cfgs = ctx.config().getServiceConfiguration();

        if (cfgs != null) {
            Collection<IgniteFuture<?>> futs = new ArrayList<>();

            for (ManagedServiceConfiguration c : ctx.config().getServiceConfiguration())
                futs.add(deploy(c));

            // Await for services to deploy.
            for (IgniteFuture<?> f : futs)
                f.get();
        }

        if (log.isDebugEnabled())
            log.debug("Started service processor.");
    }

    /** {@inheritDoc} */
    @Override public void onKernalStop(boolean cancel) {
        if (ctx.isDaemon())
            return;

        busyLock.block();

        ctx.event().removeLocalEventListener(topLsnr);

        try {
            if (cfgQry != null)
                cfgQry.close();
        }
        catch (IgniteCheckedException e) {
            log.error("Failed to unsubscribe service configuration notifications.", e);
        }

        try {
            if (assignQry != null)
                assignQry.close();
        }
        catch (IgniteCheckedException e) {
            log.error("Failed to unsubscribe service assignment notifications.", e);
        }

        Collection<ManagedServiceContextImpl> ctxs = new ArrayList<>();

        synchronized (locSvcs) {
            for (Collection<ManagedServiceContextImpl> ctxs0 : locSvcs.values())
                ctxs.addAll(ctxs0);
        }

        for (ManagedServiceContextImpl ctx : ctxs) {
            ctx.setCancelled(true);
            ctx.service().cancel(ctx);

            ctx.executor().shutdownNow();
        }

        for (ManagedServiceContextImpl ctx : ctxs) {
            try {
                if (log.isInfoEnabled() && !ctxs.isEmpty())
                    log.info("Shutting down distributed service [name=" + ctx.name() + ", execId8=" +
                        U.id8(ctx.executionId()) + ']');

                ctx.executor().awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();

                U.error(log, "Got interrupted while waiting for service to shutdown (will continue stopping node): " +
                    ctx.name());
            }
        }

        U.shutdownNow(GridServiceProcessor.class, depExe, log);

        if (log.isDebugEnabled())
            log.debug("Stopped service processor.");
    }

    /**
     * Validates service configuration.
     *
     * @param c Service configuration.
     * @throws IgniteException If validation failed.
     */
    private void validate(ManagedServiceConfiguration c) throws IgniteException {
        IgniteConfiguration cfg = ctx.config();

        IgniteDeploymentMode depMode = cfg.getDeploymentMode();

        if (cfg.isPeerClassLoadingEnabled() && (depMode == PRIVATE || depMode == ISOLATED))
            throw new IgniteException("Cannot deploy services in PRIVATE or ISOLATED deployment mode: " + depMode);

        ensure(c.getName() != null, "getName() != null", null);
        ensure(c.getTotalCount() >= 0, "getTotalCount() >= 0", c.getTotalCount());
        ensure(c.getMaxPerNodeCount() >= 0, "getMaxPerNodeCount() >= 0", c.getMaxPerNodeCount());
        ensure(c.getService() != null, "getService() != null", c.getService());
        ensure(c.getTotalCount() > 0 || c.getMaxPerNodeCount() > 0,
            "c.getTotalCount() > 0 || c.getMaxPerNodeCount() > 0", null);
    }

    /**
     * @param cond Condition.
     * @param desc Description.
     * @param v Value.
     */
    private void ensure(boolean cond, String desc, @Nullable Object v) {
        if (!cond)
            if (v != null)
                throw new IgniteException("Service configuration check failed (" + desc + "): " + v);
            else
                throw new IgniteException("Service configuration check failed (" + desc + ")");
    }

    /**
     * @param name Service name.
     * @param svc Service.
     * @return Future.
     */
    public IgniteFuture<?> deployNodeSingleton(ClusterGroup prj, String name, ManagedService svc) {
        return deployMultiple(prj, name, svc, 0, 1);
    }

    /**
     * @param name Service name.
     * @param svc Service.
     * @return Future.
     */
    public IgniteFuture<?> deployClusterSingleton(ClusterGroup prj, String name, ManagedService svc) {
        return deployMultiple(prj, name, svc, 1, 1);
    }

    /**
     * @param name Service name.
     * @param svc Service.
     * @param totalCnt Total count.
     * @param maxPerNodeCnt Max per-node count.
     * @return Future.
     */
    public IgniteFuture<?> deployMultiple(ClusterGroup prj, String name, ManagedService svc, int totalCnt,
        int maxPerNodeCnt) {
        ManagedServiceConfiguration cfg = new ManagedServiceConfiguration();

        cfg.setName(name);
        cfg.setService(svc);
        cfg.setTotalCount(totalCnt);
        cfg.setMaxPerNodeCount(maxPerNodeCnt);
        cfg.setNodeFilter(F.<ClusterNode>alwaysTrue() == prj.predicate() ? null : prj.predicate());

        return deploy(cfg);
    }

    /**
     * @param name Service name.
     * @param svc Service.
     * @param cacheName Cache name.
     * @param  affKey Affinity key.
     * @return Future.
     */
    public IgniteFuture<?> deployKeyAffinitySingleton(String name, ManagedService svc, String cacheName, Object affKey) {
        A.notNull(affKey, "affKey");

        ManagedServiceConfiguration cfg = new ManagedServiceConfiguration();

        cfg.setName(name);
        cfg.setService(svc);
        cfg.setCacheName(cacheName);
        cfg.setAffinityKey(affKey);
        cfg.setTotalCount(1);
        cfg.setMaxPerNodeCount(1);

        return deploy(cfg);
    }

    /**
     * @param cfg Service configuration.
     * @return Future for deployment.
     */
    public IgniteFuture<?> deploy(ManagedServiceConfiguration cfg) {
        A.notNull(cfg, "cfg");

        validate(cfg);

        GridServiceDeploymentFuture fut = new GridServiceDeploymentFuture(ctx, cfg);

        GridServiceDeploymentFuture old = depFuts.putIfAbsent(cfg.getName(), fut);

        if (old != null) {
            if (!old.configuration().equalsIgnoreNodeFilter(cfg)) {
                fut.onDone(new IgniteCheckedException("Failed to deploy service (service already exists with " +
                    "different configuration) [deployed=" + old.configuration() + ", new=" + cfg + ']'));

                return fut;
            }

            return old;
        }

        while (true) {
            try {
                GridServiceDeploymentKey key = new GridServiceDeploymentKey(cfg.getName());

                if (ctx.deploy().enabled())
                    ctx.cache().context().deploy().ignoreOwnership(true);

                try {
                    GridServiceDeployment dep = (GridServiceDeployment)cache.putIfAbsent(key,
                        new GridServiceDeployment(ctx.localNodeId(), cfg));

                    if (dep != null) {
                        if (!dep.configuration().equalsIgnoreNodeFilter(cfg)) {
                            // Remove future from local map.
                            depFuts.remove(cfg.getName(), fut);

                            fut.onDone(new IgniteCheckedException("Failed to deploy service (service already exists with " +
                                "different configuration) [deployed=" + dep.configuration() + ", new=" + cfg + ']'));
                        }
                        else {
                            for (GridCacheEntry<Object, Object> e : cache.entrySetx()) {
                                if (e.getKey() instanceof GridServiceAssignmentsKey) {
                                    GridServiceAssignments assigns = (GridServiceAssignments)e.getValue();

                                    if (assigns.name().equals(cfg.getName())) {
                                        // Remove future from local map.
                                        depFuts.remove(cfg.getName(), fut);

                                        fut.onDone();

                                        break;
                                    }
                                }
                            }

                            if (!dep.configuration().equalsIgnoreNodeFilter(cfg))
                                U.warn(log, "Service already deployed with different configuration (will ignore) " +
                                    "[deployed=" + dep.configuration() + ", new=" + cfg + ']');
                        }
                    }
                }
                finally {
                    if (ctx.deploy().enabled())
                        ctx.cache().context().deploy().ignoreOwnership(false);
                }

                return fut;
            }
            catch (ClusterTopologyException e) {
                if (log.isDebugEnabled())
                    log.debug("Topology changed while deploying service (will retry): " + e.getMessage());
            }
            catch (IgniteCheckedException e) {
                if (e.hasCause(ClusterTopologyException.class)) {
                    if (log.isDebugEnabled())
                        log.debug("Topology changed while deploying service (will retry): " + e.getMessage());

                    continue;
                }

                U.error(log, "Failed to deploy service: " + cfg.getName(), e);

                return new GridFinishedFuture<>(ctx, e);
            }
        }
    }

    /**
     * @param name Service name.
     * @return Future.
     */
    public IgniteFuture<?> cancel(String name) {
        while (true) {
            try {
                GridFutureAdapter<?> fut = new GridFutureAdapter<>(ctx);

                GridFutureAdapter<?> old;

                if ((old = undepFuts.putIfAbsent(name, fut)) != null)
                    fut = old;
                else {
                    GridServiceDeploymentKey key = new GridServiceDeploymentKey(name);

                    if (cache.remove(key) == null) {
                        // Remove future from local map if service was not deployed.
                        undepFuts.remove(name);

                        fut.onDone();
                    }
                }

                return fut;
            }
            catch (ClusterTopologyException e) {
                if (log.isDebugEnabled())
                    log.debug("Topology changed while deploying service (will retry): " + e.getMessage());
            }
            catch (IgniteCheckedException e) {
                log.error("Failed to undeploy service: " + name, e);

                return new GridFinishedFuture<>(ctx, e);
            }
        }
    }

    /**
     * @return Future.
     */
    @SuppressWarnings("unchecked")
    public IgniteFuture<?> cancelAll() {
        Collection<IgniteFuture<?>> futs = new ArrayList<>();

        for (GridCacheEntry<Object, Object> e : cache.entrySetx()) {
            if (!(e.getKey() instanceof GridServiceDeploymentKey))
                continue;

            GridServiceDeployment dep = (GridServiceDeployment)e.getValue();

            // Cancel each service separately.
            futs.add(cancel(dep.configuration().getName()));
        }

        return futs.isEmpty() ? new GridFinishedFuture<>(ctx) : new GridCompoundFuture(ctx, null, futs);
    }

    /**
     * @return Collection of service descriptors.
     */
    public Collection<ManagedServiceDescriptor> deployedServices() {
        Collection<ManagedServiceDescriptor> descs = new ArrayList<>();

        for (GridCacheEntry<Object, Object> e : cache.entrySetx()) {
            if (!(e.getKey() instanceof GridServiceDeploymentKey))
                continue;

            GridServiceDeployment dep = (GridServiceDeployment)e.getValue();

            ManagedServiceDescriptorImpl desc = new ManagedServiceDescriptorImpl(dep);

            try {
                GridServiceAssignments assigns = (GridServiceAssignments)cache.//flagsOn(GridCacheFlag.GET_PRIMARY).
                    get(new GridServiceAssignmentsKey(dep.configuration().getName()));

                if (assigns != null) {
                    desc.topologySnapshot(assigns.assigns());

                    descs.add(desc);
                }
            }
            catch (IgniteCheckedException ex) {
                log.error("Failed to get assignments from replicated cache for service: " +
                    dep.configuration().getName(), ex);
            }
        }

        return descs;
    }

    /**
     * @param name Service name.
     * @param <T> Service type.
     * @return Service by specified service name.
     */
    @SuppressWarnings("unchecked")
    public <T> T service(String name) {
        Collection<ManagedServiceContextImpl> ctxs;

        synchronized (locSvcs) {
            ctxs = locSvcs.get(name);
        }

        if (ctxs == null)
            return null;

        synchronized (ctxs) {
            if (ctxs.isEmpty())
                return null;

            return (T)ctxs.iterator().next().service();
        }
    }

    /**
     * @param name Service name.
     * @return Service by specified service name.
     */
    public ManagedServiceContextImpl serviceContext(String name) {
        Collection<ManagedServiceContextImpl> ctxs;

        synchronized (locSvcs) {
            ctxs = locSvcs.get(name);
        }

        if (ctxs == null)
            return null;

        synchronized (ctxs) {
            if (ctxs.isEmpty())
                return null;

            return ctxs.iterator().next();
        }
    }

    /**
     * @param prj Grid projection.
     * @param name Service name.
     * @param svcItf Service class.
     * @param sticky Whether multi-node request should be done.
     * @param <T> Service interface type.
     * @return The proxy of a service by its name and class.
     */
    @SuppressWarnings("unchecked")
    public <T> T serviceProxy(ClusterGroup prj, String name, Class<? super T> svcItf, boolean sticky)
        throws IgniteException {

        if (hasLocalNode(prj)) {
            ManagedServiceContextImpl ctx = serviceContext(name);

            if (ctx != null) {
                if (!svcItf.isAssignableFrom(ctx.service().getClass()))
                    throw new IgniteException("Service does not implement specified interface [svcItf=" +
                        svcItf.getSimpleName() + ", svcCls=" + ctx.service().getClass() + ']');

                return (T)ctx.service();
            }
        }

        return new GridServiceProxy<>(prj, name, svcItf, sticky, ctx).proxy();
    }

    /**
     * @param prj Grid nodes projection.
     * @return Whether given projection contains any local node.
     */
    private boolean hasLocalNode(ClusterGroup prj) {
        for (ClusterNode n : prj.nodes()) {
            if (n.isLocal())
                return true;
        }

        return false;
    }

    /**
     * @param name Service name.
     * @param <T> Service type.
     * @return Services by specified service name.
     */
    @SuppressWarnings("unchecked")
    public <T> Collection<T> services(String name) {
        Collection<ManagedServiceContextImpl> ctxs;

        synchronized (locSvcs) {
             ctxs = locSvcs.get(name);
        }

        if (ctxs == null)
            return null;

        synchronized (ctxs) {
            Collection<T> res = new ArrayList<>(ctxs.size());

            for (ManagedServiceContextImpl ctx : ctxs)
                res.add((T)ctx.service());

            return res;
        }
    }

    /**
     * Reassigns service to nodes.
     *
     * @param dep Service deployment.
     * @param topVer Topology version.
     * @throws IgniteCheckedException If failed.
     */
    private void reassign(GridServiceDeployment dep, long topVer) throws IgniteCheckedException {
        ManagedServiceConfiguration cfg = dep.configuration();

        int totalCnt = cfg.getTotalCount();
        int maxPerNodeCnt = cfg.getMaxPerNodeCount();
        String cacheName = cfg.getCacheName();
        Object affKey = cfg.getAffinityKey();

        while (true) {
            try (IgniteTx tx = cache.txStart(PESSIMISTIC, REPEATABLE_READ)) {
                GridServiceAssignmentsKey key = new GridServiceAssignmentsKey(cfg.getName());

                GridServiceAssignments oldAssigns = (GridServiceAssignments)cache.get(key);

                GridServiceAssignments assigns = new GridServiceAssignments(cfg, dep.nodeId(), topVer);

                Map<UUID, Integer> cnts = new HashMap<>();

                if (affKey != null) {
                    ClusterNode n = ctx.affinity().mapKeyToNode(cacheName, affKey, topVer);

                    if (n != null) {
                        int cnt = maxPerNodeCnt == 0 ? totalCnt == 0 ? 1 : totalCnt : maxPerNodeCnt;

                        cnts.put(n.id(), cnt);
                    }
                }
                else {
                    Collection<ClusterNode> nodes =
                        assigns.nodeFilter() == null ?
                            ctx.discovery().nodes(topVer) :
                            F.view(ctx.discovery().nodes(topVer), assigns.nodeFilter());

                    if (!nodes.isEmpty()) {
                        int size = nodes.size();

                        int perNodeCnt = totalCnt != 0 ? totalCnt / size : maxPerNodeCnt;
                        int remainder = totalCnt != 0 ? totalCnt % size : 0;

                        if (perNodeCnt > maxPerNodeCnt && maxPerNodeCnt != 0) {
                            perNodeCnt = maxPerNodeCnt;
                            remainder = 0;
                        }

                        for (ClusterNode n : nodes)
                            cnts.put(n.id(), perNodeCnt);

                        assert perNodeCnt >= 0;
                        assert remainder >= 0;

                        if (remainder > 0) {
                            int cnt = perNodeCnt + 1;

                            if (oldAssigns != null) {
                                Collection<UUID> used = new HashSet<>();

                                // Avoid redundant moving of services.
                                for (Entry<UUID, Integer> e : oldAssigns.assigns().entrySet()) {
                                    // Do not assign services to left nodes.
                                    if (ctx.discovery().node(e.getKey()) == null)
                                        continue;

                                    // If old count and new count match, then reuse the assignment.
                                    if (e.getValue() == cnt) {
                                        cnts.put(e.getKey(), cnt);

                                        used.add(e.getKey());

                                        if (--remainder == 0)
                                            break;
                                    }
                                }

                                if (remainder > 0) {
                                    List<Entry<UUID, Integer>> entries = new ArrayList<>(cnts.entrySet());

                                    // Randomize.
                                    Collections.shuffle(entries);

                                    for (Entry<UUID, Integer> e : entries) {
                                        // Assign only the ones that have not been reused from previous assignments.
                                        if (!used.contains(e.getKey())) {
                                            if (e.getValue() < maxPerNodeCnt) {
                                                e.setValue(e.getValue() + 1);

                                                if (--remainder == 0)
                                                    break;
                                            }
                                        }
                                    }
                                }
                            }
                            else {
                                List<Entry<UUID, Integer>> entries = new ArrayList<>(cnts.entrySet());

                                // Randomize.
                                Collections.shuffle(entries);

                                for (Entry<UUID, Integer> e : entries) {
                                    e.setValue(e.getValue() + 1);

                                    if (--remainder == 0)
                                        break;
                                }
                            }
                        }
                    }
                }

                assigns.assigns(cnts);

                cache.put(key, assigns);

                tx.commit();

                break;
            }
            catch (ClusterTopologyException e) {
                if (log.isDebugEnabled())
                    log.debug("Topology changed while reassigning (will retry): " + e.getMessage());

                U.sleep(10);
            }
        }
    }

    /**
     * Redeploys local services based on assignments.
     *
     * @param assigns Assignments.
     */
    private void redeploy(GridServiceAssignments assigns) {
        String svcName = assigns.name();

        Integer assignCnt = assigns.assigns().get(ctx.localNodeId());

        if (assignCnt == null)
            assignCnt = 0;

        ManagedService svc = assigns.service();

        Collection<ManagedServiceContextImpl> ctxs;

        synchronized (locSvcs) {
            ctxs = locSvcs.get(svcName);

            if (ctxs == null)
                locSvcs.put(svcName, ctxs = new ArrayList<>());
        }

        synchronized (ctxs) {
            if (ctxs.size() > assignCnt) {
                int cancelCnt = ctxs.size() - assignCnt;

                cancel(ctxs, cancelCnt);
            }
            else if (ctxs.size() < assignCnt) {
                int createCnt = assignCnt - ctxs.size();

                for (int i = 0; i < createCnt; i++) {
                    final ManagedService cp = copyAndInject(svc);

                    final ExecutorService exe = Executors.newSingleThreadExecutor(threadFactory);

                    final ManagedServiceContextImpl svcCtx = new ManagedServiceContextImpl(assigns.name(),
                        UUID.randomUUID(), assigns.cacheName(), assigns.affinityKey(), cp, exe);

                    ctxs.add(svcCtx);

                    try {
                        // Initialize service.
                        cp.init(svcCtx);
                    }
                    catch (Throwable e) {
                        log.error("Failed to initialize service (service will not be deployed): " + assigns.name(), e);

                        ctxs.remove(svcCtx);

                        if (e instanceof Error)
                            throw (Error)e;

                        if (e instanceof RuntimeException)
                            throw (RuntimeException)e;

                        return;
                    }

                    if (log.isInfoEnabled())
                        log.info("Starting service instance [name=" + svcCtx.name() + ", execId=" +
                            svcCtx.executionId() + ']');

                    // Start service in its own thread.
                    exe.submit(new Runnable() {
                        @Override public void run() {
                            try {
                                cp.execute(svcCtx);
                            }
                            catch (InterruptedException | GridInterruptedException ignore) {
                                if (log.isDebugEnabled())
                                    log.debug("Service thread was interrupted [name=" + svcCtx.name() + ", execId=" +
                                        svcCtx.executionId() + ']');
                            }
                            catch (IgniteException e) {
                                if (e.hasCause(InterruptedException.class) ||
                                    e.hasCause(GridInterruptedException.class)) {
                                    if (log.isDebugEnabled())
                                        log.debug("Service thread was interrupted [name=" + svcCtx.name() +
                                            ", execId=" + svcCtx.executionId() + ']');
                                }
                                else {
                                    U.error(log, "Service execution stopped with error [name=" + svcCtx.name() +
                                        ", execId=" + svcCtx.executionId() + ']', e);
                                }
                            }
                            catch (Throwable e) {
                                log.error("Service execution stopped with error [name=" + svcCtx.name() +
                                    ", execId=" + svcCtx.executionId() + ']', e);
                            }
                            finally {
                                // Suicide.
                                exe.shutdownNow();

                                try {
                                    ctx.resource().cleanup(cp);
                                }
                                catch (IgniteCheckedException e) {
                                    log.error("Failed to clean up service (will ignore): " + svcCtx.name(), e);
                                }
                            }
                        }
                    });
                }
            }
        }
    }

    /**
     * @param svc Service.
     * @return Copy of service.
     */
    private ManagedService copyAndInject(ManagedService svc) {
        IgniteMarshaller m = ctx.config().getMarshaller();

        try {
            byte[] bytes = m.marshal(svc);

            ManagedService cp = m.unmarshal(bytes, svc.getClass().getClassLoader());

            ctx.resource().inject(cp);

            return cp;
        }
        catch (IgniteCheckedException e) {
            log.error("Failed to copy service (will reuse same instance): " + svc.getClass(), e);

            return svc;
        }
    }

    /**
     * @param ctxs Contexts to cancel.
     * @param cancelCnt Number of contexts to cancel.
     */
    private void cancel(Iterable<ManagedServiceContextImpl> ctxs, int cancelCnt) {
        for (Iterator<ManagedServiceContextImpl> it = ctxs.iterator(); it.hasNext();) {
            ManagedServiceContextImpl ctx = it.next();

            // Flip cancelled flag.
            ctx.setCancelled(true);

            // Notify service about cancellation.
            try {
                ctx.service().cancel(ctx);
            }
            catch (Throwable e) {
                log.error("Failed to cancel service (ignoring) [name=" + ctx.name() +
                    ", execId=" + ctx.executionId() + ']', e);
            }

            // Close out executor thread for the service.
            // This will cause the thread to be interrupted.
            ctx.executor().shutdownNow();

            it.remove();

            if (log.isInfoEnabled())
                log.info("Cancelled service instance [name=" + ctx.name() + ", execId=" +
                    ctx.executionId() + ']');

            if (--cancelCnt == 0)
                break;
        }
    }

    /**
     * Service deployment listener.
     */
    private class DeploymentListener
        implements IgniteBiPredicate<UUID, Collection<GridCacheContinuousQueryEntry<Object, Object>>> {
        /** Serial version ID. */
        private static final long serialVersionUID = 0L;

        /** {@inheritDoc} */
        @Override public boolean apply(
            UUID nodeId,
            final Collection<GridCacheContinuousQueryEntry<Object, Object>> deps) {
            depExe.submit(new BusyRunnable() {
                @Override public void run0() {
                    for (Entry<Object, Object> e : deps) {
                        if (!(e.getKey() instanceof GridServiceDeploymentKey))
                            continue;

                        GridServiceDeployment dep = (GridServiceDeployment)e.getValue();

                        if (dep != null) {
                            svcName.set(dep.configuration().getName());

                            // Ignore other utility cache events.
                            long topVer = ctx.discovery().topologyVersion();

                            ClusterNode oldest = U.oldest(ctx.discovery().nodes(topVer), null);

                            if (oldest.isLocal())
                                onDeployment(dep, topVer);
                        }
                        // Handle undeployment.
                        else {
                            String name = ((GridServiceDeploymentKey)e.getKey()).name();

                            svcName.set(name);

                            Collection<ManagedServiceContextImpl> ctxs;

                            synchronized (locSvcs) {
                                ctxs = locSvcs.remove(name);
                            }

                            if (ctxs != null) {
                                synchronized (ctxs) {
                                    cancel(ctxs, ctxs.size());
                                }
                            }

                            // Finish deployment futures if undeployment happened.
                            GridFutureAdapter<?> fut = depFuts.remove(name);

                            if (fut != null)
                                fut.onDone();

                            // Complete undeployment future.
                            fut = undepFuts.remove(name);

                            if (fut != null)
                                fut.onDone();

                            GridServiceAssignmentsKey key = new GridServiceAssignmentsKey(name);

                            // Remove assignment on primary node in case of undeploy.
                            if (cache.cache().affinity().isPrimary(ctx.discovery().localNode(), key)) {
                                try {
                                    cache.remove(key);
                                }
                                catch (IgniteCheckedException ex) {
                                    log.error("Failed to remove assignments for undeployed service: " + name, ex);
                                }
                            }
                        }
                    }
                }
            });

            return true;
        }

        /**
         * Deployment callback.
         *
         * @param dep Service deployment.
         * @param topVer Topology version.
         */
        private void onDeployment(final GridServiceDeployment dep, final long topVer) {
            // Retry forever.
            try {
                long newTopVer = ctx.discovery().topologyVersion();

                // If topology version changed, reassignment will happen from topology event.
                if (newTopVer == topVer)
                    reassign(dep, topVer);
            }
            catch (IgniteCheckedException e) {
                if (!(e instanceof ClusterTopologyException))
                    log.error("Failed to do service reassignment (will retry): " + dep.configuration().getName(), e);

                long newTopVer = ctx.discovery().topologyVersion();

                if (newTopVer != topVer) {
                    assert newTopVer > topVer;

                    // Reassignment will happen from topology event.
                    return;
                }

                ctx.timeout().addTimeoutObject(new GridTimeoutObject() {
                    private IgniteUuid id = IgniteUuid.randomUuid();

                    private long start = System.currentTimeMillis();

                    @Override public IgniteUuid timeoutId() {
                        return id;
                    }

                    @Override public long endTime() {
                        return start + RETRY_TIMEOUT;
                    }

                    @Override public void onTimeout() {
                        if (!busyLock.enterBusy())
                            return;

                        try {
                            // Try again.
                            onDeployment(dep, topVer);
                        }
                        finally {
                            busyLock.leaveBusy();
                        }
                    }
                });
            }
        }
    }

    /**
     * Topology listener.
     */
    private class TopologyListener implements GridLocalEventListener {
        /** {@inheritDoc} */
        @Override public void onEvent(final IgniteEvent evt) {
            if (!busyLock.enterBusy())
                return;

            try {
                depExe.submit(new BusyRunnable() {
                    @Override public void run0() {
                        long topVer = ((IgniteDiscoveryEvent)evt).topologyVersion();

                        ClusterNode oldest = U.oldest(ctx.discovery().nodes(topVer), null);

                        if (oldest.isLocal()) {
                            final Collection<GridServiceDeployment> retries = new ConcurrentLinkedQueue<>();

                            if (ctx.deploy().enabled())
                                ctx.cache().context().deploy().ignoreOwnership(true);

                            try {
                                for (GridCacheEntry<Object, Object> e : cache.entrySetx()) {
                                    if (!(e.getKey() instanceof GridServiceDeploymentKey))
                                        continue;

                                    GridServiceDeployment dep = (GridServiceDeployment)e.getValue();

                                    try {
                                        svcName.set(dep.configuration().getName());

                                        ctx.cache().internalCache(UTILITY_CACHE_NAME).context().affinity().
                                            affinityReadyFuture(topVer).get();

                                        reassign(dep, topVer);
                                    }
                                    catch (IgniteCheckedException ex) {
                                        if (!(e instanceof ClusterTopologyException))
                                            LT.error(log, ex, "Failed to do service reassignment (will retry): " +
                                                dep.configuration().getName());

                                        retries.add(dep);
                                    }
                                }
                            }
                            finally {
                                if (ctx.deploy().enabled())
                                    ctx.cache().context().deploy().ignoreOwnership(false);
                            }

                            if (!retries.isEmpty())
                                onReassignmentFailed(topVer, retries);
                        }

                        // Clean up zombie assignments.
                        for (GridCacheEntry<Object, Object> e : cache.primaryEntrySetx()) {
                            if (!(e.getKey() instanceof GridServiceAssignmentsKey))
                                continue;

                            String name = ((GridServiceAssignmentsKey)e.getKey()).name();

                            try {
                                if (cache.get(new GridServiceDeploymentKey(name)) == null) {
                                    if (log.isDebugEnabled())
                                        log.debug("Removed zombie assignments: " + e.getValue());

                                    cache.remove(e.getKey());
                                }
                            }
                            catch (IgniteCheckedException ex) {
                                log.error("Failed to clean up zombie assignments for service: " + name, ex);
                            }
                        }
                    }
                });
            }
            finally {
                busyLock.leaveBusy();
            }
        }

        /**
         * Handler for reassignment failures.
         *
         * @param topVer Topology version.
         * @param retries Retries.
         */
        private void onReassignmentFailed(final long topVer, final Collection<GridServiceDeployment> retries) {
            if (!busyLock.enterBusy())
                return;

            try {
                // If topology changed again, let next event handle it.
                if (ctx.discovery().topologyVersion() != topVer)
                    return;

                for (Iterator<GridServiceDeployment> it = retries.iterator(); it.hasNext(); ) {
                    GridServiceDeployment dep = it.next();

                    try {
                        svcName.set(dep.configuration().getName());

                        reassign(dep, topVer);

                        it.remove();
                    }
                    catch (IgniteCheckedException e) {
                        if (!(e instanceof ClusterTopologyException))
                            LT.error(log, e, "Failed to do service reassignment (will retry): " +
                                dep.configuration().getName());
                    }
                }

                if (!retries.isEmpty()) {
                    ctx.timeout().addTimeoutObject(new GridTimeoutObject() {
                        private IgniteUuid id = IgniteUuid.randomUuid();

                        private long start = System.currentTimeMillis();

                        @Override public IgniteUuid timeoutId() {
                            return id;
                        }

                        @Override public long endTime() {
                            return start + RETRY_TIMEOUT;
                        }

                        @Override public void onTimeout() {
                            onReassignmentFailed(topVer, retries);
                        }
                    });
                }
            }
            finally {
                busyLock.leaveBusy();
            }
        }
    }

    /**
     * Assignment listener.
     */
    private class AssignmentListener
        implements IgniteBiPredicate<UUID, Collection<GridCacheContinuousQueryEntry<Object, Object>>> {
        /** Serial version ID. */
        private static final long serialVersionUID = 0L;

        /** {@inheritDoc} */
        @Override public boolean apply(
            UUID nodeId,
            final Collection<GridCacheContinuousQueryEntry<Object, Object>> assignCol) {
            depExe.submit(new BusyRunnable() {
                @Override public void run0() {
                    for (Entry<Object, Object> e : assignCol) {
                        if (!(e.getKey() instanceof GridServiceAssignmentsKey))
                            continue;

                        GridServiceAssignments assigns = (GridServiceAssignments)e.getValue();

                        if (assigns != null) {
                            svcName.set(assigns.name());

                            Throwable t = null;

                            try {
                                redeploy(assigns);
                            }
                            catch (Error | RuntimeException th) {
                                t = th;
                            }

                            GridServiceDeploymentFuture fut = depFuts.get(assigns.name());

                            if (fut != null && fut.configuration().equalsIgnoreNodeFilter(assigns.configuration())) {
                                depFuts.remove(assigns.name(), fut);

                                // Complete deployment futures once the assignments have been stored in cache.
                                fut.onDone(null, t);
                            }
                        }
                        // Handle undeployment.
                        else {
                            String name = ((GridServiceAssignmentsKey)e.getKey()).name();

                            svcName.set(name);

                            Collection<ManagedServiceContextImpl> ctxs;

                            synchronized (locSvcs) {
                                ctxs = locSvcs.remove(name);
                            }

                            if (ctxs != null) {
                                synchronized (ctxs) {
                                    cancel(ctxs, ctxs.size());
                                }
                            }
                        }
                    }
                }
            });

            return true;
        }
    }

    /**
     *
     */
    private abstract class BusyRunnable implements Runnable {
        /** {@inheritDoc} */
        @Override public void run() {
            if (!busyLock.enterBusy())
                return;

            svcName.set(null);

            try {
                run0();
            }
            catch (Throwable t) {
                log.error("Error when executing service: " + svcName.get(), t);
            }
            finally {
                busyLock.leaveBusy();

                svcName.set(null);
            }
        }

        /**
         * Abstract run method protected by busy lock.
         */
        public abstract void run0();
    }
}
