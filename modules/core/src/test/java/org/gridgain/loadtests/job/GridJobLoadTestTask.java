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

package org.gridgain.loadtests.job;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.compute.*;
import org.jetbrains.annotations.*;

import java.util.*;

import static org.apache.ignite.compute.ComputeJobResultPolicy.*;

/**
 * Test task for {@link GridJobLoadTest}
 */
public class GridJobLoadTestTask extends ComputeTaskAdapter<GridJobLoadTestParams, Integer> {
    /**{@inheritDoc} */
    @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid, @Nullable GridJobLoadTestParams arg)
        throws IgniteCheckedException {
        assert !subgrid.isEmpty();

        Map<ComputeJob, ClusterNode> jobs = new HashMap<>();

        for (int i = 0; i < arg.getJobsCount(); i++)
            jobs.put(
                new GridJobLoadTestJob(
                    /*only on the first step*/i == 0,
                    arg.getJobFailureProbability(),
                    arg.getExecutionDuration(),
                    arg.getCompletionDelay()),
                subgrid.get(0));

        return jobs;
    }

    /**
     * Always trying to failover job, except failed assertions.
     *
     * {@inheritDoc}
     */
    @Override public ComputeJobResultPolicy result(ComputeJobResult res, List<ComputeJobResult> rcvd) throws IgniteCheckedException {
        return res.getException() == null ? WAIT :
            res.getException().getCause() instanceof AssertionError ? REDUCE : FAILOVER;
    }

    /**{@inheritDoc} */
    @Override public Integer reduce(List<ComputeJobResult> results) throws IgniteCheckedException {
        int sum = 0;

        for (ComputeJobResult r: results) {
            if (!r.isCancelled() && r.getException() == null)
                sum += r.<Integer>getData();
        }

        return sum;
    }
}
