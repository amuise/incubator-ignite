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

package org.gridgain.testsuites;

import junit.framework.*;
import org.gridgain.grid.kernal.processors.cache.distributed.dht.*;
import org.gridgain.grid.kernal.processors.cache.distributed.near.*;
import org.gridgain.grid.kernal.processors.cache.distributed.replicated.*;

/**
 * Tx recovery self test suite.
 */
public class GridCacheTxRecoverySelfTestSuite extends TestSuite {
    /**
     * @return Cache API test suite.
     * @throws Exception If failed.
     */
    public static TestSuite suite() throws Exception {
        TestSuite suite = new TestSuite("Gridgain Cache tx recovery test suite");

        suite.addTestSuite(GridCachePartitionedTxOriginatingNodeFailureSelfTest.class);
        suite.addTestSuite(GridCachePartitionedNearDisabledTxOriginatingNodeFailureSelfTest.class);
        suite.addTestSuite(GridCacheReplicatedTxOriginatingNodeFailureSelfTest.class);

        suite.addTestSuite(GridCacheColocatedTxPessimisticOriginatingNodeFailureSelfTest.class);
        suite.addTestSuite(GridCacheNearTxPessimisticOriginatingNodeFailureSelfTest.class);
        suite.addTestSuite(GridCacheReplicatedTxPessimisticOriginatingNodeFailureSelfTest.class);

        return suite;
    }
}
