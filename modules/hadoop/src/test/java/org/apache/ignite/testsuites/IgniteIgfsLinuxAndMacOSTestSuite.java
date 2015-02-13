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

package org.apache.ignite.testsuites;

import junit.framework.*;
import org.apache.ignite.igfs.*;
import org.apache.ignite.internal.processors.igfs.*;
import org.apache.ignite.internal.processors.hadoop.*;

import static org.apache.ignite.testsuites.IgniteHadoopTestSuite.*;

/**
 * Test suite for Hadoop file system over Ignite cache.
 * Contains tests which works on Linux and Mac OS platform only.
 */
public class IgniteIgfsLinuxAndMacOSTestSuite extends TestSuite {
    /**
     * @return Test suite.
     * @throws Exception Thrown in case of the failure.
     */
    public static TestSuite suite() throws Exception {
        downloadHadoop();

        GridHadoopClassLoader ldr = new GridHadoopClassLoader(null);

        TestSuite suite = new TestSuite("Ignite IGFS Test Suite For Linux And Mac OS");

        suite.addTest(new TestSuite(ldr.loadClass(IgfsServerManagerIpcEndpointRegistrationOnLinuxAndMacSelfTest.class.getName())));

        suite.addTest(new TestSuite(ldr.loadClass(IgfsHadoopFileSystemShmemExternalPrimarySelfTest.class.getName())));
        suite.addTest(new TestSuite(ldr.loadClass(IgfsHadoopFileSystemShmemExternalSecondarySelfTest.class.getName())));
        suite.addTest(new TestSuite(ldr.loadClass(IgfsHadoopFileSystemShmemExternalDualSyncSelfTest.class.getName())));
        suite.addTest(new TestSuite(ldr.loadClass(IgfsHadoopFileSystemShmemExternalDualAsyncSelfTest.class.getName())));

        suite.addTest(new TestSuite(ldr.loadClass(IgfsHadoopFileSystemShmemEmbeddedPrimarySelfTest.class.getName())));
        suite.addTest(new TestSuite(ldr.loadClass(IgfsHadoopFileSystemShmemEmbeddedSecondarySelfTest.class.getName())));
        suite.addTest(new TestSuite(ldr.loadClass(IgfsHadoopFileSystemShmemEmbeddedDualSyncSelfTest.class.getName())));
        suite.addTest(new TestSuite(ldr.loadClass(IgfsHadoopFileSystemShmemEmbeddedDualAsyncSelfTest.class.getName())));

        suite.addTest(new TestSuite(ldr.loadClass(IgfsHadoopFileSystemIpcCacheSelfTest.class.getName())));

        suite.addTest(new TestSuite(ldr.loadClass(IgfsHadoop20FileSystemShmemPrimarySelfTest.class.getName())));

        suite.addTest(IgfsEventsTestSuite.suite());

        return suite;
    }
}
