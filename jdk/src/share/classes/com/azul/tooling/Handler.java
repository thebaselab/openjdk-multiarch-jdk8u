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

package com.azul.tooling;

import com.azul.tooling.in.Model;
import com.azul.tooling.in.Tooling;

import java.security.AccessController;
import java.security.PrivilegedAction;

public final class Handler implements Tooling.ToolingHandler {
    interface EventModel {
        void init(Tooling.ToolingEvent event);
    }

    private static final String ENABLED_EVENTS_PROPERTY_NAME = "com.azul.tooling.events";
    private static final String TOOLING_EVENT_NAME_PREFIX = "com.azul.tooling.in.";
    private static final String TOOLING_EVENT_NAME_POSTFIX = "Event";

    private Handler() {}

    @Override
    public boolean isEventTypeEnabled(Class<? extends Tooling.ToolingEvent> eventType) {
        String eventClassName = eventType.getName();
        if (!eventClassName.startsWith(TOOLING_EVENT_NAME_PREFIX) || !eventClassName.endsWith(TOOLING_EVENT_NAME_POSTFIX))
            return false;

        String enabledEventNames = AccessController.doPrivileged(new PrivilegedAction<String>() {
            @Override
            public String run() {
                return System.getProperty(ENABLED_EVENTS_PROPERTY_NAME);
            }
        });
        if (enabledEventNames == null)
            return false;

        String eventName = eventClassName.substring(TOOLING_EVENT_NAME_PREFIX.length(),
                eventClassName.length() - TOOLING_EVENT_NAME_POSTFIX.length());

        int startIndex = enabledEventNames.indexOf(eventName);
        int endIndex = startIndex + eventName.length();

        return startIndex >= 0 &&
                (startIndex == 0 || enabledEventNames.charAt(startIndex-1) == ',') &&
                (endIndex == enabledEventNames.length() || enabledEventNames.charAt(endIndex) == ',');
    }

    @Override
    public void notifyEvent(Tooling.ToolingEvent event) {
        if (!event.isEventEnabled())
            return;

        Model modelClassName = event.getClass().getAnnotation(Model.class);
        if (modelClassName != null) {
            // TODO consider optimization
            try {
                Class<? extends EventModel> modelClass = (Class<? extends EventModel>) Class.forName(modelClassName.value());
                EventModel model = modelClass.newInstance();
                model.init(event);
                Engine.putObject(model);
            } catch (IllegalAccessException | InstantiationException | ClassNotFoundException shouldNotHappen) {
                shouldNotHappen.printStackTrace();
            }
        } else {
            Engine.putObject(event);
        }
    }
}
