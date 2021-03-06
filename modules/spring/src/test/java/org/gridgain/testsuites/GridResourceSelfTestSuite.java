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
import org.gridgain.grid.kernal.processors.resource.*;

/**
 * Gridgain resource injection test Suite.
 */
@SuppressWarnings({"ProhibitedExceptionDeclared"})
public class GridResourceSelfTestSuite extends TestSuite {
    /**
     * @return Resource injection test suite.
     * @throws Exception If failed.
     */
    public static TestSuite suite() throws Exception {
        TestSuite suite = new TestSuite("Gridgain Resource Injection Test Suite");

        suite.addTest(new TestSuite(GridResourceFieldInjectionSelfTest.class));
        suite.addTest(new TestSuite(GridResourceFieldOverrideInjectionSelfTest.class));
        suite.addTest(new TestSuite(GridResourceMethodInjectionSelfTest.class));
        suite.addTest(new TestSuite(GridResourceMethodOverrideInjectionSelfTest.class));
        suite.addTest(new TestSuite(GridResourceProcessorSelfTest.class));
        suite.addTest(new TestSuite(GridResourceIsolatedTaskSelfTest.class));
        suite.addTest(new TestSuite(GridResourceIsolatedClassLoaderSelfTest.class));
        suite.addTest(new TestSuite(GridResourceSharedUndeploySelfTest.class));
        suite.addTest(new TestSuite(GridResourceUserExternalTest.class));
        suite.addTest(new TestSuite(GridResourceEventFilterSelfTest.class));
        suite.addTest(new TestSuite(GridLoggerInjectionSelfTest.class));
        suite.addTest(new TestSuite(GridServiceInjectionSelfTest.class));
        suite.addTest(new TestSuite(GridResourceConcurrentUndeploySelfTest.class));
        suite.addTest(new TestSuite(GridResourceIocSelfTest.class));

        return suite;
    }
}
