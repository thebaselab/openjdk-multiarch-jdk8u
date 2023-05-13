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
package com.azul.crs.runtime.utils;

import java.util.Arrays;
import java.util.List;

public final class URLHelper {

    private static final List<String> containerExtensions = Arrays.asList(".jar", ".war", ".ear");
    private static final List<String> containerProtos = Arrays.asList("jar:", "file:");
    private static final String containerProtoPattern = "^[a-z]{3,}:.+";

    private URLHelper() {
    }

    /**
     * Converts path/URL to a comparable and platform-independent url-like
     * notation. If passed argument is not a path/url, then unchanged value is
     * returned.
     *
     * Note: this method is not generic and handles only variations that can be
     * reported by the CRS agent.
     *
     * @param pathOrURL string to convert
     * @return platform-independent url-like string
     * @see URLHelperTest
     */
    public static String toNormalizedURL(String pathOrURL) {
        if (pathOrURL == null) {
            return null;
        }
        if (!(pathOrURL.contains("/") || pathOrURL.contains("\\"))) {
            return pathOrURL;
        }
        boolean startsWithProto = pathOrURL.matches(containerProtoPattern);
        return (startsWithProto ? pathOrURL : "file:/" + pathOrURL)
                .replace('\\', '/')
                .replaceAll("/+", "/")
                .replace(" ", "%20");
    }

    public static String toNormalizedJarURL(String pathOrURL) {
        pathOrURL = toNormalizedURL(pathOrURL);

        if (!pathOrURL.startsWith("jar:")) {
            pathOrURL = "jar:" + pathOrURL;
        }

        int idx = pathOrURL.lastIndexOf(".jar");

        if (idx > 0 && !pathOrURL.endsWith(".jar!/")) {
            pathOrURL = pathOrURL.substring(0, idx) + ".jar!/";
        }

        return pathOrURL;
    }

    /**
     * Extracts path to a container from a given normalized URL.
     *
     * Note: this method is not generic and handles only variations that can be
     * reported by the CRS agent.
     *
     * @param url URL with a path to a container (and, possibly, extra data)
     * @return extracted container path in platform-independent format
     * @see URLHelperTest
     */
    public static String extractContainerPathFromURL(String url) {
        if (url == null) {
            return null;
        }

        int s = 0, e = url.length();

        strip_schema:
        while (true) {
            for (String proto : containerProtos) {
                int pl = proto.length();
                if (url.regionMatches(s, proto, 0, pl)) {
                    s += pl;
                    continue strip_schema;
                }
            }
            break;
        }

        // only convert urls with (known) schemas
        if (s == 0) {
            return url;
        }

        // strip trailing !/
        if (url.regionMatches(e - 2, "!/", 0, 2)) {
            e -= 2;
        }

        int tmp = e;
        boolean endsWithKnownExtension = containerExtensions.stream().anyMatch(ext -> {
            int el = ext.length();
            return url.regionMatches(tmp - el, ext, 0, el);
        });

        // if not ends with any of the known extensions - try to trim at the
        // farthest '<ext>!/'.
        if (!endsWithKnownExtension) {
            int pos = containerExtensions.stream().mapToInt(ext -> {
                int idx = url.lastIndexOf(ext + "!/");
                return idx > 0 ? idx + ext.length() : idx;
            }).max().orElse(-1);

            if (pos > 0) {
                e = pos;
            }
        }

        return ("/" + url.substring(s, e)).replaceAll("/+", "/").replace("%20", " ");
    }
}
