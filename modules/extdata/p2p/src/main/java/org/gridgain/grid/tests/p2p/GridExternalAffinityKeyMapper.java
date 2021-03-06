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

import org.gridgain.grid.cache.affinity.*;

/**
 * Test mapper for P2P class loading tests.
 */
public class GridExternalAffinityKeyMapper implements GridCacheAffinityKeyMapper {
    /** {@inheritDoc} */
    @Override public Object affinityKey(Object key) {
        if (key instanceof Integer)
            return 1 == (Integer)key ? key : 0;

        return key;
    }

    /** {@inheritDoc} */
    @Override public void reset() {
        // This mapper is stateless and needs no initialization logic.
    }
}
