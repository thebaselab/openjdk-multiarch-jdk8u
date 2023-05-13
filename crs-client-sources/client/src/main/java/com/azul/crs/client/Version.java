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
package com.azul.crs.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Provides version details about CRS client */
public final class Version {
    private static final String VERSION_PROPERTIES = "version.properties";
    private static final String CLIENT_VERSION = "client.version";
    private static final String CLIENT_REVISION = "client.revision";
    private static final String CLIENT_REVISION_IS_DIRTY = "client.revision.is_dirty";
    private final Properties properties;

    public Version() throws IOException {
        properties = new Properties();
        try (InputStream is = getClass().getResourceAsStream(VERSION_PROPERTIES)) {
            properties.load(is);
        }
    }

    public String clientVersion() {
        return properties.getProperty(CLIENT_VERSION);
    }

    public String clientRevision() {
        return properties.getProperty(CLIENT_REVISION)
                + (properties.getProperty(CLIENT_REVISION_IS_DIRTY).equals("true") ? "+" : "");
    }
}
