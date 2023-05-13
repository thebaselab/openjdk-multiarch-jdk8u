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
package com.azul.crs.client;

import com.azul.crs.client.Utils.Deadline;
import com.azul.crs.client.models.VMEvent;
import com.azul.crs.client.service.*;
import com.azul.crs.client.util.DnsDetect;
import com.azul.crs.runtime.utils.TempFilesFactory;
import com.azul.crs.util.logging.LogChannel;
import com.azul.crs.util.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import sun.launcher.LauncherHelper;

import static com.azul.crs.client.Utils.currentTimeCount;
import static com.azul.crs.client.Utils.currentTimeMillis;
import static com.azul.crs.client.Utils.elapsedTimeMillis;
import static com.azul.crs.client.VMSupport.CrsNotificationType.*;
import static com.azul.crs.util.logging.Logger.Level.TRACE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@LogChannel(value = "id", lowestUpstreamLevel = TRACE)
final class Agent001 {

    private static final long DEFAULT_SHUTDOWN_DELAY = 0; // Shutdown immediately
    private static final int FLUSH_THREAD_DEFAULT_PERIOD_MS = 1_000;
    private static final int FLUSH_THREAD_FORCE_DEFAULT_PERIOD_MS = 30 * 60 * 1000; // 30 minutes

    // Services and Monitors
    private static JFRMonitor jfrMonitor;
    private static GCLogMonitor gclogMonitor; // Still used by Zulu, until vmlogs support is added
    private static VMLogMonitor vmlogMonitor; // Used by Zing
    private static JarLoadMonitor jarLoadMonitor;
    private static ClassLoadMonitor classLoadMonitor;
    private static FirstCallMonitor firstCallMonitor;
    private static HeartbeatService heartbeatService;

    // Clients and VMSupport
    private static Client client;
    private static VMSupport vmSupport;
    private static VMToolingClient vmToolingClient;

    // Startup Thread
    private static Thread agentStartupThread;
    private static final Lock agentStartupThreadLock = new ReentrantLock();

    // Main Method Detection
    private static final AtomicReference<String> notifyToJavaCallAcceptedName = new AtomicReference<>();
    private static Thread mainMethodUpdateThread;
    private static final Lock mainMethodUpdateLock = new ReentrantLock();

    // Params
    private static int forceFlushTimeout = FLUSH_THREAD_FORCE_DEFAULT_PERIOD_MS;
    private static long delayTermination = DEFAULT_SHUTDOWN_DELAY;

    // Logging
    private static final Logger logger = Logger.getLogger(Agent001.class);
    private static final CRSLogMonitor crslogMonitor = new CRSLogMonitor();

    // Shutdown deadline
    private static volatile Deadline deadline;

    static {
        Logger.addOutputStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                crslogMonitor.notifyCRSLogEntry(b, off, len);
            }
        });
    }

    /**
     * Event constants are used by native code
     */
    // private static final int EVENT_GCLOG = ; // not yet implemented

    /**
     * Entry point to CRS Java agent.
     */
    public static void premain(String args, Instrumentation unused) {
        try {
            long agentStartTimestamp = currentTimeCount();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> teardownAgent(agentStartTimestamp)));
        } catch (IllegalStateException ignored) {
            // VM is shutting down. do not start CRS
            return;
        }

        Options.read(args);

        try {
            // Connect to VM and setup hooks to get notifications from it
            vmSupport = VMSupport.init(Options.getConnectionPort(), Options.getConnectionSecret());

            vmSupport.registerAgent(Agent001.class);

            Method notifyToJavaCall = Agent001.class.getMethod("notifyToJavaCall", String.class);
            vmSupport.registerCallback(EVENT_TO_JAVA_CALL, notifyToJavaCall);

            Method notifyClassLoad = Agent001.class.getMethod("notifyClassLoad", String.class, byte[].class, byte[].class, Integer.TYPE, Integer.TYPE, String.class);
            vmSupport.registerCallback(CRS_MESSAGE_CLASS_LOAD, notifyClassLoad);

            Method notifyFirstCall = Agent001.class.getMethod("notifyFirstCall", Integer.TYPE, String.class);
            vmSupport.registerCallback(CRS_MESSAGE_FIRST_CALL, notifyFirstCall);

            if (Options.sendJVMLogs.isYes()) {
                Method notifyVMLogEntry = Agent001.class.getMethod("notifyVMLogEntry", String.class, String.class);
                vmSupport.registerCallback(CRS_MESSAGE_VM_LOG_ENTRY, notifyVMLogEntry);
            }
        } catch (IOException | NoSuchMethodException | SecurityException ex) {
            logger.error("Failed to initialize", ex);
            return;
        }

        PerformanceMetrics.init();

        if (Options.forceSyncTimeout.isSet()) {
            forceFlushTimeout = Options.forceSyncTimeout.getInt() * 1000;
        }
        if (Options.delayTermination.isSet()) {
            delayTermination = Options.delayTermination.getLong();
        }

        System.setProperty("com.azul.crs.instance.options.delayTermination", Long.toString(delayTermination));

        // a special case, if CRS agent is started but there is no mode option specified
        // it's an indication that CRS was enabled with VM command line flag -XX:AzCRSMode=auto alone
        // this is equivalent to mode=auto option
        // TODO: mode==on,enable==true
        if ("on".equals(Options.mode.get())) {
            // In force mode we start agent activation without waiting for a main method
            // Otherwise activation may be triggered by the notifyToJavaCall callback
            activateAgent(null);
        } else if (!"auto".equals(Options.mode.get())) {
            // CRS is off? Unexpected state
        }
    }

    private static void activateAgent(String mainMethod) {
        if (hardstop()) {
            // too late...
            return;
        }

        agentStartupThreadLock.lock();
        try {
            if (agentStartupThread == null) {
                agentStartupThread = new Thread(() -> startupAgent(mainMethod), "CRSStartThread");
                agentStartupThread.setDaemon(true);
                agentStartupThread.start();
            } else if (mainMethod != null) {
                // activateAgent was called again - this means that we deal with enable=force run and main method has been
                // detected asynchronously. We need to wait for the startup thread to finish and only then post main method
                // name update
                try {
                    agentStartupThread.join();
                } catch (InterruptedException ex) {
                    Thread.interrupted();
                    return;
                }
                postMainMethodName(mainMethod);
            }
        } finally {
            agentStartupThreadLock.unlock();
        }
    }

    private static void startupAgent(String mainMethod) {
        long startTime = currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getUptime();

        try {
            // Connect CRS client to cloud
            client = new Client(getClientProps(), new Client.ClientListener() {
                private boolean connectionEstablished = false;

                @Override
                public void authenticated() {
                    if (client.getVmId() != null) {
                        logger.info("Agent authenticated: vmId=%s", client.getVmId());
                        if (logger.isEnabled(Logger.Level.DEBUG)) {
                            logger.debug(" VM uptime %dms", ManagementFactory.getRuntimeMXBean().getUptime());
                        }
                        if (!connectionEstablished) {
                            client.connectionEstablished();
                        }
                        connectionEstablished = true;
                    } else {
                        disableCRS("Backend malfunction, invalid vmId received", null);
                    }
                }

                @Override
                public void syncFailed(Result<String[]> reason) {
                    logger.error("Data synchronization to the CRS cloud has failed: %s",
                            reason.errorString());
                    if (reason.hasException()) {
                        IOException e = reason.getException();
                        if (e instanceof CRSException && ((CRSException) e).isProtocolFailure()) {
                            disableCRS("Protocol failure", e);
                        }
                    }
                }
            });

            if (logger.isEnabled(Logger.Level.DEBUG)) {
                String agentVersion;
                try {
                    agentVersion = client.getClientVersion() + '+' + client.getClientRevision();
                } catch (IOException ex) {
                    agentVersion = "UNKNOWN";
                }
                logger.debug("CRS agent (%s) started. Start time %d, VM uptime %dms", agentVersion, startTime, ManagementFactory.getRuntimeMXBean().getUptime());
            }

            // Create VM_CREATE event and post it to the queue
            postVMStart(startTime, mainMethod);

            // Create and start services
            if (Options.sendJVMLogs.isYes()) {
                jfrMonitor = JFRMonitor.getInstance(client, Options.lifetimejfr.get());
                jfrMonitor.start();
            }

            heartbeatService = HeartbeatService.getInstance(client);
            crslogMonitor.setClient(client);

            VMCRSCapabilities capabilities = VMCRSCapabilities.init();

            if (Options.sendJVMLogs.isYes()) {
                if (capabilities.has(VMCRSCapability.POST_VM_LOG_EVENTS)) {
                    vmlogMonitor = VMLogMonitor.getInstance(client);
                } else {
                    gclogMonitor = GCLogMonitor.getInstance(client, startTime);
                }
            }

            if (capabilities.has(VMCRSCapability.POST_CLASS_LOAD_EVENTS)) {
                classLoadMonitor = ClassLoadMonitor.getInstance(client);
            }

            if (capabilities.has(VMCRSCapability.POST_FIRST_CALL_EVENTS)) {
                firstCallMonitor = FirstCallMonitor.getInstance(client);
            }

            if (capabilities.has(VMCRSCapability.POST_JAR_LOAD_EVENTS)) {
                jarLoadMonitor = JarLoadMonitor.getInstance(client);
            }

            if (capabilities.has(VMCRSCapability.POST_VM_TOOLING_EVENT)) {
                vmToolingClient = VMToolingClient.getInstance(client);
                vmToolingClient.setJarLoadMonitor(jarLoadMonitor);
            }

            // Start client services
            client.startup();

            startServices(
                    crslogMonitor,
                    heartbeatService,
                    vmlogMonitor,
                    gclogMonitor,
                    classLoadMonitor,
                    firstCallMonitor,
                    jarLoadMonitor,
                    vmToolingClient);

            // Create and start events flushing thread
            EventsFlusher.start();

            // At this point CRS connectivity is set up and can get established once server is reachable.
            // Can perform long-running activities without delaying connection
            postNetworkInformation();
            postSystemInformation();
        } catch (Throwable th) {
            disableCRS("CRS failed to start: %s", th);
        }
    }

    // Called on VM Shutdown
    private static void teardownAgent(long agentStartTimestamp) {
        try {
            Deadline shutdownDeadline = Deadline.in(delayTermination, MILLISECONDS);
            deadline = shutdownDeadline;
            Client.setVMShutdownInitiated(shutdownDeadline);
            long shutdownStartTime = currentTimeCount();
            logger.trace("checking if startup is complete and waiting for it to finish (%d ms)", delayTermination);

            // In 'auto' mode we are waiting for a main method (see notifyToJavaCall, specifically the sleep()).
            // For very short-living processes we may fall into a situation when we still polling for the main
            // method but, at the same time application execution has finished and we are in the shutdown hook..
            // If we have some allowed time to hang on exit, we may wait for the startup thread to start..

            if (deadline.applyIfNotExpired(ms -> mainMethodUpdateLock.tryLock(ms, MILLISECONDS)).orElse(false)) {
                try {
                    if (mainMethodUpdateThread != null) {
                        deadline.runIfNotExpired(ms -> mainMethodUpdateThread.join(ms));
                    }
                } finally {
                    mainMethodUpdateLock.unlock();
                }
            }

            if (deadline.applyIfNotExpired(ms -> agentStartupThreadLock.tryLock(ms, MILLISECONDS)).orElse(false)) {
                try {
                    if (agentStartupThread != null) {
                        deadline.runIfNotExpired(ms -> agentStartupThread.join(ms));
                    }
                } finally {
                    agentStartupThreadLock.unlock();
                }
            }

            logger.debug("drain native queue");
            EventsFlusher.stop(shutdownDeadline);

            stopServices(shutdownDeadline,
                    vmToolingClient, // should be before monitors it serves..
                    heartbeatService,
                    jfrMonitor,
                    vmlogMonitor,
                    gclogMonitor,
                    classLoadMonitor,
                    firstCallMonitor,
                    jarLoadMonitor);

            if (client != null) {
                Map perfMonData = PerformanceMetrics.logPreShutdown(elapsedTimeMillis(shutdownStartTime));
                postVMShutdown(perfMonData);
                client.shutdown(shutdownDeadline);

                if (client.getVmId() != null) {
                    PerformanceMetrics.logShutdown(elapsedTimeMillis(shutdownStartTime));
                    PerformanceMetrics.report();
                    logger.info("Agent terminated: vmId=%s, runningTime=%d", client.getVmId(), elapsedTimeMillis(agentStartTimestamp));
                } else {
                    logger.info("Agent shut down during startup. Data is discarded. runningTime=%d", elapsedTimeMillis(agentStartTimestamp));
                }
            }
        } catch (InterruptedException e) {
            Thread.interrupted();
            // here we likely do not have Client properly initialized
            logger.error("Agent failed to process shutdown during startup. Data is discarded");
        } catch (Throwable th) {
            logger.error("Internal error or unexpected problem. CRS defunct. %s", th);
            th.printStackTrace(System.err);
        } finally {
            // clean-up all resources that agent might acquire during its lifetime
            try {
                TempFilesFactory.shutdown();
            } catch (Throwable t) {
            }
        }
    }

    /**
     * Posts VM start and gets created VM instance from response
     */
    private static void postVMStart(long startTime, String mainMethod) throws Exception {
        Map<String, Object> inventory = new Inventory()
                .populate(client.getEnvFilter(), client.getSysPropsFilter())
                .mainMethod(mainMethod)
                .toMap();

        logger.trace("Post VM start to CRS service");
        client.postVMStart(inventory, startTime);
    }

    private static void postMainMethodName(String mainMethod) {
        Map<String, Object> inventory = new Inventory()
                .mainMethod(mainMethod)
                .toMap();
        client.patchInventory(inventory);
    }

    private static void postNetworkInformation() {
        Map<String, Object> inventory = new Inventory()
                .networkInformation()
                .toMap();
        client.patchInventory(inventory);
    }

    private static void postSystemInformation() {
        Map<String, Object> inventory = new Inventory()
                .systemInformation()
                .toMap();
        client.patchInventory(inventory);
    }

    /**
     * Posts shutdown event directly to CRS service not using event queue that could be full or stopped already
     */
    private static void postVMShutdown(Map perfMonData) {
        logger.trace("Post VM shutdown to CRS service");
        List<VMEvent> trailingEvents = new ArrayList<>();
        trailingEvents.add(new VMEvent<>()
                .eventType(VMEvent.Type.VM_PERFORMANCE_METRICS)
                .randomEventId()
                .eventTime(currentTimeMillis())
                .eventPayload(perfMonData));
        client.postVMShutdown(trailingEvents);
    }

    private static void startServices(ClientService... services) {
        for (ClientService service : services) {
            if (service != null) {
                try {
                    service.start();
                } catch (Exception ex) {
                    logger.error("Agent failed to start " + service.serviceName() + ". Data is discarded");
                    ex.printStackTrace(System.err);
                }
            }
        }
    }

    private static void stopServices(Deadline shutdownDeadline, ClientService... services) {
        for (ClientService service : services) {
            if (service != null) {
                try {
                    service.stop(shutdownDeadline);
                } catch (Exception ex) {
                    logger.error("Agent failed to stop " + service.serviceName() + ". Data is discarded");
                    ex.printStackTrace(System.err);
                }
            }
        }
    }

    private static synchronized void shutdownAgent() {
        // Disable notifications from native - remove hooks
        for (VMSupport.CrsNotificationType t : VMSupport.CrsNotificationType.values()) {
            vmSupport.enableEventNotifications(t, false);
        }

        vmSupport.disableCRS();

        if (client != null) {
            client.cancel();
        }
    }

    private static void disableCRS(String cause, Throwable thr) {
        shutdownAgent();

        if (thr == null) {
            logger.error(cause);
        } else {
            logger.error(cause, thr);
            if (thr.getCause() != null) {
                logger.trace("caused by: %s", thr.getCause());
            }
        }
    }

    private static Map<Client.ClientProp, Object> getClientProps() throws CRSException {
        Map<Client.ClientProp, Object> clientProps = Options.getClientProps();
        boolean hasEndpointConfig = clientProps.get(Client.ClientProp.API_URL) != null;
        boolean hasMailboxConfig = clientProps.get(Client.ClientProp.API_MAILBOX) != null;
        if (!hasEndpointConfig || !hasMailboxConfig) {
            try {
                DnsDetect detector = new DnsDetect(Options.stackRecordId.get());
                Logger.getLogger(ConnectionManager.class).info("querying DNS record%s",
                        detector.getRecordNamePostfix().length() > 0 ? " (postfix " + detector.getRecordNamePostfix() + ")" : "");
                if (!hasEndpointConfig) {
                    clientProps.put(Client.ClientProp.API_URL, "https://" + detector.queryEndpoint());
                }
                if (!hasMailboxConfig) {
                    clientProps.put(Client.ClientProp.API_MAILBOX, detector.queryMailbox());
                }
            } catch (IOException ex) {
                throw new CRSException(CRSException.REASON_NO_ENDPOINT, "DNS query error and not enough configuration supplied", ex);
            }
        }
        clientProps.put(Client.ClientProp.VM_SHUTDOWN_DELAY, delayTermination);
        return clientProps;
    }

    /**
     * VM callback invoked when java method is called from native.
     *
     * This method is called from multiple threads (including users threads and JVM 'Service Thread')
     *
     * This callback is used during initial phase to detect main method and application class. When mode is in 'auto'
     * mode, we activate it if/when we detect the above data.
     *
     * @param name the name of the holder class of a method '.' method name
     */
    public static void notifyToJavaCall(String name) {
        Runnable r = null;
        if (name.startsWith("sun/launcher/LauncherHelper.checkAndLoadMain")) {
            r = () -> {
                try {
                    Class<?> appClass;
                    // let the Launcher code run for a while to determine the
                    // main class of the application. there is no way to be notified
                    // that it has done the work, so do poll
                    // Note that in case of an error in checkAndLoadMain (exception), getApplicationClass will never
                    // return non-null value and, in case of long shutdownDelay, we can spin here for a long time - so
                    // limit attempts to 50 ms. See CRS-4294.
                    for (int i = 0; i < 10; i++) {
                        if (hardstop()) {
                            return;
                        }
                        if ((appClass = LauncherHelper.getApplicationClass()) != null) {
                            mainMethodDetected(appClass.getName().replace('.', '/') + ".main");
                            return;
                        }
                        Thread.sleep(5);
                    }
                    logger.warning("Failed to retrieve ApplicationClass");
                } catch (InterruptedException ignored) {
                    Thread.interrupted();
                } catch (Throwable th) {
                    logger.error("Internal error or unexpected problem. CRS defunct. %s", th);
                }
            };
        } else if (!(name.startsWith("apple/security/AppleProvider")
                || name.startsWith("java/")
                || name.startsWith("javax/")
                || name.startsWith("sun/")
                || name.startsWith("com/sun/")
                || name.startsWith("com/fasterxml") // used by CRS itself
                || name.startsWith("org/jcp")
                || name.startsWith("com/azul/crs")
                || name.startsWith("com/azul/tooling")
                || name.startsWith("jdk/jfr"))) {
            // ignore calls which are not about to indicate that application is being started

            // TODO: BUG: RACE:
            //   The assumption below is WRONG. It is not GUARANTEED that sun/launcher/LauncherHelper.checkAndLoadMain
            //   is observed BEFORE a name that falls through the filters as notifyToJavaCall is called from multiple
            //   threads!
            //   It is not defined which accepted name will be used for the patch in the event of a race condition.

            // use of sun launcher was not detected, assuming direct call from custom native code
            // in this case the best we can do is to use the name of the first method called
            r = () -> mainMethodDetected(name);
        }

        if (r != null) {
            if (!notifyToJavaCallAcceptedName.compareAndSet(null, name)) {
                logger.warning("notifyToJavaCall - name %s has been already accepted, skip %s", notifyToJavaCallAcceptedName.get(), name);
                return;
            }

            logger.debug("notifyToJavaCall name '%s' accepted.", name);
            vmSupport.enableEventNotifications(EVENT_TO_JAVA_CALL, false);

            mainMethodUpdateLock.lock();
            try {
                Thread thread = new Thread(r, "CRSMainMethodUpdate");
                thread.setDaemon(true);
                thread.start();
                mainMethodUpdateThread = thread;
            } finally {
                mainMethodUpdateLock.unlock();
            }
        }
    }

    // Agent is activated either if this is forced or if mode is 'auto' and main method became known.
    // Also we do not want to run agent for tools (like javac).
    // So setting main method may trigger agent activation as well as disable it
    private static void mainMethodDetected(String mainMethod) {
        if (!mainMethod.startsWith("com/sun/tools")) {
            // Activate if was not started yet
            activateAgent(mainMethod);
        } else {
            // TODO: Do we want to shutdown it in the force mode as well?
            // If yes, then this should be done more carefully.
            shutdownAgent();
        }
    }

    /**
     * VM callback invoked when java method was called the first time.
     *
     * @param classId id of the class this method belongs to
     * @param name the name of the holder class of a method '.' method name
     */
    public static void notifyFirstCall(int classId, String name) {
        firstCallMonitor.notifyMethodFirstCalled(classId, name);
    }

    /**
     * VM callback invoked each time class is loaded.
     *
     * This method is called from the "CRS Listener Thread" only (which is a VM thread from the "system" group).
     *
     * className name of the loaded class originalHash SHA-256 hash of the class file before transformation if it is
     * transformed, otherwise null hash SHA-256 hash of the class file as it is used by the VM classId the unique id of
     * the class
     */
    public static void notifyClassLoad(String className, byte[] originalHash, byte[] hash, int classId, int loaderId, String source) {
        if (jarLoadMonitor != null) {
            jarLoadMonitor.notifyClassSourceSeen(source);
        }
        classLoadMonitor.notifyClassLoad(className, originalHash, hash, classId, loaderId, source);
    }

    /**
     * VM callback invoked each time log entry if reported by VM.
     */
    public static void notifyVMLogEntry(String logName, String entry) {
        vmlogMonitor.notifyVMLogEntry(logName, entry);
    }

    private static boolean hardstop() {
        Deadline d = deadline;
        return d != null && d.hasExpired();
    }

    private static enum VMCRSCapability {
        POST_CLASS_LOAD_EVENTS,
        POST_FIRST_CALL_EVENTS,
        POST_NOTIFY_TO_JAVA_CALLS,
        POST_VM_LOG_EVENTS,
        POST_JAR_LOAD_EVENTS,
        POST_VM_TOOLING_EVENT;
    }

    private static final class VMCRSCapabilities {

        private final Set<VMCRSCapability> capabilities;

        private VMCRSCapabilities(Set<VMCRSCapability> vmcrsCapabilities) {
            this.capabilities = Collections.unmodifiableSet(vmcrsCapabilities);
            logger.trace("Active VMCRSCapabilities: " + capabilities);
        }

        private boolean has(VMCRSCapability cap) {
            return capabilities.contains(cap);
        }

        private static VMCRSCapabilities init() {
            Set<VMCRSCapability> active = new HashSet<>();
            // in case VM has no tooling interface classes
            try {
                if (VMToolingClient.isToolingImplemented()) {
                    active.addAll(Arrays.asList(
                            VMCRSCapability.POST_VM_TOOLING_EVENT,
                            VMCRSCapability.POST_JAR_LOAD_EVENTS));
                }
            } catch (Exception ignored) {
            }
            String[] reported = vmSupport.getVMCRSCapabilities();
            if (reported != null) {
                for (String vmcrsCapability : reported) {
                    try {
                        active.add(VMCRSCapability.valueOf(vmcrsCapability));
                    } catch (IllegalArgumentException e) {
                        logger.trace("VM reported unknown capability: " + vmcrsCapability);
                        // skip
                    }
                }
            } else {
                // Assume default capabilities...
                active.addAll(Arrays.asList(
                        VMCRSCapability.POST_CLASS_LOAD_EVENTS,
                        VMCRSCapability.POST_FIRST_CALL_EVENTS,
                        VMCRSCapability.POST_NOTIFY_TO_JAVA_CALLS));
            }
            return new VMCRSCapabilities(active);
        }
    }

    private static final class EventsFlusher implements Runnable {

        private static volatile boolean stopped = false;
        private static Thread thread = null;

        private static final Object flushLock = new Object();
        private static final Object threadLock = new Object();

        private EventsFlusher() {
        }

        public static void start() {
            synchronized (threadLock) {
                if (thread != null || stopped) {
                    return;
                }

                thread = new Thread(new EventsFlusher(), "CRSEventsFlushingThread");
                thread.setDaemon(true);
                thread.start();
            }
        }

        public static void stop(Deadline deadline) {
            synchronized (flushLock) {
                stopped = true;
                flushLock.notify();
            }

            synchronized (threadLock) {
                try {
                    if (thread != null) {
                        deadline.runIfNotExpired(ms -> thread.join(ms));
                    }
                } catch (InterruptedException ex) {
                }
            }
        }

        // Periodically directs VM to flush accumulated buffers.
        // On buffers flushing events are delivered to the agent via notification callbacks
        @Override
        public void run() {
            try {
                long previousForceFlushTime = currentTimeCount();
                while (true) {
                    synchronized (flushLock) {
                        try {
                            flushLock.wait(FLUSH_THREAD_DEFAULT_PERIOD_MS);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    if (stopped) {
                        break;
                    }
                    boolean forceFlush = elapsedTimeMillis(previousForceFlushTime) >= forceFlushTimeout;
                    if (forceFlush) {
                        previousForceFlushTime = currentTimeCount();
                    }
                    vmSupport.drainQueues(forceFlush, false);
                }

                vmSupport.drainQueues(true, true);
            } catch (Throwable th) {
                logger.error("Internal error or unexpected problem. CRS defunct. %s", th);
            }
        }
    }
}
