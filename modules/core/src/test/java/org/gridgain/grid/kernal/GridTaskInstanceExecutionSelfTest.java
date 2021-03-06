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
import org.apache.ignite.resources.*;
import org.gridgain.grid.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.testframework.junits.common.*;

import java.util.*;

/**
 * Task instance execution test.
 */
@SuppressWarnings("PublicInnerClass")
@GridCommonTest(group = "Kernal Self")
public class GridTaskInstanceExecutionSelfTest extends GridCommonAbstractTest {
    /** */
    private static Object testState;

    /** */
    public GridTaskInstanceExecutionSelfTest() {
        super(true);
    }

    /**
     * @throws Exception If failed.
     */
    public void testSynchronousExecute() throws Exception {
        Ignite ignite = G.ignite(getTestGridName());

        testState = 12345;

        GridStatefulTask task = new GridStatefulTask(testState);

        assert task.getState() != null;
        assert task.getState() == testState;

        IgniteCompute comp = ignite.compute().enableAsync();

        assertNull(comp.execute(task,  "testArg"));

        ComputeTaskFuture<?> fut = comp.future();

        assert fut != null;

        info("Task result: " + fut.get());
    }

    /**
     * Stateful task.
     */
    public static class GridStatefulTask extends GridTestTask {
        /** */
        private Object state;

        /** */
        @IgniteLoggerResource
        private IgniteLogger log;

        /**
         * @param state State.
         */
        public GridStatefulTask(Object state) {
            this.state = state;
        }

        /**
         * @return The state.
         */
        public Object getState() {
            return state;
        }

        /** {@inheritDoc} */
        @Override public Collection<? extends ComputeJob> split(int gridSize, Object arg) {
            log.info("Task split state: " + state);

            assert state != null;
            assert state == testState;

            return super.split(gridSize, arg);
        }

        /** {@inheritDoc} */
        @Override public ComputeJobResultPolicy result(ComputeJobResult res, List<ComputeJobResult> received) throws IgniteCheckedException {
            log.info("Task result state: " + state);

            assert state != null;
            assert state == testState;

            return super.result(res, received);
        }

        /** {@inheritDoc} */
        @Override public Object reduce(List<ComputeJobResult> results) throws IgniteCheckedException {
            log.info("Task reduce state: " + state);

            assert state != null;
            assert state == testState;

            return super.reduce(results);
        }
    }
}
