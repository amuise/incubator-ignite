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

package org.gridgain.grid.session;

import org.apache.ignite.*;
import org.apache.ignite.compute.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.resources.*;
import org.gridgain.grid.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.testframework.*;
import org.gridgain.testframework.junits.common.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 *
 */
@SuppressWarnings({"CatchGenericClass"})
@GridCommonTest(group = "Task Session")
public class GridSessionJobWaitTaskAttributeSelfTest extends GridCommonAbstractTest {
    /** */
    public static final int SPLIT_COUNT = 5;

    /** */
    public static final int EXEC_COUNT = 5;

    /** */
    public GridSessionJobWaitTaskAttributeSelfTest() {
        super(true);
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration c = super.getConfiguration(gridName);

        TcpDiscoverySpi discoSpi = new TcpDiscoverySpi();

        discoSpi.setIpFinder(new TcpDiscoveryVmIpFinder(true));

        c.setDiscoverySpi(discoSpi);

        c.setExecutorService(
            new ThreadPoolExecutor(
                SPLIT_COUNT * EXEC_COUNT,
                SPLIT_COUNT * EXEC_COUNT,
                0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>()));

        c.setExecutorServiceShutdown(true);

        return c;
    }

    /**
     * @throws Exception if failed.
     */
    public void testSetAttribute() throws Exception {
        for (int i = 0; i < EXEC_COUNT; i++)
            checkTask(i);
    }

    /**
     * @throws Exception if failed.
     */
    public void testMultiThreaded() throws Exception {
        final GridThreadSerialNumber sNum = new GridThreadSerialNumber();

        final AtomicBoolean failed = new AtomicBoolean(false);

        GridTestUtils.runMultiThreaded(new Runnable() {
            @Override public void run() {
                int num = sNum.get();

                try {
                    checkTask(num);
                }
                catch (Throwable e) {
                    error("Failed to execute task.", e);

                    failed.set(true);
                }
            }
        }, EXEC_COUNT, "grid-session-test");

        assert !failed.get() : "Multithreaded test failed";
    }

    /**
     * @param num Number.
     * @throws IgniteCheckedException if failed.
     */
    private void checkTask(int num) throws IgniteCheckedException {
        Ignite ignite = G.ignite(getTestGridName());

        ComputeTaskFuture<?> fut = executeAsync(ignite.compute(), GridTaskSessionTestTask.class.getName(), null);

        int exp = SPLIT_COUNT - 1;

        Object res = fut.get();

        assert (Integer)res == exp : "Invalid result [expected=" + exp +
            ", actual=" + res + ", iter=" + num + ", fut=" + fut + ']';
    }


    /**
     *
     */
    @ComputeTaskSessionFullSupport
    private static class GridTaskSessionTestTask extends ComputeTaskSplitAdapter<Serializable, Integer> {
        /** */
        @IgniteLoggerResource
        private IgniteLogger log;

        /** */
        @IgniteTaskSessionResource
        private ComputeTaskSession taskSes;

        /** {@inheritDoc} */
        @Override protected Collection<? extends ComputeJob> split(int gridSize, Serializable arg) throws IgniteCheckedException {
            assert taskSes != null;

            if (log.isInfoEnabled())
                log.info("Splitting job [job=" + this + ", gridSize=" + gridSize + ", arg=" + arg + ']');

            Collection<ComputeJob> jobs = new ArrayList<>(SPLIT_COUNT);

            for (int i = 1; i <= SPLIT_COUNT; i++) {
                jobs.add(new ComputeJobAdapter(i) {
                    @Override public Serializable execute() throws IgniteCheckedException {
                        assert taskSes != null;

                        if (log.isInfoEnabled())
                            log.info("Computing job [job=" + this + ", arg=" + argument(0) + ']');

                        if (this.<Integer>argument(0) != 1) {
                            try {
                                String val = (String)taskSes.waitForAttribute("testName", 20000);

                                if (log.isInfoEnabled())
                                    log.info("Received attribute 'testName': " + val);

                                if ("testVal".equals(val))
                                    return 1;

                                fail("Invalid test session value: " + val);
                            }
                            catch (InterruptedException e) {
                                throw new IgniteCheckedException("Failed to get attribute due to interruption.", e);
                            }
                        }

                        return 0;
                    }
                });
            }

            return jobs;
        }

        /** {@inheritDoc} */
        @Override public ComputeJobResultPolicy result(ComputeJobResult result, List<ComputeJobResult> received)
            throws IgniteCheckedException {
            if (result.getException() != null)
                throw result.getException();

            if (received.size() == 1) {
                log.info("Got result from setting job: " + result);

                taskSes.setAttribute("testName", "testVal");
            }
            else
                log.info("Got result from waiting job: " + result);

            return received.size() == SPLIT_COUNT ? ComputeJobResultPolicy.REDUCE : ComputeJobResultPolicy.WAIT;
        }

        /** {@inheritDoc} */
        @Override public Integer reduce(List<ComputeJobResult> results) throws IgniteCheckedException {
            if (log.isInfoEnabled())
                log.info("Reducing job [job=" + this + ", results=" + results + ']');

            if (results.size() < SPLIT_COUNT)
                fail("Results size is less than split count: " + results.size());

            int sum = 0;

            for (ComputeJobResult res : results) {
                if (res.getData() == null)
                    fail("Got null result data: " + res);
                else
                    log.info("Reducing result: " + res.getData());

                sum += (Integer)res.getData();
            }

            return sum;
        }
    }
}
