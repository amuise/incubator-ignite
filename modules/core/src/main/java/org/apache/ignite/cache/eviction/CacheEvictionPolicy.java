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

package org.apache.ignite.cache.eviction;

/**
 * Pluggable cache eviction policy. Usually, implementations will internally order
 * cache entries based on {@link #onEntryAccessed(boolean, org.apache.ignite.cache.Entry)} notifications and
 * whenever an element needs to be evicted, {@link org.apache.ignite.cache.Entry#evict()}
 * method should be called. If you need to access the underlying cache directly
 * from this policy, you can get it via {@link org.apache.ignite.cache.Entry#projection()} method.
 * <p>
 * Ignite comes with following eviction policies out-of-the-box:
 * <ul>
 * <li>{@link org.apache.ignite.cache.eviction.lru.CacheLruEvictionPolicy}</li>
 * <li>{@link org.apache.ignite.cache.eviction.random.CacheRandomEvictionPolicy}</li>
 * <li>{@link org.apache.ignite.cache.eviction.fifo.CacheFifoEvictionPolicy}</li>
 * </ul>
 * <p>
 * The eviction policy thread-safety is ensured by Ignition. Implementations of this interface should
 * not worry about concurrency and should be implemented as they were only accessed from one thread.
 * <p>
 * Note that implementations of all eviction policies provided by Ignite are very
 * light weight in a way that they are all lock-free (or very close to it), and do not
 * create any internal tables, arrays, or other expensive structures.
 * The eviction order is preserved by attaching light-weight meta-data to existing
 * cache entries.
 */
public interface CacheEvictionPolicy<K, V> {
    /**
     * Callback for whenever entry is accessed.
     *
     * @param rmv {@code True} if entry has been removed, {@code false} otherwise.
     * @param entry Accessed entry.
     */
    public void onEntryAccessed(boolean rmv, EvictableEntry<K, V> entry);
}
