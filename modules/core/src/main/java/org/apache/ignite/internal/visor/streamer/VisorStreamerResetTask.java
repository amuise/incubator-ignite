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

package org.apache.ignite.internal.visor.streamer;

import org.apache.ignite.*;
import org.apache.ignite.internal.processors.task.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.internal.visor.*;

import static org.apache.ignite.internal.visor.util.VisorTaskUtils.*;

/**
 * Task for reset specified streamer.
 */
@GridInternal
public class VisorStreamerResetTask extends VisorOneNodeTask<String, Void> {
    /** */
    private static final long serialVersionUID = 0L;

    /** {@inheritDoc} */
    @Override protected VisorStreamerResetJob job(String arg) {
        return new VisorStreamerResetJob(arg, debug);
    }

    /**
     * Job that reset streamer.
     */
    private static class VisorStreamerResetJob extends VisorJob<String, Void> {
        /** */
        private static final long serialVersionUID = 0L;

        /**
         * @param arg Streamer name.
         * @param debug Debug flag.
         */
        private VisorStreamerResetJob(String arg, boolean debug) {
            super(arg, debug);
        }

        /** {@inheritDoc} */
        @Override protected Void run(String streamerName) {
            try {
                IgniteStreamer streamer = ignite.streamer(streamerName);

                streamer.reset();

                return null;
            }
            catch (IllegalArgumentException iae) {
                throw new IgniteException("Failed to reset streamer: " + escapeName(streamerName)
                    + " on node: " + ignite.localNode().id(), iae);
            }
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(VisorStreamerResetJob.class, this);
        }
    }
}
