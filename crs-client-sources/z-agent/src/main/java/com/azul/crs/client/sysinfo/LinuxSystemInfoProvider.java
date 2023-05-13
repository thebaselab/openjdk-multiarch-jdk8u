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
package com.azul.crs.client.sysinfo;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import static com.azul.crs.client.sysinfo.SystemInfoProvider.CPUInfoKey.*;
import static com.azul.crs.client.sysinfo.SystemInfoProvider.MemInfoKey.*;

final class LinuxSystemInfoProvider extends SystemInfoProvider.SystemInfoProviderImpl {

    @Override
    protected void initProps() throws IOException, InterruptedException {
        int colon = 0;
        int idx = 0;
        String packages = "";
        String physicalcpu = "";
        String ncpu = "";
        String frequency = "";

        try (BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    idx++;
                    continue;
                }
                if (idx == 0) {
                    if (colon >= line.length() || line.charAt(colon) != ':') {
                        colon = line.indexOf(':');
                        if (colon < 0) {
                            colon = 0;
                            continue;
                        }
                    }
                    String key = "proc.cpuinfo." + line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    machdepProps.set(key, value);
                    if (line.startsWith("cpu MHz")) {
                        frequency = value;
                    }
                } else {
                    if (line.startsWith("processor")) {
                        ncpu = line;
                    } else if (line.startsWith("physical id")) {
                        packages = line;
                    } else if (line.startsWith("core id")) {
                        physicalcpu = line;
                    }
                }
            }
        }

        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                colon = line.indexOf(':');
                if (colon < 0) {
                    break;
                }
                String key = "proc.meminfo." + line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                machdepProps.set(key, value);
            }
        }

        cpuProps.set(MODEL_NAME, machdepProps.strValue("proc.cpuinfo.model name"));
        cpuProps.set(VENDOR, machdepProps.strValue("proc.cpuinfo.vendor_id"));
        cpuProps.set(FAMILY, machdepProps.intValue("proc.cpuinfo.cpu family"));
        cpuProps.set(MODEL, machdepProps.intValue("proc.cpuinfo.model"));
        cpuProps.set(STEPPING, machdepProps.intValue("proc.cpuinfo.stepping"));
        cpuProps.set(MICROCODE, machdepProps.longValue("proc.cpuinfo.microcode"));

        cpuProps.set(PACKAGE_COUNT, 1 + parseIntOrZero(packages.substring(1 + packages.lastIndexOf(' '))));
        cpuProps.set(PHYSICAL_CORE_COUNT, 1 + parseIntOrZero(physicalcpu.substring(1 + physicalcpu.lastIndexOf(' '))));
        cpuProps.set(PROCESSOR_COUNT, 1 + parseIntOrZero(ncpu.substring(1 + ncpu.lastIndexOf(' '))));

        cpuProps.set(CACHE_SIZE_L1I, cacheSize(firstLine("/sys/devices/system/cpu/cpu0/cache/index0/size")));
        cpuProps.set(CACHE_SIZE_L1D, cacheSize(firstLine("/sys/devices/system/cpu/cpu0/cache/index1/size")));
        cpuProps.set(CACHE_SIZE_L2, cacheSize(firstLine("/sys/devices/system/cpu/cpu0/cache/index2/size")));
        cpuProps.set(CACHE_SIZE_L3, cacheSize(firstLine("/sys/devices/system/cpu/cpu0/cache/index3/size")));

        cpuProps.set(FREQUENCY, frequency(frequency));
        cpuProps.set(FREQUENCY_MIN, frequency(firstLine("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq")));
        cpuProps.set(FREQUENCY_MAX, frequency(firstLine("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")));

        String scaling_driver = firstLine("/sys/devices/system/cpu/cpu0/cpufreq/scaling_driver");
        String cpu_flags = machdepProps.strValue("proc.cpuinfo.flags");
        StringBuilder sb = new StringBuilder();
        sb.append("scaling_driver=").append(scaling_driver);
        sb.append(" tsc=").append(cpu_flags.contains(" tsc ") ? "on" : "off");
        sb.append(" constant_tsc=").append(cpu_flags.contains("constant_tsc") ? "on" : "off");
        sb.append(" nonstop_tsc=").append(cpu_flags.contains("nonstop_tsc") ? "on" : "off");
        cpuProps.set(TSC, sb.toString());

        try {
            String total = machdepProps.strValue("proc.meminfo.MemTotal");
            memProps.set(SYSTEM_RAM_MAX_GB, total.endsWith(" kB")
                    ? (Long.parseLong(total.substring(0, total.length() - 3)) >> 20)
                    : Long.parseLong(total));
        } catch (NumberFormatException ex) {
        }
    }

    private static String firstLine(String path) {
        try {
            return Files.readAllLines(Paths.get(path)).get(0);
        } catch (IOException | RuntimeException ex) {
            return "";
        }
    }

    private String cacheSize(String val) {
        try {
            if (val.endsWith("K")) {
                int kb = Integer.parseInt(val.substring(0, val.length() - 1));
                return "" + (kb << 10);
            } else {
                return val.trim();
            }
        } catch (NumberFormatException ex) {
            return "";
        }
    }

    private String frequency(String frequency) {
        try {
            return "" + (Long.parseLong(frequency.replace(".", "").trim()) << 10);
        } catch (NumberFormatException ex) {
            return "";
        }
    }
}
