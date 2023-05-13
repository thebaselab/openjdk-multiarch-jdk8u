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

package com.azul.tooling.in;

import java.net.URL;
import java.util.jar.JarFile;

import static java.lang.System.currentTimeMillis;

@Model("com.azul.tooling.JarLoadEventModel")
public final class JarLoadEvent implements Tooling.ToolingEvent {
    private static final boolean isEnabled;

    static {
        isEnabled = Tooling.isEventTypeEnabled(JarLoadEvent.class);
    }

    private JarLoadEvent(URL url, JarFile jar) {
        this.url = url;
        this.jar = jar;
    }

    public static JarLoadEvent jarLoadEvent(URL url, JarFile jar) {
        return new JarLoadEvent(url, jar);
    }

    public static boolean isEnabled() {
        return isEnabled;
    }

    public boolean isEventEnabled() {
        return isEnabled();
    }

    public final URL url;
    public final JarFile jar;

    public URL getURL() {
      return url;
    }

    public JarFile getJarFile() {
      return jar;
    }
}


