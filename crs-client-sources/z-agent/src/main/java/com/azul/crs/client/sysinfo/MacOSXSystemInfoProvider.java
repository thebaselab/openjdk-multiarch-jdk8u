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

import static com.azul.crs.client.sysinfo.SystemInfoProvider.CPUInfoKey.*;
import static com.azul.crs.client.sysinfo.SystemInfoProvider.MemInfoKey.SYSTEM_RAM_MAX_GB;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

final class MacOSXSystemInfoProvider extends SystemInfoProvider.SystemInfoProviderImpl {

    @Override
    protected void initProps() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("sysctl", "-a");
        Process p = pb.start();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }

                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                machdepProps.set("sysctl." + key, value);
            }
        }

        cpuProps.set(MODEL_NAME, machdepProps.strValue("sysctl.machdep.cpu.brand_string"));
        cpuProps.set(VENDOR, machdepProps.strValue("sysctl.machdep.cpu.vendor"));
        cpuProps.set(FAMILY, machdepProps.intValue("sysctl.machdep.cpu.family"));
        cpuProps.set(MODEL, machdepProps.intValue("sysctl.machdep.cpu.model"));
        cpuProps.set(STEPPING, machdepProps.intValue("sysctl.machdep.cpu.stepping"));
        cpuProps.set(MICROCODE, machdepProps.longValue("sysctl.machdep.cpu.microcode_version"));

        cpuProps.set(PACKAGE_COUNT, machdepProps.intValue("sysctl.hw.packages"));
        cpuProps.set(PHYSICAL_CORE_COUNT, machdepProps.intValue("sysctl.hw.physicalcpu"));
        cpuProps.set(PROCESSOR_COUNT, machdepProps.intValue("sysctl.hw.ncpu"));

        cpuProps.set(CACHE_SIZE_L1I, machdepProps.intValue("sysctl.hw.l1icachesize"));
        cpuProps.set(CACHE_SIZE_L1D, machdepProps.intValue("sysctl.hw.l1dcachesize"));
        cpuProps.set(CACHE_SIZE_L2, machdepProps.intValue("sysctl.hw.l2cachesize"));
        cpuProps.set(CACHE_SIZE_L3, machdepProps.intValue("sysctl.hw.l3cachesize"));

        cpuProps.set(FREQUENCY, machdepProps.longValue("sysctl.hw.cpufrequency"));
        cpuProps.set(FREQUENCY_MIN, machdepProps.longValue("sysctl.hw.cpufrequency_min"));
        cpuProps.set(FREQUENCY_MAX, machdepProps.longValue("sysctl.hw.cpufrequency_max"));

        String cpu_flags = machdepProps.strValue("sysctl.machdep.cpu.features").toLowerCase();
        machdepProps.set("sysctl.machdep.cpu.features", cpu_flags);
        cpuProps.set(FLAGS, cpu_flags);

        StringBuilder tsc_info = new StringBuilder();
        tsc_info.append("tsc=").append(cpu_flags.contains(" tsc ") ? "on" : "off");
        tsc_info.append(" constant_tsc=").append(cpu_flags.contains("constant_tsc") ? "on" : "off");
        tsc_info.append(" nonstop_tsc=").append(cpu_flags.contains("nonstop_tsc") ? "on" : "off");
        cpuProps.set(TSC, tsc_info.toString());

        memProps.set(SYSTEM_RAM_MAX_GB, machdepProps.longValue("sysctl.hw.memsize") >> 30);

        machdepProps.filter(
                "sysctl.hw.",
                "sysctl.kern.sysv.",
                "sysctl.kern.core.",
                "sysctl.kern.timer",
                "sysctl.kern.vm_page",
                "sysctl.kern.monotonicclock"
        );

        p.waitFor();
    }
}
