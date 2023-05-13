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

import com.azul.crs.client.Client.PropertiesFilter;
import com.azul.crs.client.models.Address;
import com.azul.crs.client.models.Network;
import com.azul.crs.util.logging.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.*;
import com.azul.crs.util.logging.LogChannel;

import com.azul.crs.client.sysinfo.SystemInfoProvider;
import java.util.stream.Collectors;

/** The model of VM inventory collected and reported by agent to CRS */
@LogChannel("inventory")
public class Inventory {
    public final static String INSTANCE_TAGS_PROPERTY = "com.azul.crs.instance.tags";
    public final static String HOST_NAME_KEY = "hostName";
    public final static String NETWORKS_KEY = "networks";
    public final static String SYSTEM_PROPS_KEY = "systemProperties";
    public final static String SYSTEM_INFO_KEY = "systemInfo";
    public final static String CPU_INFO_KEY = "cpuInfo";
    public final static String MEM_INFO_KEY = "memInfo";
    public final static String OS_INFO_KEY = "osInfo";
    public final static String JVM_ARGS_KEY = "jvmArgs";
    public final static String MAIN_METHOD = "mainMethod";
    public final static String ENVIRONMENT_KEY = "osEnvironment";


    private Logger logger = Logger.getLogger(Inventory.class);
    private Map<String, Object> map = new LinkedHashMap<>();

    public Inventory() {
    }

    public Inventory populate(Client.PropertiesFilter envFilter, Client.PropertiesFilter sysPropsFilter) {
        map.put(HOST_NAME_KEY, hostName());
        // system properties must be copied to avoid ConcurrentModificationException during serialization
        map.put(SYSTEM_PROPS_KEY, new HashMap<>(systemProperties(sysPropsFilter)));
        map.put(JVM_ARGS_KEY, jvmArgs());
        map.put(ENVIRONMENT_KEY, osEnvironment(envFilter));
        return this;
    }

    public Inventory networkInformation() {
        map.put(NETWORKS_KEY, networks());
        return this;
    }

    public Inventory systemInformation() {
        try {
            map.put(CPU_INFO_KEY, SystemInfoProvider.getCPUInfo());
            map.put(MEM_INFO_KEY, SystemInfoProvider.getMemInfo());
            map.put(OS_INFO_KEY, SystemInfoProvider.getOSInfo());
            map.put(SYSTEM_INFO_KEY, SystemInfoProvider.getAllProperties());
        }
        catch (Exception ex) {
            // due to great diversity of how OSes represent their system information let's protect from possible
            // problems of obtaining or parsing of this information
            logger.warning("Failed to get system information. The data may be incomplete", ex);
        }
        return this;
    }

    public Inventory mainMethod(String mainMethod) {
        if (mainMethod != null) {
            map.put(MAIN_METHOD, mainMethod);
        }
        return this;
    }

    public String hostName() {
        try {
            return InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException uhe) {
            logger.warning("cannot get host name %s", uhe.toString());
        }
        String name = getHostNameViaReflection();
        if (name == null) {
            name = getHostNameFromNetworkInterface();
        }
        if (name == null) {
            name = "<UNKNOWN>";
        }
        return name;
    }

    public static String instanceTags() {
        return System.getProperties().getProperty(INSTANCE_TAGS_PROPERTY);
    }

    public Map systemProperties(PropertiesFilter sysPropsFilter) {
        // To safely iterate over system properties we need to make a clone first
        Properties systemProperties = (Properties) System.getProperties().clone();
        return systemProperties.entrySet().stream().filter(e -> sysPropsFilter.test(e.getKey().toString()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Map<String, String> osEnvironment(PropertiesFilter envFilter) {
        // System.getenv() is safe to use without clone()
        return System.getenv().entrySet().stream().filter(e -> envFilter.test(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public static List<String> jvmArgs() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        return runtimeMXBean.getInputArguments();
    }

    private List<Network> networks() {
        try {
            List<Network> result = new ArrayList<>();
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if(ni.isUp() && !ni.isLoopback() && !ni.getName().startsWith("docker")) {
                        List<Address> addrList = new ArrayList<>();
                        for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                            // do not delay shutdown during startup
                            if (Client.isVMShutdownInitiated() && Client.getVMShutdownDeadline().hasExpired())
                                return Collections.emptyList();
                            String hostName = (addr instanceof Inet4Address)
                                    ? addr.getCanonicalHostName()
                                    : addr.getHostAddress();
                            addrList.add(new Address(hostName, getTrueIpAddress(addr)));
                        }
                        addrList.sort(new Comparator<Address>() {
                            @Override
                            public int compare(Address a1, Address a2) {
                                return a1.hostname.compareTo(a2.hostname);
                            }
                        });
                        result.add(new Network(ni.getName(), addrList));
                    }
                } catch (SocketException e) {
                    logger.warning("cannot get network info %s", e.toString());
                }
            }
            result.sort(new Comparator<Network>() {
                @Override
                public int compare(Network n1, Network n2) {
                    return n1.interfaceName.compareTo(n2.interfaceName);
                }
            });
            return  result;
        } catch (SocketException e) {
            logger.warning("cannot get network info %s", e.toString());
        }
        return Collections.emptyList();
    }

    private String getTrueIpAddress(InetAddress addr) {
        // Inet6Address.getHostAddress() appends '%' and interface name to the address
        String text = addr.getHostAddress();
        int pos = text.indexOf('%');
        return (pos < 0) ? text : text.substring(0, pos);
    }

    Map<String, Object> toMap() {
        return map;
    }

    /**
     * The first thing InetAddress.getLocalHost() does, it gets host name. And that's all we need here.
     * Other things InetAddress.getLocalHost().getHostName() does are not just needless in our case,
     * but can lead to UnknownHostException, see CRS-183.
     * For getting host name as such InetAddress uses a InetAddressImpl delegate (which is package-local)
     * There ia no way to get this info other than reflection.
     */
    private String getHostNameViaReflection() {
        try {
            Class clazz = Class.forName("java.net.Inet4AddressImpl");
            Method method = clazz.getDeclaredMethod("getLocalHostName");
            method.setAccessible(true);
            // don't restore setAccessible back - it only affects the reflection object
            // (clazz), not the Inet4AddressImpl class itself
            for (Constructor ctor : clazz.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 0) {
                    ctor.setAccessible(true);
                    Object instance = ctor.newInstance();
                    Object result = method.invoke(instance);
                    if (result instanceof String) {
                        return (String) result;
                    } else {
                        logger.warning("cannot get host name. internal error %s",
                            result == null ? null : result.getClass());
                        return null;
                    }
                }
            }
        } catch (ReflectiveOperationException | SecurityException e) {
            logger.warning("cannot get host name %s", e.toString());
        }
        return null;
    }

    private String getHostNameFromNetworkInterface()  {
        try {
            // we prefer ipv4 over ipv6 since canonical name returned by ipv4 is "better":
            // ipv4: aspb-dhcp-10-16-12-84.xxsystems.com
            // ipv6: fe80:0:0:0:f97b:80f:df4d:fb6c%ece98e743bxe372
            String candidateName = null;
            for(Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces(); nis.hasMoreElements();) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isUp() && !ni.isLoopback()) {
                    for (Enumeration<InetAddress> isa = ni.getInetAddresses(); isa.hasMoreElements();) {
                        InetAddress ia = isa.nextElement();
                        if (ia instanceof Inet4Address) {
                            return ia.getCanonicalHostName();
                        } else {
                            candidateName = ia.getCanonicalHostName();
                        }
                    }
                }
            }
            return candidateName;
        } catch (SocketException e) {
            logger.warning("cannot get host name for iface %s", e.toString());
        }
        return null;
    }

}
