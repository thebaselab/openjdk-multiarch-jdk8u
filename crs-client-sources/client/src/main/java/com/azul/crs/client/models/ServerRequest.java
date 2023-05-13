/*
 * Copyright 2022 Azul Systems,
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
package com.azul.crs.client.models;

import com.azul.crs.client.Utils;
import com.azul.crs.client.models.ServerRequest.RequestCookie;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ServerRequest is sent to client in order to get additional information about resources reported.
 *
 * Currently only one type of events is supported - VmJarInfoRequest
 */
public abstract class ServerRequest {

    public static final String REQUEST_COOKIE_KEY = "requestCookie";
    private static final ConcurrentHashMap<String, ServerRequestDecoder> decoders = new ConcurrentHashMap<>();

    protected static void registerDecoder(String id, ServerRequestDecoder decoder) {
        decoders.putIfAbsent(id, decoder);
    }

    public static ServerRequest parse(String raw) {
        ServerRequestDecoder decoder = null;
        String[] parts = raw.split("\\|", 2);
        if (parts.length == 2) {
            decoder = decoders.get(parts[0]);
        }
        return decoder == null ? null : decoder.decode(parts[1]);
    }

    @FunctionalInterface
    protected static interface ServerRequestDecoder {

        public ServerRequest decode(String cookie);
    }

    public static abstract class RequestCookie<C extends RequestCookie> {

        protected final String[] data;

        public RequestCookie(String[] data) {
            this.data = data;
        }

        public final String encode() {
            String resource = String.join("|", data);
            String digest = Utils.Digest.digestBase64(resource);
            return digest == null
                    ? null
                    : Base64.getEncoder().encodeToString(String.join("|", digest, resource).getBytes());
        }

        public final C decode(String encoded) throws IllegalArgumentException {
            String decoded = new String(Base64.getDecoder().decode(encoded));
            int idx = decoded.indexOf('|');
            if (idx > 0) {
                String digest = decoded.substring(0, idx);
                String fields = decoded.substring(idx + 1);

                if (digest.equals(Utils.Digest.digestBase64(fields))) {
                    String[] d = fields.split("\\|");
                    if (d.length == data.length) {
                        System.arraycopy(d, 0, data, 0, d.length);
                        return (C) this;
                    }
                }
            }
            return null;
        }
    }
}
