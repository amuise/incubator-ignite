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

package org.apache.ignite.scalar.examples

import java.util.concurrent.Callable

import org.apache.ignite._
import org.apache.ignite.cache.affinity.CacheAffinityKeyMapped
import org.apache.ignite.lang.IgniteCallable
import org.apache.ignite.scalar.scalar
import org.apache.ignite.scalar.scalar._
import org.jetbrains.annotations.Nullable

/**
 * Example of how to collocate computations and data in Ignite using
 * `CacheAffinityKeyMapped` annotation as opposed to direct API calls. This
 * example will first populate cache on some node where cache is available, and then
 * will send jobs to the nodes where keys reside and print out values for those
 * keys.
 *
 * Remote nodes should always be started with configuration file which includes
 * cache: `'ignite.sh examples/config/example-cache.xml'`. Local node can
 * be started with or without cache.
 */
object ScalarCacheAffinityExample1 {
    /** Configuration file name. */
    private val CONFIG = "examples/config/example-cache.xml" // Cache.

    /** Name of cache specified in spring configuration. */
    private val NAME = "partitioned"

    /**
     * Example entry point. No arguments required.
     *
     * Note that in case of `LOCAL` configuration,
     * since there is no distribution, values may come back as `nulls`.
     */
    def main(args: Array[String]) {
        scalar(CONFIG) {
            // Clean up caches on all nodes before run.
            cache$(NAME).get.clear(0)

            var keys = Seq.empty[String]

            ('A' to 'Z').foreach(keys :+= _.toString)

            populateCache(ignite$, keys)

            var results = Map.empty[String, String]

            keys.foreach(key => {
                val res = ignite$.call$(
                    new IgniteCallable[String] {
                        @CacheAffinityKeyMapped
                        def affinityKey(): String = key

                        def cacheName(): String = NAME

                        @Nullable def call: String = {
                            println(">>> Executing affinity job for key: " + key)

                            val cache = cache$[String, String](NAME)

                            if (!cache.isDefined) {
                                println(">>> Cache not found [nodeId=" + ignite$.cluster().localNode.id +
                                    ", cacheName=" + NAME + ']')

                                "Error"
                            }
                            else
                                cache.get.peek(key)
                        }
                    },
                    null
                )

                results += (key -> res.head)
            })

            results.foreach(e => println(">>> Affinity job result for key '" + e._1 + "': " + e._2))
        }
    }

    /**
     * Populates cache with given keys. This method accounts for the case when
     * cache is not started on local node. In that case a job which populates
     * the cache will be sent to the node where cache is started.
     *
     * @param ignite Ignite.
     * @param keys Keys to populate.
     */
    private def populateCache(ignite: Ignite, keys: Seq[String]) {
        var prj = ignite.cluster().forCacheNodes(NAME)

        // Give preference to local node.
        if (prj.nodes().contains(ignite.cluster().localNode))
            prj = ignite.cluster().forLocal()

        // Populate cache on some node (possibly this node) which has cache with given name started.
        prj.run$(() => {
            println(">>> Storing keys in cache: " + keys)

            val c = cache$[String, String](NAME).get

            keys.foreach(key => c += (key -> key.toLowerCase))
        }, null)
    }
}
