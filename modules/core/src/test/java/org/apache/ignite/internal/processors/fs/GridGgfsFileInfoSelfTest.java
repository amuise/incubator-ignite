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

package org.apache.ignite.internal.processors.fs;

import org.apache.ignite.*;
import org.apache.ignite.marshaller.*;
import org.apache.ignite.marshaller.optimized.*;
import org.apache.ignite.internal.util.typedef.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * {@link org.apache.ignite.internal.processors.fs.GridGgfsFileInfo} test case.
 */
public class GridGgfsFileInfoSelfTest extends GridGgfsCommonAbstractTest {
    /** Marshaller to test {@link Externalizable} interface. */
    private final IgniteMarshaller marshaller = new IgniteOptimizedMarshaller();

    /**
     * Test node info serialization.
     *
     * @throws Exception If failed.
     */
    public void testSerialization() throws Exception {
        final int max = Integer.MAX_VALUE;

        multithreaded(new Callable<Object>() {
            private final Random rnd = new Random();

            @SuppressWarnings("deprecation") // Suppress due to default constructor should never be used directly.
            @Nullable @Override public Object call() throws IgniteCheckedException {
                for (int i = 0; i < 10000; i++) {
                    testSerialization(new GridGgfsFileInfo());
                    testSerialization(new GridGgfsFileInfo());
                    testSerialization(new GridGgfsFileInfo(true, null));
                    testSerialization(new GridGgfsFileInfo(false, null));

                    GridGgfsFileInfo rndInfo = new GridGgfsFileInfo(rnd.nextInt(max), null, false, null);

                    testSerialization(rndInfo);
                    testSerialization(new GridGgfsFileInfo(rndInfo, rnd.nextInt(max)));
                    testSerialization(new GridGgfsFileInfo(rndInfo, F.asMap("desc", String.valueOf(rnd.nextLong()))));
                }

                return null;
            }
        }, 20);
    }

    /**
     * Test node info serialization.
     *
     * @param info Node info to test serialization for.
     * @throws IgniteCheckedException If failed.
     */
    public void testSerialization(GridGgfsFileInfo info) throws IgniteCheckedException {
        assertEquals(info, mu(info));
    }

    /**
     * Marshal/unmarshal object.
     *
     * @param obj Object to marshal/unmarshal.
     * @return Marshalled and then unmarshalled object.
     * @throws IgniteCheckedException In case of any marshalling exception.
     */
    private <T> T mu(T obj) throws IgniteCheckedException {
        return marshaller.unmarshal(marshaller.marshal(obj), null);
    }
}