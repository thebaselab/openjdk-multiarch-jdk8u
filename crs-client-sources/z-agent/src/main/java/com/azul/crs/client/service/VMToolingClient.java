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
package com.azul.crs.client.service;

import com.azul.crs.client.Client;
import com.azul.crs.client.Utils.Deadline;
import com.azul.crs.client.models.VMEvent;
import com.azul.crs.util.logging.LogChannel;
import com.azul.crs.util.logging.Logger;

import com.azul.tooling.ConsumerManager;
import com.azul.tooling.JarLoadEventModel;
import com.azul.tooling.in.Tooling;

import static com.azul.crs.client.Utils.currentTimeMillis;
import static com.azul.crs.client.models.VMEvent.Type.VM_ANY_CONNECTION;
import java.util.concurrent.TimeUnit;

@LogChannel("service.vmtooling")
public class VMToolingClient implements ClientService, ConsumerManager.Consumer { // add http interface list
    private static final int POLL_TIMEOUT = 3_000; // every 3 seconds
    private static final int WATERMARK_THRESHOLD = 50;

    private static final VMToolingClient instance = new VMToolingClient();
    private final Object LOCK = new Object();

    private Client client;
    private volatile boolean running;
    private Thread thread;
    private ConsumerManager consumerManager;
    //TODO: refactor to register specific event listener?
    private JarLoadMonitor jarLoadMonitor;

    public static boolean isToolingImplemented() {
        return Tooling.isImplemented();
    }

    private VMToolingClient() {
    }

    public void setJarLoadMonitor(JarLoadMonitor jarLoadMonitor) {
        this.jarLoadMonitor = jarLoadMonitor;
    }

    @Override
    public void notifyEvent(Object event) {
        if (event instanceof JarLoadEventModel) {
            JarLoadEventModel jarLoadEventModel = (JarLoadEventModel) event;
            if (jarLoadMonitor != null) {
                jarLoadMonitor.notifyJarLoad(jarLoadEventModel.getURL(), jarLoadEventModel.getJarFile());
            }
            return;
        }
        client.postVMEvent(new VMEvent<>()
                .eventType(VM_ANY_CONNECTION)
                .eventPayload(event)
                .eventTime(currentTimeMillis()));
    }

    @Override
    public void notifyOverflow(int num) {
        final Logger logger = Logger.getLogger(VMToolingClient.class);
        logger.warning("%d events are discarded", num);
    }

    @Override
    public void notifyWatermarkReached() {
        synchronized (this) {
            this.notify();
        }
    }

    public static VMToolingClient getInstance(Client client) {
        instance.client = client;
        return instance;
    }

    @Override
    public void start() {
        if (isToolingImplemented())
            consumerManager = ConsumerManager.registerConsumer(this);
        final Logger logger = Logger.getLogger(VMToolingClient.class);
        if (consumerManager == null) {
            logger.error("cannot register with VM");
            return;
        }
        consumerManager.setNotificationWatermark(WATERMARK_THRESHOLD);

        running = true;
        logger.debug("registered with VM");

        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    do {
                        synchronized (LOCK) {
                            try {
                                LOCK.wait(POLL_TIMEOUT);
                            } catch (InterruptedException ignored) {
                            }
                        }
                        consumerManager.drain();
                    } while (running);
                } catch (Throwable th) {
                    logger.error("Internal error or unexpected problem. CRS defunct. %s", th);
                } finally {
                    consumerManager.unregisterConsumer();
                }
            }
        });
        thread.setDaemon(true);
        thread.setName("CRSVMTooling");
        thread.start();
    }

    @Override
    public void stop(Deadline deadline) {
        if (!running)
            return;

        running = false;

        Logger logger = Logger.getLogger(VMToolingClient.class);
        logger.debug("unregistered with VM");

        synchronized (LOCK) {
            LOCK.notify();
        }

        try {
            thread.join(Math.max(1, deadline.remainder(TimeUnit.MILLISECONDS)));
        } catch (InterruptedException ignored) {
            logger.debug("failed to join VMToolingClient thread in time");
        }
    }
}
