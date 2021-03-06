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

package org.gridgain.grid.kernal.processors.ggfs;

import org.apache.ignite.fs.*;
import org.gridgain.grid.util.typedef.internal.*;

import java.io.*;
import java.util.*;

/**
 * GGFS attributes.
 * <p>
 * This class contains information on a single GGFS configured on some node.
 */
public class GridGgfsAttributes implements Externalizable {
    /** */
    private static final long serialVersionUID = 0L;

    /** GGFS name. */
    private String ggfsName;

    /** File's data block size (bytes). */
    private int blockSize;

    /** Size of the group figured in {@link org.apache.ignite.fs.IgniteFsGroupDataBlocksKeyMapper}. */
    private int grpSize;

    /** Meta cache name. */
    private String metaCacheName;

    /** Data cache name. */
    private String dataCacheName;

    /** Default mode. */
    private IgniteFsMode dfltMode;

    /** Fragmentizer enabled flag. */
    private boolean fragmentizerEnabled;

    /** Path modes. */
    private Map<String, IgniteFsMode> pathModes;

    /**
     * @param ggfsName GGFS name.
     * @param blockSize File's data block size (bytes).
     * @param grpSize Size of the group figured in {@link org.apache.ignite.fs.IgniteFsGroupDataBlocksKeyMapper}.
     * @param metaCacheName Meta cache name.
     * @param dataCacheName Data cache name.
     * @param dfltMode Default mode.
     * @param pathModes Path modes.
     */
    public GridGgfsAttributes(String ggfsName, int blockSize, int grpSize, String metaCacheName, String dataCacheName,
        IgniteFsMode dfltMode, Map<String, IgniteFsMode> pathModes, boolean fragmentizerEnabled) {
        this.blockSize = blockSize;
        this.ggfsName = ggfsName;
        this.grpSize = grpSize;
        this.metaCacheName = metaCacheName;
        this.dataCacheName = dataCacheName;
        this.dfltMode = dfltMode;
        this.pathModes = pathModes;
        this.fragmentizerEnabled = fragmentizerEnabled;
    }

    /**
     * Public no-arg constructor for {@link Externalizable}.
     */
    public GridGgfsAttributes() {
        // No-op.
    }

    /**
     * @return GGFS name.
     */
    public String ggfsName() {
        return ggfsName;
    }

    /**
     * @return File's data block size (bytes).
     */
    public int blockSize() {
        return blockSize;
    }

    /**
     * @return Size of the group figured in {@link org.apache.ignite.fs.IgniteFsGroupDataBlocksKeyMapper}.
     */
    public int groupSize() {
        return grpSize;
    }

    /**
     * @return Metadata cache name.
     */
    public String metaCacheName() {
        return metaCacheName;
    }

    /**
     * @return Data cache name.
     */
    public String dataCacheName() {
        return dataCacheName;
    }

    /**
     * @return Default mode.
     */
    public IgniteFsMode defaultMode() {
        return dfltMode;
    }

    /**
     * @return Path modes.
     */
    public Map<String, IgniteFsMode> pathModes() {
        return pathModes != null ? Collections.unmodifiableMap(pathModes) : null;
    }

    /**
     * @return {@code True} if fragmentizer is enabled.
     */
    public boolean fragmentizerEnabled() {
        return fragmentizerEnabled;
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        U.writeString(out, ggfsName);
        out.writeInt(blockSize);
        out.writeInt(grpSize);
        U.writeString(out, metaCacheName);
        U.writeString(out, dataCacheName);
        U.writeEnum0(out, dfltMode);
        out.writeBoolean(fragmentizerEnabled);

        if (pathModes != null) {
            out.writeBoolean(true);

            out.writeInt(pathModes.size());

            for (Map.Entry<String, IgniteFsMode> pathMode : pathModes.entrySet()) {
                U.writeString(out, pathMode.getKey());
                U.writeEnum0(out, pathMode.getValue());
            }
        }
        else
            out.writeBoolean(false);
    }

    /** {@inheritDoc} */
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        ggfsName = U.readString(in);
        blockSize = in.readInt();
        grpSize = in.readInt();
        metaCacheName = U.readString(in);
        dataCacheName = U.readString(in);
        dfltMode = IgniteFsMode.fromOrdinal(U.readEnumOrdinal0(in));
        fragmentizerEnabled = in.readBoolean();

        if (in.readBoolean()) {
            int size = in.readInt();

            pathModes = new HashMap<>(size, 1.0f);

            for (int i = 0; i < size; i++)
                pathModes.put(U.readString(in), IgniteFsMode.fromOrdinal(U.readEnumOrdinal0(in)));
        }
    }
}
