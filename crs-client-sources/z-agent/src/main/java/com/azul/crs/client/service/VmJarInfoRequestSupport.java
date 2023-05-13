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

import com.azul.crs.client.models.ServerRequest;
import com.azul.crs.client.models.ServerRequest.RequestCookie;

public final class VmJarInfoRequestSupport {

    private VmJarInfoRequestSupport() {
    }

    // Visible for tests
    static VmJarInfoRequest decodeVmJarInfoRequest(String data) {
        String[] parts = data.split("\\|");

        VmJarInfoRequestCookie cookie = null;
        VmJarInfoRequest.DetailsLevel detailsLevel = VmJarInfoRequest.DetailsLevel.ALL_HASHES;

        for (String part : parts) {
            String[] chunks = part.split("=", 2);
            try {
                if (chunks.length == 2) {
                    switch (chunks[0]) {
                        case "cookie":
                            cookie = new VmJarInfoRequestCookie().decode(chunks[1]);
                            break;
                        case "detailsLevel":
                            detailsLevel = VmJarInfoRequest.DetailsLevel.valueOf(chunks[1]);
                            break;
                        default:
                        // skip unknown fields without failure
                        }
                }
            } catch (Exception e) {

            }
        }

        return cookie == null ? null : new VmJarInfoRequest(cookie, detailsLevel);
    }

    /**
     * Request to calculate and send hash codes of jar entries.
     *
     * A server request is a list of N=V values separated by '|' char. Request must always contain 'cookie=[cookie]'
     * entry, where [cookie] is the one that is provided by the client in the original request. Currently no additional
     * fields are recognized and any request with the valid cookie will result in a VM_JAR_LOADED event with all
     * calculated hashes to be send to the server.
     */
    public final static class VmJarInfoRequest extends ServerRequest {

        private static void registerDecoder() {
            ServerRequest.registerDecoder("VmJarInfoRequest", VmJarInfoRequestSupport::decodeVmJarInfoRequest);
        }

        public static enum DetailsLevel {
            NONE,
            ALL_HASHES
        }

        private final DetailsLevel detailsLevel;
        private final VmJarInfoRequestCookie cookie;

        private VmJarInfoRequest(VmJarInfoRequestCookie cookie, DetailsLevel detailsLevel) {
            this.cookie = cookie;
            this.detailsLevel = detailsLevel;
        }

        public String getUrl() {
            return cookie.getUrl();
        }

        public String getPath() {
            return cookie.getPath();
        }

        public DetailsLevel getDetailsLevel() {
            return detailsLevel;
        }

        @Override
        public String toString() {
            return "VmJarInfoRequest: " + String.join("|",
                    getPath(),
                    getUrl(),
                    detailsLevel.name());
        }
    }

    /**
     * Cookie to use with VmJarInfoRequest.
     *
     * This cookie contains all information needed to identify a jar and cannot be falsified by server, as it is signed
     * with a random session key that is only known to the client. Only requests with valid cookies are processed.
     */
    public final static class VmJarInfoRequestCookie extends RequestCookie<VmJarInfoRequestCookie> {

        static {
            VmJarInfoRequest.registerDecoder();
        }

        public VmJarInfoRequestCookie(String path, String url) {
            super(new String[]{path, url});
        }

        private VmJarInfoRequestCookie() {
            super(new String[2]);
        }

        public String getPath() {
            return data[0];
        }

        public String getUrl() {
            return data[1];
        }
    }

}
