/*
 * Copyright (c) 2022, Azul Systems, Inc. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 only, as published by
 * the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE.  See the GNU General Public License version 2 for  more
 * details (a copy is included in the LICENSE file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version 2
 * along with this work; if not, write to the Free Software Foundation,Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Azul Systems, Inc., 1600 Plymouth Street, Mountain View,
 * CA 94043 USA, or visit www.azulsystems.com if you need additional information
 * or have any questions.
 *
 */

package jdk.test.lib.crs;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;

/**
 * Prevent clashing with Azul CRS startup: src/hotspot/share/services/connectedRuntime.cpp
 */
public class CRSUtils {
    // Default CRS agent startup delay if not specified in command line
    private static final int DEFAULT_DELAY_INITIATION = 2 * 1000; // 2 seconds
    // Preconfigured timeout for CRS agent initialization
    private static final int DELAY_INITIATION_ADDON = 20 * 1000; // 20 seconds
    // Maximum timeout for CRS agent initialization
    private static final int CRS_INITIALIZATION_TIMEOUT = 60 * 1000; // 60 seconds
    // Name of CRS agent thread to detect the CRS agent initialization has finished
    private static final String CRS_INITIALIZATION_THREAD = "CRSEventsFlushingThread";

    protected int delayInitiation = -1;
    protected RuntimeMXBean mxbean = java.lang.management.ManagementFactory.getRuntimeMXBean();

    // Java copy of ConnectedRuntime::parse_arguments().
    protected void parseArguments(String arguments) {
        if (arguments == null)
            return;
        if (delayInitiation == -1)
            delayInitiation = DEFAULT_DELAY_INITIATION;

        while (!arguments.isEmpty()) {
            int comma = arguments.indexOf(',');
            if (comma == -1)
                comma = arguments.length();
            final String prefix = "delayInitiation=";
            if (arguments.startsWith(prefix)) {
                String value = arguments.substring(prefix.length(), comma);
                try {
                     delayInitiation = Integer.parseInt(value);
                }
                catch (NumberFormatException e) {}
            }
            arguments = arguments.substring(comma);
            while (arguments.startsWith(","))
                arguments = arguments.substring(1);
        }
    }

    // Java copy of ConnectedRuntime::parse_options().
    protected void ConnectedRuntimeParseOptions() {
        parseArguments(System.getenv("AZ_CRS_ARGUMENTS"));

        for (String arg : mxbean.getInputArguments()) {
            final String prefix = "-XX:AzCRSArguments=";
            if (arg.startsWith(prefix))
                parseArguments(arg.substring(prefix.length()));
        }
    }

    public CRSUtils() {
        ConnectedRuntimeParseOptions();
    }

    public boolean isCRSConfigured() {
        return delayInitiation != -1;
    }

    public void waitForCRSAgentIntialized() {
        if (!isCRSConfigured())
            return;

        long uptime_msec = mxbean.getUptime();
        long delay_msec = DELAY_INITIATION_ADDON + delayInitiation;
        if (uptime_msec < delay_msec) {
            try {
                Thread.sleep(delay_msec - uptime_msec);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        // Be sure CRS is already initialized.
        long wait_max_msec = delay_msec + CRS_INITIALIZATION_TIMEOUT;
        for (;;) {
            if (Thread.getAllStackTraces().keySet().stream()
                .filter(value -> value.getName().equals(CRS_INITIALIZATION_THREAD))
                .findAny()
                .isPresent())
                break;
            if (mxbean.getUptime() >= wait_max_msec)
                throw new RuntimeException("CRS Thread " + CRS_INITIALIZATION_THREAD
                                           + " not found in " + CRS_INITIALIZATION_TIMEOUT + "msec");
            try {
                Thread.sleep(1 * 1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
