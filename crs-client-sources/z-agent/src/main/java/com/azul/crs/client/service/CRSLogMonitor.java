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
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class CRSLogMonitor implements ClientService {

    private static final int INITIAL_BUFFER_SIZE = 4 * 1024;
    private static final String OVERFLOW_WARNING = "... [truncated] ...";
    private static final Charset CHARSET = StandardCharsets.UTF_8;

    // Used to accumulate log entries before connectinon to CRS has been established
    private final AtomicReference<ByteBuffer> bufferRef;
    private final AtomicBoolean overflow = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicInteger artifactID = new AtomicInteger(0);
    private final AtomicReference<Client> clientRef = new AtomicReference<>();

    public CRSLogMonitor() {
        ByteBuffer buffer = ByteBuffer.allocate(INITIAL_BUFFER_SIZE);
        buffer.limit(INITIAL_BUFFER_SIZE - OVERFLOW_WARNING.length());
        bufferRef = new AtomicReference<>(buffer);
    }

    public void setClient(Client client) {
        if (!clientRef.compareAndSet(null, client)) {
            throw new IllegalStateException(serviceName() + " client is defined already");
        }
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException(serviceName() + " is running already");
        }
        Client client = clientRef.get();
        if (client == null) {
            throw new IllegalStateException(serviceName() + " starting without client defined");
        }
        int id = client.createArtifactId();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", "crs.log");
        metadata.put("tags", Inventory.instanceTags());
        client.postVMArtifactCreate(VMArtifact.Type.CRS_LOG, id, metadata);
        if (!artifactID.compareAndSet(0, id)) {
            throw new IllegalStateException(serviceName() + " artifactID is set already");
        }
    }

    @Override
    public void stop(Deadline deadline) {
        stopped.set(true);
    }

    public void notifyCRSLogEntry(byte[] buf, int offset, int len) {
        if (stopped.get()) {
            return;
        }

        int id = artifactID.get();
        ByteBuffer buffer = bufferRef.get();

        if (id == 0) {
            if (!overflow.get()) {
                synchronized (overflow) {
                    if (!overflow.get() && buffer.remaining() > len) {
                        buffer.put(buf, offset, len);
                    } else {
                        buffer.limit(buffer.capacity());
                        buffer.put(OVERFLOW_WARNING.getBytes());
                        overflow.set(true);
                    }
                }
            }
        } else {
            Client client = clientRef.get();

            if (buffer != null) {
                if (bufferRef.compareAndSet(buffer, null)) {
                    client.postVMArtifactData(id, new String(buffer.array(), 0, buffer.position(), CHARSET));
                }
            }

            client.postVMArtifactData(id, new String(buf, offset, len, CHARSET));
        }
    }
}
