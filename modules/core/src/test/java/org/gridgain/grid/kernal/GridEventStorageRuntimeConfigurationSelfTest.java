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

package org.gridgain.grid.kernal;

import org.apache.ignite.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.events.*;
import org.apache.ignite.lang.*;
import org.gridgain.grid.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.testframework.junits.common.*;
import org.junit.*;

import java.util.*;
import java.util.concurrent.atomic.*;

import static org.apache.ignite.events.IgniteEventType.*;

/**
 * Tests for runtime events configuration.
 */
public class GridEventStorageRuntimeConfigurationSelfTest extends GridCommonAbstractTest {
    /** */
    private int[] inclEvtTypes;

    /** {@inheritDoc} */
    @SuppressWarnings("deprecation")
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        cfg.setIncludeEventTypes(inclEvtTypes);

        return cfg;
    }

    /**
     * @throws Exception If failed.
     */
    public void testEnableWithDefaults() throws Exception {
        inclEvtTypes = null;

        try {
            Ignite g = startGrid();

            final AtomicInteger cnt = new AtomicInteger();

            g.events().localListen(new IgnitePredicate<IgniteEvent>() {
                @Override public boolean apply(IgniteEvent evt) {
                    cnt.incrementAndGet();

                    return true;
                }
            }, EVT_TASK_STARTED);

            g.compute().run(F.noop());

            assertEquals(0, cnt.get());

            g.events().enableLocal(EVT_TASK_STARTED);

            g.compute().run(F.noop());

            assertEquals(1, cnt.get());
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testEnableWithIncludes() throws Exception {
        inclEvtTypes = new int[] { EVT_TASK_STARTED, EVT_TASK_FINISHED };

        try {
            Ignite g = startGrid();

            final AtomicInteger cnt = new AtomicInteger();

            g.events().localListen(new IgnitePredicate<IgniteEvent>() {
                @Override public boolean apply(IgniteEvent evt) {
                    cnt.incrementAndGet();

                    return true;
                }
            }, EVT_TASK_STARTED, EVT_TASK_FINISHED, EVT_JOB_STARTED);

            g.compute().run(F.noop());

            assertEquals(2, cnt.get());

            g.events().enableLocal(EVT_TASK_FINISHED, EVT_JOB_STARTED);

            g.compute().run(F.noop());

            assertEquals(5, cnt.get());
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testDisableWithIncludes() throws Exception {
        inclEvtTypes = null;

        try {
            Ignite g = startGrid();

            g.events().enableLocal(EVT_TASK_STARTED, EVT_TASK_FINISHED, EVT_JOB_STARTED);

            final AtomicInteger cnt = new AtomicInteger();

            g.events().localListen(new IgnitePredicate<IgniteEvent>() {
                @Override public boolean apply(IgniteEvent evt) {
                    cnt.incrementAndGet();

                    return true;
                }
            }, EVT_TASK_STARTED, EVT_TASK_FINISHED, EVT_JOB_STARTED);

            g.compute().run(F.noop());

            assertEquals(3, cnt.get());

            g.events().disableLocal(EVT_TASK_STARTED, EVT_TASK_FINISHED, EVT_JOB_FAILED);

            g.compute().run(F.noop());

            assertEquals(4, cnt.get());
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testEnableDisable() throws Exception {
        inclEvtTypes = null;

        try {
            Ignite g = startGrid();

            IgniteEvents evts = g.events();

            evts.enableLocal(EVT_CACHE_OBJECT_PUT);

            evts.disableLocal(EVT_CACHE_OBJECT_PUT);

            for (int evtType : evts.enabledEvents())
                assertFalse(evtType == EVT_CACHE_OBJECT_PUT);
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @throws Exception If failed.
     */
    @SuppressWarnings("UnusedDeclaration")
    public void testInvalidTypes() throws Exception {
        inclEvtTypes = new int[]{EVT_TASK_STARTED};

        try (Ignite g = startGrid()) {
            assertTrue(g.events().isEnabled(EVT_TASK_STARTED));

            try {
                g.events().isEnabled(-13);

                fail("Expected IgniteCheckedException");
            }
            catch (IllegalArgumentException e) {
                info("Caught expected exception: " + e);
            }
        }
        finally {
            stopAllGrids();
        }

        inclEvtTypes = new int[]{-13};

        try (Ignite g = startGrid()) {
            fail("Expected IgniteCheckedException");
        }
        catch (IgniteCheckedException e) {
            info("Caught expected exception: " + e);
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testGetters() throws Exception {
        inclEvtTypes = new int[]{EVT_TASK_STARTED, EVT_TASK_FINISHED, 30000};

        try {
            Ignite g = startGrid();

            assertEqualsWithoutOrder(inclEvtTypes, getEnabledEvents(g));
            assertEqualsWithoutOrder(inclEvtTypes, getEnabledEvents(1013, g, 30000));

            g.events().enableLocal(20000, EVT_TASK_STARTED, EVT_CACHE_ENTRY_CREATED);

            assertEqualsWithoutOrder(
                new int[] {EVT_TASK_STARTED, EVT_TASK_FINISHED, EVT_CACHE_ENTRY_CREATED, 20000, 30000},
                getEnabledEvents(g));

            assertEqualsWithoutOrder(
                new int[] {EVT_TASK_STARTED, EVT_TASK_FINISHED, EVT_CACHE_ENTRY_CREATED, 20000, 30000},
                getEnabledEvents(1013, g, 20000, 30000));

            g.events().disableLocal(20000, 20001, 30000, EVT_TASK_STARTED, EVT_CACHE_ENTRY_CREATED);

            assertEqualsWithoutOrder(
                new int[] {EVT_TASK_FINISHED, EVT_TASK_STARTED, 30000},
                getEnabledEvents(g));

            assertEqualsWithoutOrder(
                new int[] {EVT_TASK_FINISHED, EVT_TASK_STARTED, 30000},
                getEnabledEvents(1013, g, 20000, 30000));

            int[] a = new int[1013];

            for (int i = 0; i < 1000; i++)
                a[i] = 1001 + i;

            a[1000] = EVT_TASK_TIMEDOUT;
            a[1001] = EVT_TASK_STARTED;

            randomShuffle(a, 1002);

            int[] a0 = Arrays.copyOf(a, a.length + 1);

            g.events().enableLocal(Arrays.copyOf(a, 1002));

            a0[1002] = EVT_TASK_FINISHED;
            a0[1003] = 30000;

            assertEqualsWithoutOrder(Arrays.copyOf(a0, 1004), getEnabledEvents(g));
            assertEqualsWithoutOrder(Arrays.copyOf(a0, 1004), getEnabledEvents(2013, g, 30000));

            g.events().disableLocal(Arrays.copyOf(a, 1002));

            assertEqualsWithoutOrder(
                new int[] {EVT_TASK_STARTED, EVT_TASK_FINISHED, 30000},
                getEnabledEvents(g));

            assertEqualsWithoutOrder(
                new int[] {EVT_TASK_STARTED, EVT_TASK_FINISHED, 30000},
                getEnabledEvents(1013, g, 20000, 30000));
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * @param a Array.
     * @param len Prefix length.
     */
    private void randomShuffle(int[] a, int len) {
        Random rand = new Random();

        for (int i = len - 1; i > 0; i--) {
            int j = rand.nextInt(i);

            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    /**
     * @param a First array.
     * @param b Second array.
     */
    private void assertEqualsWithoutOrder(int[] a, int[] b) {
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a.length, b.length);

        int[] a0 = Arrays.copyOf(a, a.length);
        int[] b0 = Arrays.copyOf(a, a.length);

        Arrays.sort(a0);
        Arrays.sort(b0);

        Assert.assertArrayEquals(a0, b0);
    }

    /**
     * @param g Grid.
     * @return Enabled events.
     */
    private int[] getEnabledEvents(Ignite g) {
        return g.events().enabledEvents();
    }

    /**
     * @param limit Loop limit.
     * @param g Grid.
     * @param customTypes Array of event types.
     * @return Enabled events counted with loop (1..limit) and checks of custom types.
     */
    private int[] getEnabledEvents(int limit, Ignite g, int... customTypes) {
        Collection<Integer> res = new HashSet<>();

        IgniteEvents evts = g.events();

        for (int i = 1; i <= limit; i++) {
            if (evts.isEnabled(i))
                res.add(i);
        }

        if (customTypes != null) {
            for (int i : customTypes)
                if (evts.isEnabled(i))
                    res.add(i);
        }

        return U.toIntArray(res);
    }
}
