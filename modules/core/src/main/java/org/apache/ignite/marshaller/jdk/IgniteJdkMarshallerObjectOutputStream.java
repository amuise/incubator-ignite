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

package org.apache.ignite.marshaller.jdk;

import org.apache.ignite.marshaller.*;
import org.jetbrains.annotations.*;

import java.io.*;

/**
 * This class defines own object output stream.
 */
class IgniteJdkMarshallerObjectOutputStream extends ObjectOutputStream {
    /**
     * @param out Output stream.
     * @throws IOException Thrown in case of any I/O errors.
     */
    IgniteJdkMarshallerObjectOutputStream(OutputStream out) throws IOException {
        super(out);

        enableReplaceObject(true);
    }

    /** {@inheritDoc} */
    @Nullable @Override protected Object replaceObject(Object o) throws IOException {
        return o == null || IgniteMarshallerExclusions.isExcluded(o.getClass()) ? null :
            o.getClass().equals(Object.class) ? new IgniteJdkMarshallerDummySerializable() : super.replaceObject(o);
    }
}

