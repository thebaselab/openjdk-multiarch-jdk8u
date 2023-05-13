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

import java.util.HashSet;
import java.util.Set;

public final class KnownAzulRuntimeContainers {

    private KnownAzulRuntimeContainers() {
    }

    // Returns paths of known JVM jars.
    //     '/' is used as path separator (even on Windows);
    //     spaces are not converted
    //
    // Ex: /opt/zulu8/lib/jfr.jar
    //     /C:/Users/tester/zulu 8/jre/lib/cat.jar
    //
    public static Set<String> get(String javaHome, String javaSpecificationVersion) {
        Set<String> result = new HashSet<>();

        if (javaSpecificationVersion == null || javaHome == null) {
            return result;
        }

        if (javaSpecificationVersion.startsWith("1.")) {
            javaSpecificationVersion = javaSpecificationVersion.substring(2);
        }

        int specVersionNumber;
        try {
            specVersionNumber = Integer.parseInt(javaSpecificationVersion);
        } catch (NumberFormatException ex) {
            // Unexpected
            return result;
        }

        javaHome = ("/" + javaHome).replace('\\', '/').replaceAll("/+", "/");

        // JAVA 8:
        if (specVersionNumber <= 8) {
            for (String fname : new String[]{
                "cat.jar",
                "charsets.jar",
                "crs-agent.jar",
                "jce.jar",
                "jfr.jar",
                "jsse.jar",
                "management-agent.jar",
                "resources.jar",
                "rt.jar",
                "sunrsasign.jar"
            }) {
                add(result, javaHome, "lib", fname);
            }

            for (String fname : new String[]{
                "cldrdata.jar",
                "crs-agent.jar",
                "dnsns.jar",
                "jaccess.jar",
                "legacy8ujsse.jar",
                "localedata.jar",
                "nashorn.jar",
                "openeddsa.jar",
                "openjsse.jar",
                "sunec.jar",
                "sunjce_provider.jar",
                "sunmscapi.jar",
                "sunpkcs11.jar",
                "zipfs.jar"
            }) {
                add(result, javaHome, "lib/ext", fname);
            }

            String jdkHome = javaHome.endsWith("/jre") ? javaHome.substring(0, javaHome.length() - 4) : javaHome;
            for (String fname : new String[]{
                "dt.jar",
                "jconsole.jar",
                "sa-jdi.jar",
                "tools.jar",
            }) {
                add(result, jdkHome, "lib", fname);
            }
        } else {
            // JAVA 9+
            for (String fname : new String[]{
                "modules",
                "jrt-fs.jar",}) {
                add(result, javaHome, "lib", fname);
            }

            // Known Zing extensions
            for (String fname : new String[]{
                "modified_inputstreams.jar",
                "modified_rule_based_collator.jar",
                "readynow_extensions.jar",
                "standard_threadlocal.jar",}) {
                add(result, javaHome, "etc/extensions/jdk", fname);
            }
        }
        return result;
    }

    private static void add(Set<String> result, String javaHome, String dir, String fname) {
        try {
            result.add(String.join("/", javaHome, dir, fname));
        } catch (IllegalArgumentException ex) {
            // Should not happen
        }
    }
}
