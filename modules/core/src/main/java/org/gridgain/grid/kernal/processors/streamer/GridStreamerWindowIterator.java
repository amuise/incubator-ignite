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

package org.gridgain.grid.kernal.processors.streamer;

import org.jetbrains.annotations.*;

import java.util.*;

/**
 * Helper iterator extension which prevents regular element remove and adds removex() method tracking which element
 * was actually removed.
 */
public abstract class GridStreamerWindowIterator<T> implements Iterator<T> {
    /** {@inheritDoc} */
    @Override public void remove() {
        throw new UnsupportedOperationException();
    }

    /**
     * Remove element from the underlying collection and return removed element.
     *
     * @return Removed element or {@code null} in case no deletion occurred.
     */
    @Nullable public abstract T removex();
}
