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

package org.apache.ignite.cache.query;

import org.apache.ignite.internal.util.typedef.internal.*;

import javax.cache.event.*;

/**
 * API for configuring and executing continuous cache queries.
 * <p>
 * Continuous queries are executed as follows:
 * <ol>
 * <li>
 *  Query is sent to requested grid nodes. Note that for {@link org.apache.ignite.cache.CacheMode#LOCAL LOCAL}
 *  and {@link org.apache.ignite.cache.CacheMode#REPLICATED REPLICATED} caches query will be always executed
 *  locally.
 * </li>
 * <li>
 *  Each node iterates through existing cache data and registers listeners that will
 *  notify about further updates.
 * <li>
 *  Each key-value pair is passed through optional filter and if this filter returns
 *  true, key-value pair is sent to the master node (the one that executed query).
 *  If filter is not provided, all pairs are sent.
 * </li>
 * <li>
 *  When master node receives key-value pairs, it notifies the local callback.
 * </li>
 * </ol>
 * <h2 class="header">NOTE</h2>
 * Under some concurrent circumstances callback may get several notifications
 * for one cache update. This should be taken into account when implementing callback.
 * <h1 class="header">Query usage</h1>
 * As an example, suppose we have cache with {@code 'Person'} objects and we need
 * to query all persons with salary above 1000.
 * <p>
 * Here is the {@code Person} class:
 * <pre name="code" class="java">
 * public class Person {
 *     // Name.
 *     private String name;
 *
 *     // Salary.
 *     private double salary;
 *
 *     ...
 * }
 * </pre>
 * <p>
 * You can create and execute continuous query like so:
 * <pre name="code" class="java">
 * // Create new continuous query.
 * qry = cache.createContinuousQuery();
 *
 * // Callback that is called locally when update notifications are received.
 * // It simply prints out information about all created persons.
 * qry.callback(new GridPredicate2&lt;UUID, Collection&lt;Map.Entry&lt;UUID, Person&gt;&gt;&gt;() {
 *     &#64;Override public boolean apply(UUID uuid, Collection&lt;Map.Entry&lt;UUID, Person&gt;&gt; entries) {
 *         for (Map.Entry&lt;UUID, Person&gt; e : entries) {
 *             Person p = e.getValue();
 *
 *             X.println("&gt;&gt;&gt;");
 *             X.println("&gt;&gt;&gt; " + p.getFirstName() + " " + p.getLastName() +
 *                 "'s salary is " + p.getSalary());
 *             X.println("&gt;&gt;&gt;");
 *         }
 *
 *         return true;
 *     }
 * });
 *
 * // This query will return persons with salary above 1000.
 * qry.filter(new GridPredicate2&lt;UUID, Person&gt;() {
 *     &#64;Override public boolean apply(UUID uuid, Person person) {
 *         return person.getSalary() &gt; 1000;
 *     }
 * });
 *
 * // Execute query.
 * qry.execute();
 * </pre>
 * This will execute query on all nodes that have cache you are working with and notify callback
 * with both data that already exists in cache and further updates.
 * <p>
 * To stop receiving updates call {@link #close()} method:
 * <pre name="code" class="java">
 * qry.cancel();
 * </pre>
 * Note that one query instance can be executed only once. After it's cancelled, it's non-operational.
 * If you need to repeat execution, use {@link org.apache.ignite.internal.processors.cache.query.CacheQueries#createContinuousQuery()} method to create
 * new query.
 */
public final class ContinuousQuery<K, V> extends Query<ContinuousQuery<K,V>> {
    /** */
    private static final long serialVersionUID = 0L;

    /**
     * Default buffer size. Size of {@code 1} means that all entries
     * will be sent to master node immediately (buffering is disabled).
     */
    public static final int DFLT_BUF_SIZE = 1;

    /** Maximum default time interval after which buffer will be flushed (if buffering is enabled). */
    public static final long DFLT_TIME_INTERVAL = 0;

    /**
     * Default value for automatic unsubscription flag. Remote filters
     * will be unregistered by default if master node leaves topology.
     */
    public static final boolean DFLT_AUTO_UNSUBSCRIBE = true;

    /** Initial filter. */
    private Query initFilter;

    /** Local listener. */
    private CacheEntryUpdatedListener<K, V> locLsnr;

    /** Remote filter. */
    private CacheEntryEventFilter<K, V> rmtFilter;

    /** Buffer size. */
    private int bufSize = DFLT_BUF_SIZE;

    /** Time interval. */
    private long timeInterval = DFLT_TIME_INTERVAL;

    /** Automatic unsubscription flag. */
    private boolean autoUnsubscribe = DFLT_AUTO_UNSUBSCRIBE;

    /**
     * Sets initial query.
     *
     * @param initFilter Initial query.
     */
    public void setInitialPredicate(Query initFilter) {
        this.initFilter = initFilter;
    }

    /**
     * Gets initial query.
     *
     * @return Initial query.
     */
    public Query getInitialPredicate() {
        return initFilter;
    }

    /**
     * Sets local callback. This callback is called only in local node when new updates are received.
     * <p> The callback predicate accepts ID of the node from where updates are received and collection
     * of received entries. Note that for removed entries value will be {@code null}.
     * <p>
     * If the predicate returns {@code false}, query execution will be cancelled.
     * <p>
     * <b>WARNING:</b> all operations that involve any kind of JVM-local or distributed locking (e.g.,
     * synchronization or transactional cache operations), should be executed asynchronously without
     * blocking the thread that called the callback. Otherwise, you can get deadlocks.
     *
     * @param locLsnr Local callback.
     */
    public void setLocalListener(CacheEntryUpdatedListener<K, V> locLsnr) {
        this.locLsnr = locLsnr;
    }

    /**
     * Gets local listener.
     *
     * @return Local listener.
     */
    public CacheEntryUpdatedListener<K, V> getLocalListener() {
        return locLsnr;
    }

    /**
     * Sets optional key-value filter. This filter is called before entry is sent to the master node.
     * <p>
     * <b>WARNING:</b> all operations that involve any kind of JVM-local or distributed locking
     * (e.g., synchronization or transactional cache operations), should be executed asynchronously
     * without blocking the thread that called the filter. Otherwise, you can get deadlocks.
     *
     * @param rmtFilter Key-value filter.
     */
    public void setRemoteFilter(CacheEntryEventFilter<K, V> rmtFilter) {
        this.rmtFilter = rmtFilter;
    }

    /**
     * Gets remote filter.
     *
     * @return Remote filter.
     */
    public CacheEntryEventFilter<K, V> getRemoteFilter() {
        return rmtFilter;
    }

    /**
     * Sets buffer size. <p> When a cache update happens, entry is first put into a buffer. Entries from buffer will be
     * sent to the master node only if the buffer is full or time provided via {@link #setTimeInterval(long)} method is
     * exceeded. <p> Default buffer size is {@code 1} which means that entries will be sent immediately (buffering is
     * disabled).
     *
     * @param bufSize Buffer size.
     */
    public void setBufferSize(int bufSize) {
        A.ensure(bufSize > 0, "bufSize > 0");

        this.bufSize = bufSize;
    }

    /**
     * Gets buffer size.
     *
     * @return Buffer size.
     */
    public int getBufferSize() {
        return bufSize;
    }

    /**
     * Sets time interval. <p> When a cache update happens, entry is first put into a buffer. Entries from buffer will
     * be sent to the master node only if the buffer is full (its size can be provided via {@link #setBufferSize(int)}
     * method) or time provided via this method is exceeded. <p> Default time interval is {@code 0} which means that
     * time check is disabled and entries will be sent only when buffer is full.
     *
     * @param timeInterval Time interval.
     */
    public void setTimeInterval(long timeInterval) {
        A.ensure(timeInterval >= 0, "timeInterval >= 0");

        this.timeInterval = timeInterval;
    }

    /**
     * Gets time interval.
     *
     * @return Time interval.
     */
    public long getTimeInterval() {
        return timeInterval;
    }

    /**
     * Sets automatic unsubscribe flag. <p> This flag indicates that query filters on remote nodes should be
     * automatically unregistered if master node (node that initiated the query) leaves topology. If this flag is
     * {@code false}, filters will be unregistered only when the query is cancelled from master node, and won't ever be
     * unregistered if master node leaves grid. <p> Default value for this flag is {@code true}.
     *
     * @param autoUnsubscribe Automatic unsubscription flag.
     */
    public void setAutoUnsubscribe(boolean autoUnsubscribe) {
        this.autoUnsubscribe = autoUnsubscribe;
    }

    /**
     * Gets automatic unsubscription flag value.
     *
     * @return Automatic unsubscription flag.
     */
    public boolean isAutoUnsubscribe() {
        return autoUnsubscribe;
    }

}
