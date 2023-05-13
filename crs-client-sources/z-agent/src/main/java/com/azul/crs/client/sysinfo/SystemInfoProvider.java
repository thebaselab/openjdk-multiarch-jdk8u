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

import com.azul.crs.util.logging.LogChannel;
import com.azul.crs.util.logging.Logger;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@LogChannel("service.systeminfo")
public final class SystemInfoProvider {

    public static enum OS {
        Linux,
        MacOSX
    }

    private static SoftReference<SystemInfoProviderImpl> providerRef = new SoftReference<>(null);
    private static final OS OS = getOS();

    private SystemInfoProvider() {
    }

    private static OS getOS() {
        String os = System.getProperty("os.name");
        if (os != null) {
            os = os.toLowerCase();
            if (os.startsWith("linux")) {
                return OS.Linux;
            }
            if (os.startsWith("mac")) {
                return OS.MacOSX;
            }
        }
        return null;
    }

    private static SystemInfoProviderImpl getInfoProvider() {
        if (OS == null) {
            return new DummyInfoProvider();
        }
        SystemInfoProviderImpl provider = providerRef.get();
        if (provider == null) {
            switch (OS) {
                case Linux:
                    provider = new LinuxSystemInfoProvider();
                    break;
                case MacOSX:
                    provider = new MacOSXSystemInfoProvider();
                    break;
                default:
                    return new DummyInfoProvider();
            }
            try {
                assert provider != null;
                provider.initialize();
            } catch (IOException | InterruptedException ex) {
                Logger.getLogger(SystemInfoProvider.class).error("Cannot get provider: %s", ex);
            }
            providerRef = new SoftReference<>(provider);
        }
        return provider;
    }

    public static Map<String, Object> getAllProperties() {
        return Collections.unmodifiableMap(getInfoProvider().getRawProperties());
    }

    public static Map<String, Object> getCPUInfo() {
        return Collections.unmodifiableMap(getInfoProvider().cpuProps);
    }

    public static Map<String, Object> getMemInfo() {
        return Collections.unmodifiableMap(getInfoProvider().memProps);
    }

    public static Map<String, Object> getOSInfo() {
        return Collections.unmodifiableMap(getInfoProvider().osProps);
    }

    public static enum CPUInfoKey {
        PACKAGE_COUNT("Number of CPU Packages"),
        PHYSICAL_CORE_COUNT("Number of Physical Cores"),
        PROCESSOR_COUNT("Number of Processors"),
        ASSIGNED_LOGICAL_CORE_COUNT("Number of Assigned Logical Cores"),
        VENDOR("CPU Vendor"),
        MODEL_NAME("CPU Model Name"),
        FAMILY("CPU Family"),
        MODEL("CPU Model"),
        STEPPING("CPU Stepping"),
        MICROCODE("CPU Microcode"),
        CACHE_SIZE_L1I("Cache Size L1i"),
        CACHE_SIZE_L1D("Cache Size L1d"),
        CACHE_SIZE_L2("Cache Size L2"),
        CACHE_SIZE_L3("Cache Size L3"),
        FREQUENCY("Frequency, Hz"),
        FREQUENCY_MAX("Frequency Max, Hz"),
        FREQUENCY_MIN("Frequency Min, Hz"),
        TSC("Time Stamp Counter"),
        FLAGS("CPU flags"),
        SKX102_AFFECTED("Affected by MCU Erratum SKX102");

        private final String key;

        private CPUInfoKey(String key) {
            this.key = key;
        }

        @Override
        public String toString() {
            return key;
        }
    };

    public static enum MemInfoKey {
        SYSTEM_RAM_MAX_GB("System RAM max, GB");

        private final String key;

        private MemInfoKey(String key) {
            this.key = key;
        }

        @Override
        public String toString() {
            return key;
        }
    }

    public static enum OSInfoKey {
        NAME("OS Name"),
        VERSION("OS Version"),
        ARCH("OS Arch");

        private final String key;

        private OSInfoKey(String key) {
            this.key = key;
        }

        @Override
        public String toString() {
            return key;
        }
    }

    private static final class CPUID {

        int cpu_family;
        int model;
        int stepping;
        long microcode;

        public CPUID(int cpu_family, int model, int stepping, long microcode) {
            this.cpu_family = cpu_family;
            this.model = model;
            this.stepping = stepping;
            this.microcode = microcode;
        }
    }

    // Taken from https://github.com/intel/Intel-Linux-Processor-Microcode-Data-Files/releases/tag/microcode-20191115
    // and https://www.intel.com/content/dam/support/us/en/documents/processors/mitigations-jump-conditional-code-erratum.pdf
    // and https://access.redhat.com/solutions/2019-microcode-nov
    private static final CPUID[] SKX102_AffectedCPUs = {
        new CPUID(6, 0x4e, 0x3, 0xd4),
        new CPUID(6, 0x55, 0x4, 0x2000064),
        new CPUID(6, 0x55, 0x7, 0x500002b),
        new CPUID(6, 0x5e, 0x3, 0xd4),
        new CPUID(6, 0x8e, 0x9, 0xc6),
        new CPUID(6, 0x8e, 0xa, 0xc6),
        new CPUID(6, 0x8e, 0xb, 0xc6),
        new CPUID(6, 0x8e, 0xc, 0xc6),
        new CPUID(6, 0x9e, 0x9, 0xc6),
        new CPUID(6, 0x9e, 0xa, 0xc6),
        new CPUID(6, 0x9e, 0xb, 0xc6),
        new CPUID(6, 0x9e, 0xd, 0xc6),
        new CPUID(6, 0xa6, 0x0, 0xc6), // new CPUID(6, 0xae, 0xa, ??), // There is no microcode version on github for this. Probably typo in Intel's pdf.
    };

    static abstract class SystemInfoProviderImpl {

        protected final SystemProperties<String> machdepProps = new SystemProperties<>();
        protected final SystemProperties<CPUInfoKey> cpuProps = new SystemProperties<>();
        protected final SystemProperties<MemInfoKey> memProps = new SystemProperties<>();
        protected final SystemProperties<OSInfoKey> osProps = new SystemProperties<>();

        protected static class SystemProperties<T> extends HashMap<String, Object> {

            public SystemProperties() {
            }

            public void set(T key, Object value) {
                super.put(key.toString(), value);
            }

            @Override
            public Object put(String key, Object value) {
                throw new InternalError("Should not call");
            }

            @Override
            public Object get(Object key) {
                return super.getOrDefault(key.toString(), "");
            }

            public int intValue(T key) {
                Object val = get(key.toString());
                try {
                    return Integer.decode(val.toString());
                } catch (NumberFormatException ex) {
                }
                return -1;
            }

            public long longValue(T key) {
                Object val = get(key.toString());
                try {
                    return Long.decode(val.toString());
                } catch (NumberFormatException ex) {
                }
                return -1;
            }

            public String strValue(T key) {
                return get(key.toString()).toString();
            }

            void filter(String... prefixes) {
                Iterator<String> it = super.keySet().iterator();
                outer:
                while (it.hasNext()) {
                    String key = it.next();
                    for (String prefix : prefixes) {
                        if (key.startsWith(prefix)) {
                            continue outer;
                        }
                    }
                    it.remove();
                }
            }
        }

        private SystemProperties getRawProperties() {
            return machdepProps;
        }

        private boolean known_erratum_SKX102_affected() {
            for (CPUID id : SKX102_AffectedCPUs) {
                if (id.cpu_family == cpuProps.intValue(CPUInfoKey.FAMILY)
                        && id.model == cpuProps.intValue(CPUInfoKey.MODEL)
                        && id.stepping == cpuProps.intValue(CPUInfoKey.STEPPING)
                        && id.microcode == cpuProps.longValue(CPUInfoKey.MICROCODE)) {
                    return true;
                }
            }
            return false;
        }

        final void initialize() throws IOException, InterruptedException {
            initProps();
            cpuProps.set(CPUInfoKey.ASSIGNED_LOGICAL_CORE_COUNT, Runtime.getRuntime().availableProcessors());
            cpuProps.set(CPUInfoKey.SKX102_AFFECTED, known_erratum_SKX102_affected());

            osProps.set(OSInfoKey.NAME, System.getProperty("os.name"));
            osProps.set(OSInfoKey.VERSION, System.getProperty("os.version"));
            osProps.set(OSInfoKey.ARCH, System.getProperty("os.arch"));
        }

        protected abstract void initProps() throws IOException, InterruptedException;

        /**
         * Utility method to parse input text as 10-base integer. The only additional feature to standard
         * <code>Integer.parseInt</code> is that it accepts empty string and returns 0 if passed.
         * @param str the 10-base encoded integer or empty string
         * @return the parsed integer or 0
         */
        protected final int parseIntOrZero(String str) {
            return str.length() > 0 ? Integer.parseInt(str) : 0;
        }
    }

    private static class DummyInfoProvider extends SystemInfoProviderImpl {

        @Override
        protected void initProps() throws IOException, InterruptedException {
        }
    }
}
