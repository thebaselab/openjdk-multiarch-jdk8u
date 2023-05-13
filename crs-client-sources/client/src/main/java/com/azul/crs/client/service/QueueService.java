/*
 * Copyright 2019-2020 Azul Systems,
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.azul.crs.client.service;

import com.azul.crs.client.PerformanceMetrics;
import com.azul.crs.client.Utils.Deadline;
import com.azul.crs.util.logging.LogChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CRS client side queue to accumulate and process batches of items
 * asynchronously.
 *
 * The queue has limited capacity and drops events on overflow. The queue starts
 * concurrent workers to monitor new enqueued events and to process them in a
 * batches of max allowed size. The queue can flush all accumulated items to
 * stop workers gracefully.
 *
 * @param <T>
 */
@LogChannel("service.queue")
public final class QueueService<T> implements ClientService {

    private static final int DEFAULT_MAX_SEND_DELAY = 5_000; // default time to wait for a batch to fill up before sending it out (ms)
    private static final int DEFAULT_MAX_QUEUE_SIZE = 5_000; // default max queue size (items)
    private static final int DEFAULT_MAX_WORKERS    = 3;     // default max workers to process the queue
    private static final int DEFAULT_MAX_BATCH_SIZE = 1_000; // default max batch size to process by worker (items)
    private static final long DEFAULT_ADD_TIMEOUT   = 500;   // default timeout to add item to the queue (ms)

    private final BlockingQueue<Object> queue;
    private final List<WorkerThread> workerThreads;
    private final AtomicReference<Deadline> stopDeadline = new AtomicReference<>();
    private volatile boolean stopAcceptingItems;

    private final int maxQueueSize;
    private final int maxWorkers;
    private final int maxBatchSize;
    private final long addTimeout;
    private final long maxSendDelay;
    private final ProcessBatch<T> processBatch;
    private final String name;

    public interface ProcessBatch<T> {
        void process(String workerId, Collection<T> batch);
    }

    public static class Builder<T> {
        private int maxQueueSize = DEFAULT_MAX_QUEUE_SIZE;
        private int maxWorkers = DEFAULT_MAX_WORKERS;
        private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
        private long addTimeout = DEFAULT_ADD_TIMEOUT;
        private long maxSendDelay = DEFAULT_MAX_SEND_DELAY;
        private ProcessBatch<T> processBatch;
        private String name = "<unnamed>";

        public Builder<T> maxQueueSize(int maxQueueSize) { this.maxQueueSize = maxQueueSize; return this; }
        public Builder<T> maxWorkers(int maxWorkers) { this.maxWorkers = maxWorkers; return this; }
        public Builder<T> maxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; return this; }
        public Builder<T> addTimeout(long duration, TimeUnit units) { this.addTimeout = units.toMillis(duration); return this; }
        public Builder<T> maxSendDelay(long duration, TimeUnit units) { this.maxSendDelay = units.toMillis(duration); return this; }
        public Builder<T> processBatch(ProcessBatch<T> processBatch) { this.processBatch = processBatch; return this; }
        public Builder<T> name(String name) { this.name = name; return this; }
        private void notNull(Object o) { o.getClass(); }

        QueueService<T> build() {
            notNull(processBatch);
            return new QueueService<>(
                    maxQueueSize, maxWorkers, maxBatchSize,
                    addTimeout, maxSendDelay,
                    processBatch, name
            );
        }
    }

    private QueueService(int maxQueueSize, int maxWorkers, int maxBatchSize,
            long addTimeout, long maxSendDelay,
            ProcessBatch<T> processBatch, String name) {
        this.maxQueueSize = maxQueueSize;
        this.maxWorkers = maxWorkers;
        this.maxBatchSize = maxBatchSize;
        this.addTimeout = addTimeout;
        this.maxSendDelay = maxSendDelay;
        this.processBatch = processBatch;

        this.queue = new LinkedBlockingDeque<>(maxQueueSize);
        this.workerThreads = new LinkedList<>();
        this.name = name;
    }

    @Override
    public String serviceName() {
        return ClientService.super.serviceName() + " [" + name + "]";
    }

    /**
     * Initializes configured number of workers and starts concurrent queue
     * processing.
     *
     * @throws IllegalStateException if was already called.
     */
    @Override
    public void start() {
        synchronized (workerThreads) {
            if (stopDeadline.get() != null) {
                return;
            }

            if (!workerThreads.isEmpty()) {
                throw new IllegalStateException(serviceName() + " has been started already");
            }

            for (int i = 0; i < maxWorkers; i++) {
                WorkerThread w = new WorkerThread(String.valueOf(i));
                workerThreads.add(w);
                w.start();
            }
        }
    }

    /**
     * Tries to gracefully stop queue service by processing posted events.
     *
     * Events may be dropped if provided deadline expires before processing is
     * complete. Does nothing if was already stopped.
     *
     * @throws NullPointerException if deadline is {@code null}
     *
     * @param deadline deadline when events processing should be aborted if not
     * able to complete
     */
    @Override
    public void stop(Deadline deadline) {
        if (deadline == null) {
            throw new NullPointerException();
        }

        if (stopDeadline.compareAndSet(null, deadline)) {
            /**
             * Wait for workers to exit.
             *
             * Workers are not forcibly terminated here. We wait until the
             * deadline is expired and allow VM to proceed with termination
             * (workers do not prevent VM from exiting). Just to give them a
             * small chance to send as many as they can.
             *
             * Hence there is a (small) race here - as threads are allowed to
             * try to complete sendBatch, the final statistics report may
             * contain LESS events than actually were sent to the server if the
             * deadline is missed.
             */
            synchronized (workerThreads) {
                sync(true);
                workerThreads.forEach(t -> t.join(deadline));
            }

            /**
             * Do the final sync.
             *
             * While sync is performed items still can be added (if time
             * permits). Actually events processing itself adds some logging.
             * Now, if we still have time to drain whatever events we have - do
             * this. But before that stop accepting new items.
             */
            stopAcceptingItems = true;
            List<T> batch = workerThreads.stream().findAny().map(w -> w.batch).orElse(new ArrayList<>());
            while (!deadline.hasExpired() && !queue.isEmpty()) {
                int num = queue.drainTo((Collection) batch, maxBatchSize);
                if (num == 0) {
                    break;
                }
                processBatch.process("main", batch);
                batch.clear();
            }
        }
    }

    /**
     * Stops queue service without processing already posted events.
     *
     * Equivalent to the {@code stop(Deadline.in(0, MILLISECONDS))} call.
     */
    public void cancel() {
        stop(Deadline.in(0, MILLISECONDS));
    }

    private final AtomicInteger recursionCounter = new AtomicInteger();
    /**
     * Tries to add new item to the queue, waiting up to allowed time if
     * necessary for space to become available.
     *
     * Allowed time is either {@code addTimeout} or stop deadline remainder -
     * whichever is smaller.
     *
     * @param item item to add
     * @return {@code true} if item was enqueued or {@code false} otherwise.
     */
    public boolean add(T item) {
        Deadline deadline = stopDeadline.get();

        if (deadline != null && stopAcceptingItems) {
            return false;
        }

        long timeout = deadline == null ? addTimeout : deadline.remainder(MILLISECONDS);

        try {
            if (queue.offer(item, timeout, MILLISECONDS)) {
                return true;
            }
            try {
                if (recursionCounter.getAndIncrement() == 0) {
                    // Logging error will cause ARTIFACT_DATA event to be sent (crs.log)
                    // This will end up in this method again. To avoid recursion (that can lead to StackOverflow if polling thread
                    // is not fast enough to free some space in the queue), report the error only once.
                    logger().error(String.format(
                            "QueueService %s: failed to enqueue an item. queueSize=%d, maxQueueSize=%d, timeout=%d, item=%s",
                            name, queue.size(), maxQueueSize, timeout, item)
                    );
                }
            } finally {
                recursionCounter.decrementAndGet();
            }
        } catch (InterruptedException ie) {
            // not expected, clear interrupted state.
            Thread.interrupted();
        } finally {
            PerformanceMetrics.logEventQueueLength(queue.size());
        }

        return false;
    }

    /**
     * Trigger previously enqueued data processing without waiting for
     * processing to complete.
     *
     * In normal mode workers will accumulate data for processing until either
     * they fill their buffers or more than {@code maxSendDelay} has passed
     * since the previous batch processing. This method allows to process
     * already enqueued events without waiting for the forementioned events.
     */
    public void sync() {
        sync(false);
    }

    private void sync(boolean stop) {
        synchronized (workerThreads) {
            Marker marker = new Marker(workerThreads.size(), stop);
            try {
                /**
                 * Notifying workers to force post accumulated data.
                 *
                 * All workers may be sleeping on queue.poll() if all enqueued
                 * events have been processed by this moment. Need to wake them
                 * up - for this will add stop markers. It is guaranteed that
                 * workers do not take more than one marker each.
                 */
                for (WorkerThread wt : workerThreads) {
                    queue.put(marker);
                }
            } catch (InterruptedException ex) {
                // not expected, clear interrupted state.
                Thread.interrupted();
            }
        }
    }

    private final class WorkerThread extends Thread {

        private final String id;
        private final List<T> batch = new ArrayList<>(maxBatchSize);

        public WorkerThread(String workerId) {
            super("CRSQW-" + name + workerId);
            setDaemon(true);
            id = workerId;
        }

        /**
         * Main loop of the worker polls item batches and processes them
         */
        @Override
        public void run() {
            Object item;
            Deadline sendDeadline = Deadline.in(maxSendDelay, MILLISECONDS);

            while (true) {
                try {
                    // Wait for first item available in the queue
                    try {
                        item = queue.poll(sendDeadline.remainder(MILLISECONDS), MILLISECONDS);
                    } catch (InterruptedException th) {
                        // the thread may be waked-up by a sync request
                        Thread.interrupted();
                        item = null;
                    }

                    if (item instanceof Marker) {
                        Marker marker = (Marker) item;
                        marker.countDown();
                        postBatch();

                        if (marker.stop) {
                            break;
                        }

                        // if stop is not requested, make sure not to
                        // re-poll until other threads see their marker
                        try {
                            marker.await();
                        } catch (InterruptedException ex) {
                            // not expected, clear interrupted state.
                            Thread.interrupted();
                        }
                    } else {
                        if (item != null) {
                            batch.add((T) item);
                        }
                        boolean deadlineExpired = sendDeadline.hasExpired();

                        if (deadlineExpired || batch.size() == maxBatchSize) {
                            postBatch();
                        }

                        if (deadlineExpired) {
                            sendDeadline = Deadline.in(maxSendDelay, MILLISECONDS);
                        }
                    }
                } catch (Throwable th) {
                    logger().error("QueueService Worker [%s:%s] - internal error or unexpected problem. %s", name, id, th);
                }
            }

            logger().debug("QueueService Worker [%s:%s] has exited", name, id);
        }

        /**
         * Post accumulated batch of events
         */
        private void postBatch() {
            if (!batch.isEmpty()) {
                processBatch.process(id, batch);
                batch.clear();
            }
        }

        private void join(Deadline deadline) {
            try {
                join(Math.max(1, deadline.remainder(MILLISECONDS)));
            } catch (InterruptedException ex) {
                // not expected, clear interrupted state.
                Thread.interrupted();
            }
        }
    }

    private final static class Marker extends CountDownLatch {

        private final boolean stop;

        public Marker(int count, boolean stop) {
            super(count);
            this.stop = stop;
        }

    }
}
