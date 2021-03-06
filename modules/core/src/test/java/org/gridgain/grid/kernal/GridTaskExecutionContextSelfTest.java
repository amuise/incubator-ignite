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
import org.apache.ignite.compute.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.marshaller.optimized.*;
import org.apache.ignite.resources.*;
import org.gridgain.grid.*;
import org.gridgain.grid.util.lang.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.testframework.*;
import org.gridgain.testframework.junits.common.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Tests for {@code GridProjection.withXXX(..)} methods.
 */
public class GridTaskExecutionContextSelfTest extends GridCommonAbstractTest {
    /** */
    private static final AtomicInteger CNT = new AtomicInteger();

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        cfg.setMarshaller(new IgniteOptimizedMarshaller(false));

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        startGridsMultiThreaded(2);
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        stopAllGrids();
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        CNT.set(0);
    }

    /**
     * @throws Exception If failed.
     */
    public void testWithName() throws Exception {
        Callable<String> f = new IgniteCallable<String>() {
            @IgniteTaskSessionResource
            private ComputeTaskSession ses;

            @Override public String call() {
                return ses.getTaskName();
            }
        };

        Ignite g = grid(0);

        assert "name1".equals(g.compute().withName("name1").call(f));
        assert "name2".equals(g.compute().withName("name2").call(f));
        assert f.getClass().getName().equals(g.compute().call(f));

        assert "name1".equals(g.compute().withName("name1").execute(new TestTask(false), null));
        assert "name2".equals(g.compute().withName("name2").execute(new TestTask(false), null));
        assert TestTask.class.getName().equals(g.compute().execute(new TestTask(false), null));
    }

    /**
     * @throws Exception If failed.
     */
    public void testWithNoFailoverClosure() throws Exception {
        final Runnable r = new GridAbsClosureX() {
            @Override public void applyx() throws IgniteCheckedException {
                CNT.incrementAndGet();

                throw new ComputeExecutionRejectedException("Expected error.");
            }
        };

        final Ignite g = grid(0);

        GridTestUtils.assertThrows(
            log,
            new Callable<Object>() {
                @Override public Object call() throws Exception {
                    g.compute().withNoFailover().run(r);

                    return null;
                }
            },
            ComputeExecutionRejectedException.class,
            "Expected error."
        );

        assertEquals(1, CNT.get());
    }

    /**
     * @throws Exception If failed.
     */
    public void testWithNoFailoverTask() throws Exception {
        final Ignite g = grid(0);

        GridTestUtils.assertThrows(
            log,
            new Callable<Object>() {
                @Override public Object call() throws Exception {
                    g.compute().withNoFailover().execute(new TestTask(true), null);

                    return null;
                }
            },
            ComputeExecutionRejectedException.class,
            "Expected error."
        );

        assertEquals(1, CNT.get());
    }

    /**
     * Test task that returns its name.
     */
    private static class TestTask extends ComputeTaskSplitAdapter<Void, String> {
        /** */
        private final boolean fail;

        /**
         * @param fail Whether to fail.
         */
        private TestTask(boolean fail) {
            this.fail = fail;
        }

        /** {@inheritDoc} */
        @Override protected Collection<? extends ComputeJob> split(int gridSize, Void arg) throws IgniteCheckedException {
            return F.asSet(new ComputeJobAdapter() {
                @IgniteTaskSessionResource
                private ComputeTaskSession ses;

                @Override public Object execute() throws IgniteCheckedException {
                    CNT.incrementAndGet();

                    if (fail)
                        throw new ComputeExecutionRejectedException("Expected error.");

                    return ses.getTaskName();
                }
            });
        }

        /** {@inheritDoc} */
        @Override public String reduce(List<ComputeJobResult> results) throws IgniteCheckedException {
            return F.first(results).getData();
        }
    }
}
