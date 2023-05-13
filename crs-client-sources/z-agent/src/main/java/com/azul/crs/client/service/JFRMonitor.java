/*
 * Copyright 2019-2021 Azul Systems,
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

import com.azul.crs.client.Client;
import com.azul.crs.client.Inventory;
import com.azul.crs.client.Utils.Deadline;
import com.azul.crs.client.models.VMArtifact;
import com.azul.crs.jfr.access.FlightRecorderAccess;
import com.azul.crs.util.logging.LogChannel;
import com.azul.crs.util.logging.Logger;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.*;

/*
 * Service to send JFR recordings (in chunks) to CRS.
 *
 * JFRMonitor registers itself with the FlightRecorder subsystem as a
 * FlightRecorderListener. On every recording that is started, a new VMArtifact
 * of type VMArtifact.Type.JFR is created. Updates of the RecordingState are
 * reported to CRS via VMArtifact patching mechanism.
 *
 * Upload of the actual data is done using nextChunk() callback from a modified
 * version of JDK (via FlightRecorderAssociate interface)
 *
 * When nextChunk() event arrives, the chunk is locked by calling
 * useRepositoryChunk(), enqueued to the UploadService, and is released only
 * after it has been asynchronously uploaded.
 *
 */
@LogChannel("service.jfr")
public final class JFRMonitor implements ClientService, FlightRecorderListener, FlightRecorderAccess.FlightRecorderCallbacks {

    private static final String SERVICE_NAME = "client.service.JFR";
    private static final Logger logger = Logger.getLogger(JFRMonitor.class);
    private static JFRMonitor instance;
    private static final AtomicReference<Thread> initTask = new AtomicReference<>();

    private final AtomicReference<FlightRecorder> recorder = new AtomicReference<>();
    private final AtomicInteger chunkSequenceNumber = new AtomicInteger();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Map<Long, Integer> idMap = new HashMap<>();
    private final Object shutdownJfrMonitor = new Object();
    private final Client client;
    private final String params;

    private final AtomicReference<FlightRecorderAccess> accessRef = new AtomicReference<>(); // Allows to lock/release chunks

    private JFRMonitor(Client client, String params) {
        this.client = client;
        this.params = params;
    }

    public static synchronized JFRMonitor getInstance(Client client, String params) {
        if (instance == null) {
            instance = new JFRMonitor(client, params);

            // addListeners is not just adding implementors to the list, but also
            // initial notifications are send to them. The problem is that this
            // is done under locks.
            // Starting JFR's shutdownHook before this initialization is complete
            // will lead to deadlocks. The simplest approach to overcome this is
            // to move initialization to a separate thread and wait until it is
            // done. If shutdown hook is started at the same time, we are notified
            // about this via finishJoin(), where we have a chance to interrupt
            // this initialization, effectively dropping all the locks

            initTask.set(new Thread("JFRMonitor Init Thread") {
                {
                    setDaemon(true);
                }

                @Override
                public void run() {
                    FlightRecorder.addListener(instance);
                }
            });

            try {
                initTask.get().start();
                initTask.get().join();
                instance.initialized.set(true);
            } catch (InterruptedException ex) {
                logger.debug("Exception when waiting JFRMonitor initTask", ex);
            } finally {
                initTask.set(null);
            }
            return instance;
        }

        // Check whether existing instance has parameters as requested
        if (!Objects.equals(client, instance.client) || !Objects.equals(params, instance.params)) {
            throw new IllegalArgumentException(SERVICE_NAME + ": "
                    + "an instance with different parameters has already been created");
        }
        return instance;
    }

    @Override
    public String serviceName() {
        return SERVICE_NAME;
    }

    @Override
    public void start() {

        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException(SERVICE_NAME + " has already been started");
        }
        if (initialized.get()) {
            maybeStartLifetimeRecording(params);
        }
    }

    @Override
    public void stop(Deadline deadline) {
        if (!stopped.compareAndSet(false, true)) {
            throw new IllegalStateException(SERVICE_NAME + " has already been stopped");
        }

        synchronized (shutdownJfrMonitor) {
            while (recorder.get() != null && !deadline.hasExpired()) {
                logger.debug("Waiting for jfr to shutdown");
                try {
                    shutdownJfrMonitor.wait(Math.max(1, deadline.remainder(TimeUnit.MILLISECONDS)));
                } catch (InterruptedException ignored) {
                    logger.debug("jfr shutdown waiting thread has been interrupted");
                    Thread.interrupted();
                    break;
                }
            }
        }
        logger.debug("Unblocked CRS client shutdown");
    }

    @Override
    public void recorderInitialized(FlightRecorder recorder) {
        // Called only once for the recorder (or when listener is added)
        if (!this.recorder.compareAndSet(null, recorder)) {
            throw new IllegalStateException("recorderInitialized is expected to be called only once");
        }

        try {
            setAccess(FlightRecorderAccess.getAccess(recorder, JFRMonitor.this));
        } catch (Throwable ex) {
            this.recorder.set(null);
            FlightRecorder.removeListener(instance);
            logger.error("Cannot install associate to JFR: %s", ex.toString());
            return;
        }

        // Make sure to register already started recordings
        for (Recording recording : recorder.getRecordings()) {
            recordingStateChanged(recording);
        }
    }

    @Override
    public void recordingStateChanged(final Recording recording) {
        // Note: this method is called from multiple threads
        logger.debug("recording %s state changed to %s",
                getRecordingName(recording), recording.getState());

        try {
            createOrUpdate(recording);
        } catch (Throwable th) {
            logger.error("Exception %s", th.getMessage(), th);
        }
    }

    @Override
    public void nextChunk(final Object chunk, final Path path, final Instant startTime, final Instant endTime, final long size, final Recording ignoreMe) {
        // Note: this method is called from multiple threads
        lockRepositoryChunk(chunk);

        final List<Long> recordingIds = new ArrayList<>();
        for (Recording r : recorder.get().getRecordings()) {
            long id = r.getId();
            // sometimes JFR reports "ghost" recordings which does not have corresponding
            // notifyRecordingStateChanged() listener method called (and neither this recording is
            // reported in JFR's own debug log). ignore such recordings
            if (r != ignoreMe && idMap.containsKey(id)) {
                recordingIds.add(id);
            }
        }

        if (recordingIds.isEmpty()) {
            logger.warning("No active record for the chunk");
            return;
        }

        enqueuePostVMArtifactChunk(chunk, path, startTime, endTime == null ? Instant.now() : endTime, size, recordingIds);
    }

    @Override
    public void finishJoin() {
        // Called by shutdown hook from the synchronized (on recorder) method
        // PlatformRecorder.destroy
        logger.debug("shutting down JFR " + System.currentTimeMillis());

        try {
            Thread task = initTask.get();

            if (task != null) {
                task.interrupt();
                logger.warning("JFR stopped before JFRMonitor was fully initialized.");
            } else {
                // Will report only if initialization is complete
                for (Recording r : recorder.get().getRecordings()) {
                    r.close();
                }
                client.finishChunkPost();
            }
        } finally {
            synchronized (shutdownJfrMonitor) {
                recorder.set(null);
                shutdownJfrMonitor.notify();
            }
        }

        logger.debug("JFR tracking finished " + System.currentTimeMillis());
    }

    // This method enqueues asynchronous DDB update and file upload tasks
    private void enqueuePostVMArtifactChunk(Object chunk, Path path, Instant startTime, Instant endTime, long size, Collection<Long> recordingIds) {
        logger.debug("Enqueuing chunk data record [%s, size %d], Recordings: %s",
                path.toString(), size, Arrays.toString(recordingIds.toArray()));

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("startTime", startTime.toEpochMilli());
        attributes.put("endTime", endTime.toEpochMilli());
        attributes.put("size", size);
        attributes.put("path", path.toString());
        // Sequence number grows monotonically, but any given artifact
        // (recording) may have 'holes' in this numbering.
        attributes.put("sequenceNumber", Integer.toString(chunkSequenceNumber.incrementAndGet()));

        Set<Integer> artifactIds = new HashSet<>(recordingIds.size());
        recordingIds.forEach(id -> artifactIds.add(idMap.get(id)));

        client.postVMArtifactChunk(artifactIds, attributes, output -> {
            try {
                Files.copy(path, output);
            } catch (IOException ex) {
                logger.warning("Failed to send recording chunk %s: %s%s",
                        path.toString(), ex, Client.isVMShutdownInitiated() ? " (expected during shutdown if timeout is exceeded)" : "");
            } finally {
                releaseRepositoryChunk(chunk);
            }
        });
    }

    private void setAccess(FlightRecorderAccess access) {
        this.accessRef.set(access);
    }

    private void lockRepositoryChunk(Object chunk) {
        try {
            logger.debug("locking chunk %s", chunk);
            accessRef.get().useRepositoryChunk(chunk);
        } catch (FlightRecorderAccess.AccessException shouldnothappen) {
            shouldnothappen.printStackTrace();
        }
    }

    private void releaseRepositoryChunk(Object chunk) {
        try {
            logger.debug("releasing chunk %s", chunk);
            accessRef.get().releaseRepositoryChunk(chunk);
        } catch (FlightRecorderAccess.AccessException shouldnothappen) {
            shouldnothappen.printStackTrace();
        }
    }

    private static void maybeStartLifetimeRecording(String params) {
        if (null == params || "disable".equals(params)) {
            logger.info("lifetime recording is disabled");
            return;
        }

        if (!FlightRecorder.isAvailable()) {
            logger.warning("lifetime recording is not available");
            return;
        }

        Recording recording;
        if (params.isEmpty()) {
            recording = new Recording();
            logger.info("started lifetime recording with empty configuration");
        } else {
            try {
                recording = new Recording(Configuration.create(new File(params).toPath()));
                logger.info("started lifetime recording with configuration from %s", params);
            } catch (IOException | ParseException ex) {
                logger.error("cannot read or parse specified JFR configuration file %s. recording stopped", params);
                return;
            }
        }

        recording.setName("lifetime recording");

        // scheduling start moves it to a separate dedicated thread
        // this allows to avoid deadlocks
        recording.scheduleStart(Duration.ZERO);
    }

    private String getRecordingName(Recording recording) {
        String name = recording.getName();
        if (!Long.toString(recording.getId()).equals(name)) {
            return name;
        }
        Path path = recording.getDestination();
        return path == null ? name : path.getFileName().toString();
    }

    private void createOrUpdate(Recording recording) {
        // No actual data is sent in this method - only events enqueuing.

        Map<String, Object> attributes = new HashMap<>();
        RecordingState state = recording.getState();

        attributes.put("state", state.name());
        if (state == RecordingState.STOPPED || state == RecordingState.CLOSED) {
            attributes.put("stopTime", recording.getStopTime().toEpochMilli());
        }

        long id = recording.getId();

        Integer old_id;
        int new_id = -1;

        synchronized (idMap) {
            old_id = idMap.get(id);

            if (old_id == null) {
                new_id = client.createArtifactId();
                idMap.put(id, new_id);
            }
        }

        if (new_id > 0) {
            attributes.put("name", getRecordingName(recording));
            attributes.put("tags", Inventory.instanceTags());
            attributes.put("startTime", recording.getStartTime().toEpochMilli());
            Path path = recording.getDestination();
            if (path != null) {
                attributes.put("destination", path.toString());
            }
            // Enqueue asynchronous POST request
            client.postVMArtifactCreate(VMArtifact.Type.JFR, new_id, attributes);
            logger.debug("Enqueued VMArtifact creation [id: %d, crs_id: %d]", id, new_id);
        } else {
            // Enqueue asynchronous POST request
            client.postVMArtifactPatch(VMArtifact.Type.JFR, old_id, attributes);
            logger.debug("Enqueued VMArtifact patching [id: %d, crs_id: %d]", id, old_id);
        }
    }
}
