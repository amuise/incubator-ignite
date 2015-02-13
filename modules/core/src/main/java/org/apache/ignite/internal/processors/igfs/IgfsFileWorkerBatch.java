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

package org.apache.ignite.internal.processors.igfs;

import org.apache.ignite.*;
import org.apache.ignite.igfs.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.util.typedef.internal.*;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * Work batch is an abstraction of the logically grouped tasks.
 */
public class IgfsFileWorkerBatch {
    /** Completion latch. */
    private final CountDownLatch completeLatch = new CountDownLatch(1);

    /** Finish guard. */
    private final AtomicBoolean finishGuard = new AtomicBoolean();

    /** Lock for finish operation. */
    private final ReadWriteLock finishLock = new ReentrantReadWriteLock();

    /** Tasks queue. */
    private final BlockingDeque<IgfsFileWorkerTask> queue = new LinkedBlockingDeque<>();

    /** Path to the file in the primary file system. */
    private final IgfsPath path;

    /** Output stream to the file. */
    private final OutputStream out;

    /** Caught exception. */
    private volatile IgniteCheckedException err;

    /** Last task marker. */
    private boolean lastTask;

    /**
     * Constructor.
     *
     * @param path Path to the file in the primary file system.
     * @param out Output stream opened to that file.
     */
    IgfsFileWorkerBatch(IgfsPath path, OutputStream out) {
        assert path != null;
        assert out != null;

        this.path = path;
        this.out = out;
    }

    /**
     * Perform write.
     *
     * @param data Data to be written.
     * @return {@code True} in case operation was enqueued.
     */
    boolean write(final byte[] data) {
        return addTask(new IgfsFileWorkerTask() {
            @Override public void execute() throws IgniteCheckedException {
                try {
                    out.write(data);
                }
                catch (IOException e) {
                    throw new IgniteCheckedException("Failed to write data to the file due to secondary file system " +
                        "exception: " + path, e);
                }
            }
        });
    }

    /**
     * Process the batch.
     */
    void process() {
        try {
            boolean cancelled = false;

            while (!cancelled) {
                try {
                    IgfsFileWorkerTask task = queue.poll(1000, TimeUnit.MILLISECONDS);

                    if (task == null)
                        continue;

                    task.execute();

                    if (lastTask)
                        cancelled = true;
                }
                catch (IgniteCheckedException e) {
                    err = e;

                    cancelled = true;
                }
                catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();

                    cancelled = true;
                }
            }
        }
        finally {
            try {
                onComplete();
            }
            finally {
                U.closeQuiet(out);

                completeLatch.countDown();
            }
        }
    }

    /**
     * Add the last task to that batch which will release all the resources.
     */
    @SuppressWarnings("LockAcquiredButNotSafelyReleased")
    void finish() {
        if (finishGuard.compareAndSet(false, true)) {
            finishLock.writeLock().lock();

            try {
                queue.add(new IgfsFileWorkerTask() {
                    @Override public void execute() {
                        assert queue.isEmpty();

                        lastTask = true;
                    }
                });
            }
            finally {
                finishLock.writeLock().unlock();
            }
        }
    }

    /**
     * Await for that worker batch to complete.
     *
     * @throws IgniteCheckedException In case any exception has occurred during batch tasks processing.
     */
    void await() throws IgniteCheckedException {
        try {
            completeLatch.await();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IgniteInterruptedCheckedException(e);
        }

        IgniteCheckedException err0 = err;

        if (err0 != null)
            throw err0;
    }

    /**
     * Await for that worker batch to complete in case it was marked as finished.
     *
     * @throws IgniteCheckedException In case any exception has occurred during batch tasks processing.
     */
    void awaitIfFinished() throws IgniteCheckedException {
        if (finishGuard.get())
            await();
    }

    /**
     * Get primary file system path.
     *
     * @return Primary file system path.
     */
    IgfsPath path() {
        return path;
    }

    /**
     * Callback invoked when all the tasks within the batch are completed.
     */
    protected void onComplete() {
        // No-op.
    }

    /**
     * Add task to the queue.
     *
     * @param task Task to add.
     * @return {@code True} in case the task was added to the queue.
     */
    private boolean addTask(IgfsFileWorkerTask task) {
        finishLock.readLock().lock();

        try {
            if (!finishGuard.get()) {
                try {
                    queue.put(task);

                    return true;
                }
                catch (InterruptedException ignore) {
                    // Task was not enqueued due to interruption.
                    Thread.currentThread().interrupt();

                    return false;
                }
            }
            else
                return false;

        }
        finally {
            finishLock.readLock().unlock();
        }
    }
}
