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

package org.gridgain.scalar.lang

import org.gridgain.grid.util.lang.{IgniteReducer3}

/**
 * Wrapping Scala function for `GridReducer3`.
 */
class ScalarReducer3Function[E1, E2, E3, R](val inner: IgniteReducer3[E1, E2, E3, R]) extends
    ((Seq[E1], Seq[E2], Seq[E3]) => R) {
    assert(inner != null)

    /**
     * Delegates to passed in grid reducer.
     */
    def apply(s1: Seq[E1], s2: Seq[E2], s3: Seq[E3]) = {
        for (e1 <- s1; e2 <- s2; e3 <- s3) inner.collect(e1, e2, e3)

        inner.apply()
    }
}
