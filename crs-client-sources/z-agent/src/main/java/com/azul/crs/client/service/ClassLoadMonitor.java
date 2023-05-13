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
import com.azul.crs.client.Utils.Deadline;
import com.azul.crs.client.models.VMEvent;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static com.azul.crs.client.Utils.encodeToStringOrNull;
import static com.azul.crs.client.Utils.currentTimeMillis;
import static com.azul.crs.client.models.VMEvent.Type.*;
import com.azul.crs.util.logging.Logger;
import com.azul.crs.util.logging.LogChannel;

@LogChannel("service.classload")
public class ClassLoadMonitor implements ClientService {

    private final static ClassLoadMonitor instance = new ClassLoadMonitor();
    private Client client;
    private volatile boolean started, stopped;
    private final PrintWriter traceOut;

    private ClassLoadMonitor() {
        PrintWriter out = null;
        if (logger().isEnabled(Logger.Level.TRACE)) {
            try {
                Path traceOutFileName = Files.createTempFile("CRSClassLoadMonitor", ".log");
                logger().trace("writing ClassLoadMonitor trace to file %s", traceOutFileName);
                out = new PrintWriter(Files.newBufferedWriter(traceOutFileName));
            } catch (IOException ignored) {
                ignored.printStackTrace(System.err);
            }
        }
        traceOut = out;
    }

    public static ClassLoadMonitor getInstance(Client client) {
        instance.client = client;
        return instance;
    }

    /** Creates VM event object with given class load details */
    private VMEvent classLoadEvent(String className, String originalHashString, String hashString, int classId, int loaderId, String source, long eventTime) {
        Map<String, String> payload = new HashMap<>();
        payload.put("className", className);
        // to minimize modifications to the cloud part instead of using "original"/"actual" hash pair we use
        // "transformed"/"original" instead
        if (originalHashString != null)
            payload.put("transformedHash", hashString);
        payload.put("hash", originalHashString != null ? originalHashString : hashString);
        payload.put("classId", Integer.toString(classId));
        payload.put("loaderId", Integer.toString(loaderId));
        if (source != null)
            payload.put("source", source);

        return new VMEvent<>()
                .eventType(VM_CLASS_LOADED)
                .eventTime(eventTime)
                .eventPayload(payload);
    }

    @Override
    public synchronized void start() {
        started = true;
    }

    @Override
    public synchronized void stop(Deadline deadline) {
        if (traceOut != null)
            traceOut.close();

        started = false;
        stopped = true;
    }

    /**
     * VM callback invoked each time class is loaded.
     *
     * className name of the loaded class
     * originalHash SHA-256 hash of the class file before transformation if it is transformed, otherwise null
     * hash SHA-256 hash of the class file as it is used by the VM
     * classId the unique id of the class
     */
    public void notifyClassLoad(String className, byte[] originalHash, byte[] hash, int classId, int loaderId, String source) {
        if (stopped)
            return;
        if (!started) {
            logger().error("service is not yet started");
        }

        long eventTime = currentTimeMillis();
        String originalHashString = encodeToStringOrNull(originalHash);
        String hashString = encodeToStringOrNull(hash);
        client.postVMEvent(classLoadEvent(className, originalHashString, hashString, classId, loaderId, source, eventTime));

        if (traceOut != null)
            traceOut.printf("%s [%d:%d]\n", className, loaderId, classId);
    }
}
