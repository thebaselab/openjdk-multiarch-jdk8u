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

import com.azul.tooling.in.Tooling;

public final class ConsumerManager {
    public interface Consumer {
        /**
         * Event for consumer to consume.
         *
         * @deprecated to be superseded by serialized data communication
         * @param event event to consume
         */
        @Deprecated
        void notifyEvent(Object event);

        /**
         * Notifies that internal buffers are full up to set watermark level.
         */
        void notifyWatermarkReached();

        /**
         * Notifies that internal buffers are overflowed.
         */
        void notifyOverflow(int num);
    }

    /**
     * Registers the only consumer of the com.azul.tooling. Returns instance of ConsumeManager if attempt succeeds.
     * It fails if either com.azul.tooling is not implemented on the VM or if consumer is already registered.
     *
     * @param consumer consumer to register
     * @return ConsumerManager instance if attempt succeeded, null otherwise
     */
    public static ConsumerManager registerConsumer(Consumer consumer) {
        if (!Tooling.isImplemented())
            return null;

        synchronized (ConsumerManager.class) {
            if (instance != null)
                return null;
            instance = new ConsumerManager(consumer);
            Engine.setDiscarding(false);
        }

        return instance;
    }

    /**
     * Unregisters consumer.
     */
    public void unregisterConsumer() {
        if (!Tooling.isImplemented() || instance != this)
            return;

        synchronized (ConsumerManager.class) {
            if (instance == this) {
                instance = null;
                Engine.setDiscarding(true);
            }
        }
    }

    /**
     * Set percentage of the internal buffer which is, when filled, causes com.azul.tooling to invoke
     * {@link Consumer#notifyWatermarkReached()} on registered consumer instance.
     * @param percent the watermark level in percent
     */
    public void setNotificationWatermark(int percent) {
        if (instance != this)
            return;

        Engine.setNotificationWatermark(percent);
    }

    /**
     * Request to drain internal buffer by invoking {@link Consumer#notifyEvent(Object)} method on registered consumer.
     */
    public void drain() {
        if (instance != this)
            return;

        Engine.consume(consumer);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // implementation details
    private static volatile ConsumerManager instance;

    private final Consumer consumer;

    private ConsumerManager(Consumer consumer) {
        this.consumer = consumer;
    }

    static Consumer getConsumer() {
        ConsumerManager c = instance;
        return c == null ? null : c.consumer;
    }
}
