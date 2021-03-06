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

package org.gridgain.grid.tests.p2p;

import org.apache.ignite.cluster.*;
import org.apache.ignite.lang.*;
import org.gridgain.grid.util.typedef.internal.*;

import java.util.*;

/**
 * This node filter excludes the node with the given UUID
 * from the topology.
 */
public class GridExcludeNodeFilter implements IgnitePredicate<ClusterNode> {
    /** Node ID to exclude. */
    private final UUID excludeId;

    /**
     * @param excludeId Excluded node UUID.
     */
    public GridExcludeNodeFilter(UUID excludeId) {
        assert excludeId != null;

        this.excludeId = excludeId;
    }

    /** {@inheritDoc} */
    @Override public boolean apply(ClusterNode e) {
        return !excludeId.equals(e.id());
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridExcludeNodeFilter.class, this);
    }
}
