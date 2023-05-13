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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.security.AccessController;
import java.security.PrivilegedAction;

public final class Tooling {
    public interface ToolingEvent {
        boolean isEventEnabled();
    }

    public interface ToolingHandler {
        boolean isEventTypeEnabled(Class<? extends ToolingEvent> eventType);
        void notifyEvent(ToolingEvent event);
    }

    static {
        handler = AccessController.doPrivileged(new PrivilegedAction<ToolingHandler>() {
            @Override
            public ToolingHandler run() {
                ToolingHandler handler = null;
                try {
                    Class<ToolingHandler> tooling = (Class<ToolingHandler>) Class.forName("com.azul.tooling.Handler", true, Tooling.class.getClassLoader());
                    Constructor<ToolingHandler> c = tooling.getDeclaredConstructor();
                    c.setAccessible(true);
                    handler = c.newInstance();
                } catch (IllegalAccessException | InstantiationException | NoSuchMethodException | InvocationTargetException shouldNotHappen) {
                    shouldNotHappen.printStackTrace();
                } catch (ClassNotFoundException ignored) {
                }
                return handler;
            }
        });
    }

    static boolean isEventTypeEnabled(Class<? extends ToolingEvent> eventClass) {
        return handler != null && handler.isEventTypeEnabled(eventClass);
    }

    private static final ToolingHandler handler;

    /**
     * Notify implementation about given event. To be used strictly by JRE.
     *
     * @param event the event to notify about.
     */
    public static void notifyEvent(ToolingEvent event) {
        if (event.isEventEnabled()) {
            handler.notifyEvent(event);
        }
    }

    /**
     * Checks for presence of actual implementation.
     *
     * @return true if tooling is implemented in this JRE
     */
    public static boolean isImplemented() {
        return handler != null;
    }
}
