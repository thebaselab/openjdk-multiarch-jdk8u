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
import com.azul.crs.client.Utils;
import com.azul.crs.client.Utils.Deadline;
import com.azul.crs.json.CompileJsonSerializer;
import com.azul.crs.util.logging.Logger;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** The model of VM event sent by connected runtime to cloud service */
@CompileJsonSerializer
public class VMEvent<T> extends Payload {
    public enum Type {
        VM_CREATE               ( VMInstance.class ),
        VM_PATCH                ( VMInstance.class ),
        VM_ARTIFACT_CREATE      ( VMArtifact.class ),
        VM_ARTIFACT_PATCH       ( VMArtifact.class ),
        VM_ARTIFACT_DATA        ( Map.class        ),
        VM_HEARTBEAT            ( Void.class       ),
        VM_SHUTDOWN             ( Void.class       ),
        VM_CLASS_LOADED         ( Map.class        ),
        VM_JAR_LOADED           ( Map.class        ),
        VM_METHOD_FIRST_CALLED  ( Map.class        ),
        VM_PERFORMANCE_METRICS  ( Map.class        ),
        VM_ANY_CONNECTION       ( Object.class     );

        private final Class payloadClass;
        private int inFlightEventsCount = 0;
        Type(Class payloadClass) { this.payloadClass = payloadClass; }
        public Class payloadClass() { return this.payloadClass; }
        public static Type eventType(String type) {
            return type != null ?
                Type.valueOf(type.toUpperCase()) : null;
        }

        public synchronized int getInFlightEventsCounter() {
            return inFlightEventsCount;
        }

        public synchronized int incrementInFlightEventsCount() {
            inFlightEventsCount++;
            return inFlightEventsCount;
        }

        public synchronized int subtractInFlightEventsCount(int decrement) {
            inFlightEventsCount -= decrement;

            if (inFlightEventsCount == 0) {
                notifyAll();
            }

            return inFlightEventsCount;
        }

        public synchronized void waitAllEventsProcessed(Deadline deadline) throws InterruptedException {
            while (inFlightEventsCount != 0 && !deadline.hasExpired()) {
                wait(Math.max(1, deadline.remainder(TimeUnit.MILLISECONDS)));
            }
        }
    }

    /**
     * ID of the VM instance associated with event.
     * VM does not send this field, receiver must fill missing value before persisting or processing
    */
    private String vmId;
    private String eventId; // synthetic primary key of event
    private Type eventType; // event type
    private Long eventTime; // event time, epoch millis
    private T eventPayload; // event payload

    public String getVmId() { return vmId; }
    public String getEventId() { return eventId; }
    public Type getEventType() { return eventType; }
    public Long getEventTime() { return eventTime; }
    public T getEventPayload() { return eventPayload; }

    public void setVmId(String vmId) { this.vmId = vmId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public void setEventTime(Long eventTime) { this.eventTime = eventTime; }

    // Ensure event type is consistent with existing event payload type
    public void setEventType(Type eventType) {
        if (eventPayload != null &&
                !eventType.payloadClass().isAssignableFrom(eventPayload.getClass()))
            throw new IllegalArgumentException("Event type inconsistent with event payload type");
        this.eventType = eventType;
    }

    // Ensure event payload type is consistent with existing event type
    public void setEventPayload(T eventPayload) {
        if (eventType != null &&
                !eventType.payloadClass().isAssignableFrom(eventPayload.getClass())) {
            throw new IllegalArgumentException("Event payload type inconsistent with event type ");
        } else {
            this.eventPayload = eventPayload;
        }
    }

    public VMEvent<T> vmId(String vmId) { setVmId(vmId); return this; }
    public VMEvent<T> eventId(String eventId) { setEventId(eventId); return this; }
    public VMEvent<T> randomEventId() { setEventId(UUID.randomUUID().toString()); return this; }
    public VMEvent<T> eventType(Type eventType) { setEventType(eventType); return this; }
    public VMEvent<T> eventType(String eventType) { setEventType(Type.valueOf(eventType)); return this; }
    public VMEvent<T> eventTime(Long eventTime) { setEventTime(eventTime); return this; }
    public VMEvent<T> eventPayload(T eventPayload) { setEventPayload(eventPayload); return this; }

    public static Iterator<DataWithCounters> toJsonArrays(Collection<? extends VMEvent> events, int stringSizeLimit) {
        return new Iterator<DataWithCounters>() {
            Iterator<? extends VMEvent> it = events.iterator();
            StringBuilder current = new StringBuilder();
            StringBuilder sb = new StringBuilder().append('[');
            Map<VMEvent.Type, Long> counters = new HashMap<>();
            VMEvent.Type currentEventType = null;

            @Override
            public boolean hasNext() {
                return it.hasNext() || current.length() > 0;
            }

            @Override
            public DataWithCounters next() {
                counters.clear();
                sb.setLength(1);
                fillNext();
                sb.setCharAt(sb.length() - 1, ']');
                return new DataWithCounters(sb.toString(), counters);
            }

            private void fillNext() {
                if (current.length() > 0) {
                    sb.append(current).append(',');
                    counters.compute(currentEventType, (k, v) -> v == null ? 1 : v + 1);
                    current.setLength(0);
                }
                if (sb.length() < stringSizeLimit) {
                    while (it.hasNext()) {
                        VMEvent event = it.next();
                        currentEventType = event.getEventType();
                        Utils.serializer.serialize(current, event);
                        if (sb.length() + current.length() + 1 /* ] */ >= stringSizeLimit) {
                            if (current.length() + 2 /* [ ] */ >= stringSizeLimit) {
                                Logger.getLogger(Payload.class).error("Single event must not exceed %d. Current event '%s...' has length %d. The event is skipped.",
                                        stringSizeLimit,
                                        current.subSequence(0, Math.min(current.length(), 100)).toString(),
                                        current.length());
                                current.setLength(0);
                            }
                            return;
                        }
                        sb.append(current).append(',');
                        counters.compute(currentEventType, (k, v) -> v == null ? 1 : v + 1);
                        current.setLength(0);
                    }
                }
            }
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VMEvent<?> vmEvent = (VMEvent<?>) o;
        return Objects.equals(vmId, vmEvent.vmId) &&
            Objects.equals(eventId, vmEvent.eventId) &&
            eventType == vmEvent.eventType &&
            Objects.equals(eventTime, vmEvent.eventTime) &&
            Objects.equals(eventPayload, vmEvent.eventPayload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vmId, eventId, eventType, eventTime, eventPayload);
    }

    @Override
    public String toString() {
        return "vmId=" + vmId +
            ", eventId=" + eventId +
            ", eventType=" + eventType +
            ", eventTime=" + eventTime +
            ", eventPayload=" + eventPayload;
    }
}
