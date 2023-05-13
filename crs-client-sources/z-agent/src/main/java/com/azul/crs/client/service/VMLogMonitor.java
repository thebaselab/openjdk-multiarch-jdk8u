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
import com.azul.crs.util.logging.LogChannel;
import com.azul.crs.util.logging.Logger;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

@LogChannel("service.vmlog")
public final class VMLogMonitor implements ClientService {

    private final static class VMArtifactInfo {

        private final Integer id;
        private final AtomicLong eventsCount = new AtomicLong();
        private final AtomicLong bytesSent = new AtomicLong();

        public VMArtifactInfo(Integer id) {
            this.id = id;
        }
    }

    private final ConcurrentHashMap<String, VMArtifactInfo> artifacts = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Client client;

    public static VMLogMonitor getInstance(Client client) {
        return new VMLogMonitor(client);
    }

    private VMLogMonitor(Client client) {
        this.client = client;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException(serviceName() + " is running already");
        }
    }

    @Override
    public void stop(Deadline deadline) {
        if (running.compareAndSet(true, false)) {
            logger().info("VMLogMonitor stopped.");
            if (logger().isEnabled(Logger.Level.TRACE)) {
                for (Map.Entry<String, VMArtifactInfo> entry : artifacts.entrySet()) {
                    VMArtifactInfo info = entry.getValue();
                    logger().info("VMLog '%s' (%d) events: %,d; bytes: %,d",
                            entry.getKey(),
                            entry.getValue().id,
                            info.eventsCount.get(),
                            info.bytesSent.get());
                };
            }
        }
    }

    public void notifyVMLogEntry(String logName, String entry) {
        if (!running.get()) {
            return;
        }

        VMArtifactInfo info = getVMArtifactInfo(logName);

        try {
            int size = entry.length();
            long count = info.eventsCount.incrementAndGet();
            long bytes = info.bytesSent.addAndGet(size);
            client.postVMArtifactData(info.id, entry);
            logger().trace("%d: Appended to VMLog artifact %s: size=%,d bytes, reported=%,d bytes",
                    count, logName, size, bytes);
        } catch (Throwable th) {
            logger().warning("Exception occured: [%s] ", logName, th);
        }
    }

    private VMArtifactInfo getVMArtifactInfo(String logName) {
        return artifacts.computeIfAbsent(logName, new VMArtifactInfoInitializer());
    }

    private class VMArtifactInfoInitializer implements Function<String, VMArtifactInfo> {

        @Override
        public VMArtifactInfo apply(String logName) {
            Integer artifactId = client.createArtifactId();
            VMArtifactInfo info = new VMArtifactInfo(artifactId);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("name", logName);
            metadata.put("tags", Inventory.instanceTags());

            client.postVMArtifactCreate(VMArtifact.Type.GC_LOG, artifactId, metadata);
            logger().info("Created VMLog artifact %d for crsstream %s", artifactId, logName);
            return info;
        }
    }
}
