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

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.compute.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.resources.*;
import org.apache.ignite.transactions.*;
import org.jetbrains.annotations.*;

import java.util.*;

import static org.apache.ignite.transactions.IgniteTxConcurrency.*;
import static org.apache.ignite.transactions.IgniteTxIsolation.*;

/**
 * Puts all the passed data into partitioned cache in small chunks.
 */
class GridCacheGroupLockPutTask extends ComputeTaskAdapter<Collection<Integer>, Void> {
    /** Preferred node. */
    private final UUID preferredNode;

    /** Cache name. */
    private final String cacheName;

    /** Optimistic transaction flag. */
    private final boolean optimistic;

    /**
     *
     * @param preferredNode A node that we'd prefer to take from grid.
     * @param cacheName A name of the cache to work with.
     * @param optimistic Optimistic transaction flag.
     */
    GridCacheGroupLockPutTask(UUID preferredNode, String cacheName, boolean optimistic) {
        this.preferredNode = preferredNode;
        this.cacheName = cacheName;
        this.optimistic = optimistic;
    }

    /**
     * This method is called to map or split grid task into multiple grid jobs. This is the first method that gets called
     * when task execution starts.
     *
     * @param data     Task execution argument. Can be {@code null}. This is the same argument as the one passed into {@code
     *                Grid#execute(...)} methods.
     * @param subgrid Nodes available for this task execution. Note that order of nodes is guaranteed to be randomized by
     *                container. This ensures that every time you simply iterate through grid nodes, the order of nodes
     *                will be random which over time should result into all nodes being used equally.
     * @return Map of grid jobs assigned to subgrid node. Unless {@link org.apache.ignite.compute.ComputeTaskContinuousMapper} is injected into task, if
     *         {@code null} or empty map is returned, exception will be thrown.
     * @throws IgniteCheckedException If mapping could not complete successfully. This exception will be thrown out of {@link
     *                       org.apache.ignite.compute.ComputeTaskFuture#get()} method.
     */
    @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid,
        @Nullable final Collection<Integer> data) {
        assert !subgrid.isEmpty();

        // Give preference to wanted node. Otherwise, take the first one.
        ClusterNode targetNode = F.find(subgrid, subgrid.get(0), new IgnitePredicate<ClusterNode>() {
            @Override public boolean apply(ClusterNode e) {
                return preferredNode.equals(e.id());
            }
        });

        return Collections.singletonMap(
            new ComputeJobAdapter() {
                @LoggerResource
                private IgniteLogger log;

                @IgniteInstanceResource
                private Ignite ignite;

                @Override public Object execute() {
                    try {
                        log.info("Going to put data: " + data.size());

                        IgniteCache<Object, Object> cache = ignite.jcache(cacheName);

                        assert cache != null;

                        Map<Integer, T2<Integer, Collection<Integer>>> putMap = groupData(data);

                        for (Map.Entry<Integer, T2<Integer, Collection<Integer>>> entry : putMap.entrySet()) {
                            T2<Integer, Collection<Integer>> pair = entry.getValue();

                            Object affKey = pair.get1();

                            // Group lock partition.
                            try (IgniteTx tx = ignite.transactions().txStartPartition(cacheName,
                                ignite.affinity(cacheName).partition(affKey), optimistic ? OPTIMISTIC : PESSIMISTIC,
                                REPEATABLE_READ, 0, pair.get2().size())) {
                                for (Integer val : pair.get2())
                                    cache.put(val, val);

                                tx.commit();
                            }
                        }

                        log.info("Finished put data: " + data.size());

                        return data;
                    }
                    catch (Exception e) {
                        throw new IgniteException(e);
                    }
                }

                /**
                 * Groups values by partitions.
                 *
                 * @param data Data to put.
                 * @return Grouped map.
                 */
                private Map<Integer, T2<Integer, Collection<Integer>>> groupData(Iterable<Integer> data) {
                    Map<Integer, T2<Integer, Collection<Integer>>> res = new HashMap<>();

                    for (Integer val : data) {
                        int part = ignite.affinity(cacheName).partition(val);

                        T2<Integer, Collection<Integer>> tup = res.get(part);

                        if (tup == null) {
                            tup = new T2<Integer, Collection<Integer>>(val, new LinkedList<Integer>());

                            res.put(part, tup);
                        }

                        tup.get2().add(val);
                    }

                    return res;
                }
            },
            targetNode);
    }

    /** {@inheritDoc} */
    @Nullable @Override public Void reduce(List<ComputeJobResult> results) {
        return null;
    }
}
