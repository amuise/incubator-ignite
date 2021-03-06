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
import org.gridgain.grid.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.testframework.junits.common.*;

import java.io.*;
import java.util.*;

/**
 *
 */
@GridCommonTest(group = "Kernal Self")
public class GridTaskResultCacheSelfTest extends GridCommonAbstractTest {
    /**
     *
     */
    public GridTaskResultCacheSelfTest() {
        super(true);
    }

    /**
     * @throws Exception If failed.
     */
    public void testNoCacheResults() throws Exception {
        Ignite ignite = G.ignite(getTestGridName());

        ignite.compute().execute(GridResultNoCacheTestTask.class, "Grid Result No Cache Test Argument");
    }

    /**
     * @throws Exception If failed.
     */
    public void testCacheResults() throws Exception {
        Ignite ignite = G.ignite(getTestGridName());

        ignite.compute().execute(GridResultCacheTestTask.class, "Grid Result Cache Test Argument");
    }

    /**
     *
     */
    @ComputeTaskNoResultCache
    private static class GridResultNoCacheTestTask extends GridAbstractCacheTestTask {
        /** {@inheritDoc} */
        @Override public ComputeJobResultPolicy result(ComputeJobResult res, List<ComputeJobResult> rcvd) throws IgniteCheckedException {
            assert res.getData() != null;
            assert rcvd.isEmpty();

            return super.result(res, rcvd);
        }

        /** {@inheritDoc} */
        @Override public Object reduce(List<ComputeJobResult> results) throws IgniteCheckedException {
            assert results.isEmpty();

            return null;
        }
    }

    /**
     *
     */
    private static class GridResultCacheTestTask extends GridAbstractCacheTestTask {
        /** {@inheritDoc} */
        @Override public ComputeJobResultPolicy result(ComputeJobResult res, List<ComputeJobResult> rcvd)
            throws IgniteCheckedException {
            assert res.getData() != null;
            assert rcvd.contains(res);

            for (ComputeJobResult jobRes : rcvd)
                assert jobRes.getData() != null;

            return super.result(res, rcvd);
        }

        /** {@inheritDoc} */
        @Override public Object reduce(List<ComputeJobResult> results) throws IgniteCheckedException {
            for (ComputeJobResult res : results) {
                if (res.getException() != null)
                    throw res.getException();

                assert res.getData() != null;
            }

            return null;
        }
    }

    /**
     * Test task.
     */
    private abstract static class GridAbstractCacheTestTask extends ComputeTaskSplitAdapter<String, Object> {
        /** {@inheritDoc} */
        @Override protected Collection<? extends ComputeJob> split(int gridSize, String arg) throws IgniteCheckedException {
            String[] words = arg.split(" ");

            Collection<ComputeJobAdapter> jobs = new ArrayList<>(words.length);

            for (String word : words) {
                jobs.add(new ComputeJobAdapter(word) {
                    @Override public Serializable execute() {
                        return argument(0);
                    }
                });
            }

            return jobs;
        }
    }
}
