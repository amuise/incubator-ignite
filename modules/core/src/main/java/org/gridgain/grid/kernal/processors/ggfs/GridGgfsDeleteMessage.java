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

import org.apache.ignite.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.marshaller.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.util.direct.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.nio.*;

/**
 * Indicates that entry scheduled for delete was actually deleted.
 */
public class GridGgfsDeleteMessage extends GridGgfsCommunicationMessage {
    /** */
    private static final long serialVersionUID = 0L;

    /** Deleted entry ID. */
    private IgniteUuid id;

    /** Optional error. */
    @GridDirectTransient
    private IgniteCheckedException err;

    /** */
    private byte[] errBytes;

    /**
     * {@link Externalizable} support.
     */
    public GridGgfsDeleteMessage() {
        // No-op.
    }

    /**
     * Constructor.
     *
     * @param id Deleted entry ID.
     */
    public GridGgfsDeleteMessage(IgniteUuid id) {
        assert id != null;

        this.id = id;
    }

    /**
     * Constructor.
     *
     * @param id Entry ID.
     * @param err Error.
     */
    public GridGgfsDeleteMessage(IgniteUuid id, IgniteCheckedException err) {
        assert err != null;

        this.id = id;
        this.err = err;
    }

    /**
     * @return Deleted entry ID.
     */
    public IgniteUuid id() {
        return id;
    }

    /**
     * @return Error.
     */
    public IgniteCheckedException error() {
        return err;
    }

    /** {@inheritDoc} */
    @Override public void prepareMarshal(IgniteMarshaller marsh) throws IgniteCheckedException {
        super.prepareMarshal(marsh);

        if (err != null)
            errBytes = marsh.marshal(err);
    }

    /** {@inheritDoc} */
    @Override public void finishUnmarshal(IgniteMarshaller marsh, @Nullable ClassLoader ldr) throws IgniteCheckedException {
        super.finishUnmarshal(marsh, ldr);

        if (errBytes != null)
            err = marsh.unmarshal(errBytes, ldr);
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"CloneDoesntCallSuperClone", "CloneCallsConstructors"})
    @Override public GridTcpCommunicationMessageAdapter clone() {
        GridGgfsDeleteMessage _clone = new GridGgfsDeleteMessage();

        clone0(_clone);

        return _clone;
    }

    /** {@inheritDoc} */
    @Override protected void clone0(GridTcpCommunicationMessageAdapter _msg) {
        super.clone0(_msg);

        GridGgfsDeleteMessage _clone = (GridGgfsDeleteMessage)_msg;

        _clone.id = id;
        _clone.err = err;
        _clone.errBytes = errBytes;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("all")
    @Override public boolean writeTo(ByteBuffer buf) {
        commState.setBuffer(buf);

        if (!super.writeTo(buf))
            return false;

        if (!commState.typeWritten) {
            if (!commState.putByte(directType()))
                return false;

            commState.typeWritten = true;
        }

        switch (commState.idx) {
            case 0:
                if (!commState.putByteArray(errBytes))
                    return false;

                commState.idx++;

            case 1:
                if (!commState.putGridUuid(id))
                    return false;

                commState.idx++;

        }

        return true;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("all")
    @Override public boolean readFrom(ByteBuffer buf) {
        commState.setBuffer(buf);

        if (!super.readFrom(buf))
            return false;

        switch (commState.idx) {
            case 0:
                byte[] errBytes0 = commState.getByteArray();

                if (errBytes0 == BYTE_ARR_NOT_READ)
                    return false;

                errBytes = errBytes0;

                commState.idx++;

            case 1:
                IgniteUuid id0 = commState.getGridUuid();

                if (id0 == GRID_UUID_NOT_READ)
                    return false;

                id = id0;

                commState.idx++;

        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public byte directType() {
        return 68;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridGgfsDeleteMessage.class, this);
    }
}
