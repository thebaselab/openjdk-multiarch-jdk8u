/*
 * Copyright 2022 Azul Systems,
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

import com.azul.crs.client.Utils.Deadline;
import static com.azul.crs.client.Utils.currentTimeCount;
import static com.azul.crs.client.Utils.elapsedTimeString;
import com.azul.crs.client.models.ServerRequest;
import com.azul.crs.util.logging.LogChannel;
import com.azul.crs.util.logging.Logger;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@LogChannel("requests.processor")
public final class ServerRequestsService implements ClientService {

    private static final boolean disableServerRequests =     Boolean.getBoolean("com.azul.crs.client.service.disableServerRequests");

    private static final Object requestsCountLock = new Object();
    private static final BlockingDeque<ServerRequest> requests = new LinkedBlockingDeque<>();
    private static final Logger log = Logger.getLogger(ServerRequestsService.class);
    private static final ServerRequest stopRequest = new ServerRequest() {
        @Override
        public String toString() {
            return "StopServerRequestsServiceRequest";
        }
    };

    private static Thread queueProcessingThread = null;
    private static int requestsCount;
    private boolean isRunning;

    @Override
    public String serviceName() {
        return "requests.processor";
    }

    public static void addServiceRequest(ServerRequest request) {
        log.debug("Adding ServiceRequest: %s", request);

        if (disableServerRequests) {
            log.debug("ServiceRequest: %s was ignored because com.azul.crs.client.service.disableServerRequests was set", request);
            return;
        }

        synchronized (requestsCountLock) {
            requestsCount++;
            requests.add(request);
        }
    }

    @Override
    public synchronized void start() {
        if (isRunning) {
            return;
        }

        if (!disableServerRequests) {
            queueProcessingThread = new Thread(new ServerRequestsProcessor(), "ServerRequestsProcessor");
            queueProcessingThread.setDaemon(true);
            queueProcessingThread.start();
        } else {
            log.debug("ServiceRequestService was disabled by com.azul.crs.client.service.disableServerRequests property");
        }

        isRunning = true;
    }

    @Override
    public synchronized void stop(Deadline deadline) {
        if (!isRunning) {
            return;
        }

        addServiceRequest(stopRequest);

        try {
            if (null != queueProcessingThread) {
                queueProcessingThread.join(Math.max(1, deadline.remainder(TimeUnit.MILLISECONDS)));
            }
        } catch (InterruptedException ex) {
            // not what we would expect, but will clear interrupted state
            Thread.interrupted();
        }

        if (queueProcessingThread != null && queueProcessingThread.isAlive()) {
            log.debug("Failed to stop ServerRequestsService::queueProcessingThread in time");
        }

        isRunning = false;
    }

    public static int getRequestsCount() {
        synchronized (requestsCountLock) {
            return requestsCount;
        }
    }

    static void waitAllRequestsProcessed(Deadline deadline) {
        long startTime = currentTimeCount();

        synchronized (requestsCountLock) {
            while (!deadline.hasExpired() && getRequestsCount() > 0) {
                try {
                    requestsCountLock.wait(Math.max(1, deadline.remainder(TimeUnit.MILLISECONDS)));
                } catch (InterruptedException ex) {
                    // not what we would expect, but will clear interrupted state and break
                    Thread.interrupted();
                    break;
                }
            }
        }

        log.debug("waitAllRequestsProcessed complete%s", elapsedTimeString(startTime));
    }

    public void cancel() {
        stop(Deadline.in(0, TimeUnit.MILLISECONDS));
    }

    private final static Map<Class<? extends ServerRequest>, List<Consumer>> listeners = new HashMap<>();

    public static <T extends ServerRequest> void addListener(Class<T> type, Consumer<T> consumer) {
        listeners.computeIfAbsent(type, t -> new LinkedList<>()).add(consumer);
    }

    private static class ServerRequestsProcessor implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    ServerRequest request = requests.takeFirst();

                    if (stopRequest == request) {
                        log.debug("Stop processing ServerRequests");
                        break;
                    }

                    listeners.entrySet().stream()
                            .filter(e -> request.getClass().isAssignableFrom(e.getKey()))
                            .forEachOrdered(e -> e.getValue().forEach(c -> process(c, request)));
                } catch (InterruptedException ex) {
                    // not what we would expect, but will clear interrupted state and break
                    Thread.interrupted();
                    break;
                } finally {
                    synchronized (requestsCountLock) {
                        requestsCount--;
                        requestsCountLock.notify();
                    }
                }
            }
        }

        private void process(Consumer c, ServerRequest r) {
            log.debug("Processing server request: %s", r);
            c.accept(r);
        }
    }

    public static boolean isDisabled() {
        return disableServerRequests;
    }

}
