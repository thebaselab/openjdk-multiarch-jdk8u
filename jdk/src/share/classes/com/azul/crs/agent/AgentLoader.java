/*
 * Copyright 2019-2021 Azul Systems, Inc.  All Rights Reserved.
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

package com.azul.crs.agent;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

class AgentLoader {

    /**
     * Load Connected Runtime Services agent.
     *
     * @return Class of CRS agent or null if CRS agent is not bundles with JRE
     * @throws Exception if CRS agent is present but cannot be loaded
     */
    private static Object main() throws Exception {
        String agentJarName = System.getProperty("java.home")+"/lib/crs-agent.jar";
        if (!(new File(agentJarName)).exists())
            return null;
        URL url = new URL("file:///"+agentJarName);
        ClassLoader loader = new URLClassLoader(new URL[] { url }, null);
        Class agentClass = loader.loadClass("com.azul.crs.client.Agent001");

        registerNatives(agentClass);

        return agentClass;
    }

    /**
     * Register implementations for native methods of CRS agent.
     */
    private static native void registerNatives(Class agentClass);
}
