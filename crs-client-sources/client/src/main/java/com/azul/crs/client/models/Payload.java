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
package com.azul.crs.client.models;

import java.util.Collection;
import com.azul.crs.client.Utils;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The class represents abstract payload supplied with JSON serialization/deserialization on
 * communication between cloud service and connected clients. Extended with model subclasses
 */
public abstract class Payload {

    /** Gets JSON array string by collection of payload instances */
    public static String toJsonArray(Collection<? extends Payload> items) {
        return Utils.serializer.serialize(items);
    }

    /** Gets JSON string by this payload instance */
    public String toJson() {
        return Utils.serializer.serialize(this);
    }

    /** Gets JSON with unchecked exception */
    public String toJsonUnchecked() {
        return Utils.prettySerializer.serialize(this);
    }

    @Override
    public String toString() {
        return toJsonUnchecked();
    }

    public final static class DataWithCounters {

        public final String data;
        public final Map<VMEvent.Type, Long> counters;

        public DataWithCounters(String data, Map<VMEvent.Type, Long> counters) {
            this.data = data;
            this.counters = Collections.unmodifiableMap(new HashMap<>(counters));
        }
    }
}
