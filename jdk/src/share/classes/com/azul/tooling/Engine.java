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

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class Engine {
    private static final int MAX_EVENTS_COUNT = 10_000; // TODO should limit memory amount, not object count

    private static ConcurrentLinkedQueue<Object> events = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger overflowCounter = new AtomicInteger(0);
    private static int watermarkCount = MAX_EVENTS_COUNT;
    private static volatile boolean notifiedWatermarkReached; // notification is sent only once until cleared
    private static volatile boolean discardEvents;

    static void putObject(Object event) {
        // get buffer
        // ensure size
        // serialize event
        // flush buffer

        if (discardEvents)
            return;

        if (events.size() > MAX_EVENTS_COUNT) {
            overflowCounter.incrementAndGet();
            return;
        }
        events.add(event);

        checkWatermark();
    }

    static void consume(ConsumerManager.Consumer consumer) {
        notifiedWatermarkReached = false;
        Object event;
        int discarded = overflowCounter.getAndSet(0);
        if (discarded > 0)
            consumer.notifyOverflow(discarded);
        while ((event = events.poll()) != null)
            consumer.notifyEvent(event);
    }

    static void setNotificationWatermark(int percent) {
        watermarkCount = percent * MAX_EVENTS_COUNT / 100;
        checkWatermark();
    }

    static void setDiscarding(boolean discarding) {
        discardEvents = discarding;
    }

    private static void checkWatermark() {
        if (events.size() > watermarkCount && !notifiedWatermarkReached) {
            ConsumerManager.Consumer c = ConsumerManager.getConsumer();
            if (c != null) {
                notifiedWatermarkReached = true;
                c.notifyWatermarkReached(); // might synchronously clear notified flag
            }
        }
    }

}
