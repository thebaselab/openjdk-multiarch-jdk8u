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
package com.azul.crs.client;

import com.azul.crs.util.logging.LogChannel;
import com.azul.crs.util.logging.Logger;
import static com.azul.crs.util.logging.Logger.Level.TRACE;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.CharBuffer;

@LogChannel(value = "vmsupport", lowestUpstreamLevel = TRACE)
public final class VMSupport {

    private static final int CRS_CMD_BUF_SIZE = 1024;
    private static final int CRS_CMD_LEN_SIZE = 4;

    private static final Logger logger = Logger.getLogger(VMSupport.class);
    private final BufferedReader in;
    private final BufferedWriter out;
    private final char[] buffer = new char[CRS_CMD_BUF_SIZE];
    private final CharBuffer cb = CharBuffer.wrap(buffer);

    enum CrsNotificationType {
        EVENT_TO_JAVA_CALL(-98),
        CRS_MESSAGE_CLASS_LOAD(0),
        CRS_MESSAGE_FIRST_CALL(1),
        CRS_MESSAGE_VM_LOG_ENTRY(2);
        private final int id;

        private CrsNotificationType(int id) {
            this.id = id;
        }
    }

    private VMSupport(InputStream is, OutputStream os) {
        this.in = new BufferedReader(new InputStreamReader(is));
        this.out = new BufferedWriter(new OutputStreamWriter(os));
    }

    static VMSupport init(int port, int secret) throws IOException {
        final int retries = 10;
        IOException exception = null;

        for (int i = 1; i <= retries; i++) {
            try {
                Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                InputStream is = socket.getInputStream();
                OutputStream os = socket.getOutputStream();
                VMSupport support = new VMSupport(is, os);
                // Send authentication token
                support.send(Integer.toString(secret));
                return support;
            } catch (IOException ex) {
                logger.trace("Attempt %d to connect to VM failed (will retry %d more times).", i, retries - i);
                exception = ex;
            }
            try {
                Thread.sleep(50 * i);
            } catch (InterruptedException ex) {
                Thread.interrupted();
                exception = new IOException(ex);
                logger.trace("VMSupport initialization interrupted");
                break;
            }
        }

        throw exception;
    }

    void disableCRS() {
        sendCommand("disableCRS");
    }

    void registerAgent(Class klass) {
        sendCommand("registerAgent", klass.getCanonicalName());
    }

    void enableEventNotifications(CrsNotificationType event, boolean enabled) {
        sendCommand("enableEventNotifications", Integer.toString(event.id), enabled ? "1" : "0");
    }

    void drainQueues(boolean force, boolean stopAfterDrain) {
        // TODO: this is a temporary hack to avoid CRS-4042
        if (stopAfterDrain && System.getProperty("os.name").toLowerCase().contains("win")) {
            stopAfterDrain = false;
        }
        sendCommand("drainQueues", force ? "1" : "0", stopAfterDrain ? "1" : "0");
    }

    void registerCallback(CrsNotificationType event, Method callbackFunction) {
        sendCommand("registerCallback", Integer.toString(event.id), callbackFunction.getDeclaringClass().getCanonicalName() + "." + callbackFunction.getName());
    }

    String[] getVMCRSCapabilities() {
        String result = sendCommand("getCapabilities");
        return result.isEmpty() ? null : result.split("[, ]+");
    }

    private synchronized String sendCommand(String command, String... args) {
        try {
            cb.clear();
            cb.append(command).append("(");
            if (args.length > 0) {
                cb.append(args[0]);
                for (int i = 1; i < args.length; i++) {
                    cb.append(',').append(args[i]);
                }
            }
            cb.append(")");
            cb.flip();

            return send(cb);
        } catch (IOException ex) {
            logger.debug("Exception in sendCommand", ex);
        }
        return "";
    }

    private synchronized String send(CharSequence msg) throws IOException {
        char[] msg_len = new char[CRS_CMD_LEN_SIZE];
        int len = msg.length();

        int idx = CRS_CMD_LEN_SIZE;
        while (idx > 0) {
            msg_len[--idx] = (char) ('0' + (len % 10));
            len /= 10;
        }

        out.append(new String(msg_len));
        out.append(msg);
        out.flush();

        int responseLength = Integer.parseInt(read(CRS_CMD_LEN_SIZE).trim());
        return (responseLength > 0) ? read(responseLength) : "";
    }

    private String read(int size) throws IOException {
        cb.clear();
        int toread = size;
        int offset = 0;
        while (toread > 0) {
            int read = in.read(cb.array(), offset, toread);
            if (read == -1) {
                throw new IOException();
            }
            toread -= read;
            offset += read;
        }
        return cb.subSequence(0, size).toString();
    }
}
