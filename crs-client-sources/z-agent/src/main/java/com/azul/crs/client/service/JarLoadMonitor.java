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
import com.azul.crs.client.Inventory;
import com.azul.crs.client.Utils.Deadline;
import com.azul.crs.client.Utils;

import com.azul.crs.client.models.ServerRequest;
import com.azul.crs.client.models.VMArtifact;
import com.azul.crs.client.models.VMEvent;
import com.azul.crs.client.PerformanceMetrics;
import com.azul.crs.client.service.VmJarInfoRequestSupport.*;
import com.azul.crs.digest.Digest;
import com.azul.crs.digest.ShadedClassHashCalculator;
import com.azul.crs.jar.ZipTools.ZipFileClosedException;
import com.azul.crs.jar.ZipTools;
import com.azul.crs.runtime.utils.DataEntriesMap;
import com.azul.crs.runtime.utils.KnownAzulRuntimeContainers;
import com.azul.crs.runtime.utils.TempFilesFactory;
import com.azul.crs.util.logging.LogChannel;
import com.azul.crs.util.logging.Logger;

import java.io.*;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.jar.*;
import java.util.regex.Pattern;

import static com.azul.crs.client.Utils.currentTimeMillis;
import static com.azul.crs.client.Utils.encodeToStringOrNull;
import static com.azul.crs.client.models.VMEvent.Type.*;
import static com.azul.crs.runtime.utils.URLHelper.*;

@LogChannel("service.jarload")
public final class JarLoadMonitor implements ClientService {

    private static final boolean DEBUG =                     Boolean.getBoolean("com.azul.crs.jarload.debug");
    private static final boolean jarLoadByClassLoad =        getBooleanProperty("com.azul.crs.jarload.jarLoadByClassLoad", true);

    private static final boolean sendCentralDirectoryHash =  Boolean.getBoolean("com.azul.crs.jarload.sendCentralDirectoryHashOnJarLoad");
    private static final boolean sendJarEntriesHashes =      getBooleanProperty("com.azul.crs.jarload.sendJarEntriesHashesOnJarLoad", true);
    private static final boolean sendJarEntries =            getBooleanProperty("com.azul.crs.jarload.sendJarEntriesOnJarLoad", false);
    private static final boolean sendJarEntriesShadedHashes= getBooleanProperty("com.azul.crs.jarload.sendJarEntriesShadedHashesOnJarLoad", true);
    private static final boolean sendJarFile =               Boolean.getBoolean("com.azul.crs.jarload.sendJarFileOnJarLoad");
    private static final String  allowedToSendJarFiles =     System.getProperty("com.azul.crs.jarload.allowedToSendJarFilesListOnJarLoad");
    private static final String  sendJarEntriesList =        System.getProperty("com.azul.crs.jarload.sendJarEntriesListOnJarLoad", "**/META-INF/MANIFEST.MF,**/pom.properties");
    private static final boolean recursiveJarDiscovery =     getBooleanProperty("com.azul.crs.jarload.recursiveJarDiscoveryOnJarLoad", true);

    private static final int     tempfilesTimeToLive =       getIntProperty("com.azul.crs.jarload.tempfilesTimeToLive", 30 * 60 * 1000);

    // mostly for testing
    private static final boolean forceFullJarLoadedEvents =  getBooleanProperty("com.azul.crs.jarload.forceFullJarLoadedEvents", false);

    private static final int     dedupSize =                 getIntProperty("com.azul.crs.jarload.dedupSize", 100);

    private static final JarLoadMonitor instance = new JarLoadMonitor();
    private Client client;
    private AtomicBoolean started = new AtomicBoolean();
    private volatile Deadline deadline;
    private final PrintWriter traceOut;
    private final MessageDigest md;
    private ZipTools zt;
    private JarEntryAccess jarEntryAccess;
    private JarFileAccess jarFileAccess;
    private static final Set<String> knownVmJars = initKnownVmJars();

    private static final Object activeTasksLock = new Object();
    private static final Set<NotificationTask> activeTasks = new HashSet<>();

    // structure encapsulating knowledge about has jar being processed already or not
    // we are counting the jar as processed in case if same URL was processed or
    // if we met already same central directory hash + size
    private static final class ProcessedJarFiles {
        private final String[] alreadyProcessedMetaList = new String[dedupSize];
        private final Set<String> alreadyProcessedMetaSet = new HashSet<>();
        private int alreadyProcessedIdx = 0;

        private static final ProcessedJarFiles knownURLs = new ProcessedJarFiles();
        private static final ProcessedJarFiles knownCDHashes = new ProcessedJarFiles();

        public boolean isAlreadyProcessed(String meta) {
            synchronized (alreadyProcessedMetaList) {
                return alreadyProcessedMetaSet.contains(meta);
            }
        }

        public void setAlreadyProcessed(String meta) {
            synchronized (alreadyProcessedMetaList) {
                if (alreadyProcessedMetaSet.add(meta)) {
                    String toRemove = alreadyProcessedMetaList[alreadyProcessedIdx];
                    if (toRemove != null) {
                        alreadyProcessedMetaSet.remove(toRemove);
                    }
                    alreadyProcessedMetaList[alreadyProcessedIdx] = meta;
                    alreadyProcessedIdx = (alreadyProcessedIdx + 1) % alreadyProcessedMetaList.length;
                }
            }
        }

        private static String getJarUUID(String metaUrl, JarFile jar, ZipTools.JarShortDigest jd) {
            String centralDirectoryHashString = jd == null ? null : encodeToStringOrNull(jd.getCentralDirectoryHash());
            return centralDirectoryHashString + ":" + jar.size();
        }

        public static boolean isAlreadyProcessed(String metaUrl, JarFile jar, ZipTools.JarShortDigest jd) {
            assert(jar != null);
            assert(jd != null);

            final String jarid = getJarUUID(metaUrl, jar, jd);
            return knownURLs.isAlreadyProcessed(metaUrl) || knownCDHashes.isAlreadyProcessed(jarid);
        }

        public static boolean isAlreadyProcessedURL(String metaUrl) {
            return knownURLs.isAlreadyProcessed(metaUrl);
        }

        public static void setAlreadyProcessed(String metaUrl, JarFile jar, ZipTools.JarShortDigest jd) {
            assert(metaUrl != null);
            assert(jar != null);
            assert(jd != null);

            final String jarid = getJarUUID(metaUrl, jar, jd);
            knownURLs.setAlreadyProcessed(metaUrl);
            knownCDHashes.setAlreadyProcessed(jarid);
        }

    }

    private static final class JarEntryAccess {
      private static final String prefix = "**/";

      private final Collection<String> entireFileNameMatch;
      private final Collection<String> suffixMatch;

      JarEntryAccess(String prop) {
        entireFileNameMatch = new ArrayList<>();
        suffixMatch = new ArrayList<>();

        for (String entryFilter : prop.split(",")) {
          if (entryFilter.startsWith(prefix)) {
            suffixMatch.add(entryFilter.substring(prefix.length()));
          } else {
            entireFileNameMatch.add(entryFilter);
          }
        }
      }

      public boolean isAllowed(String name) {
        if (name == null) return false;
        if (entireFileNameMatch.contains(name)) return true;
        for (String suffix : suffixMatch) {
          int nameLen = name.length();
          int suffixLen = suffix.length();
          if (nameLen >= suffixLen && name.endsWith(suffix) &&
              (nameLen == suffixLen || name.charAt(nameLen-suffixLen-1) == '/')) return true;
        }
        return false;
      }
    }

    //TODO: merge with JarEntryAccess to common access interface
    private static final class JarFileAccess {
      private final Collection<Pattern> patterns;

      JarFileAccess(String prop) {
        patterns = new ArrayList<>();

        if (prop != null && prop.length() > 0) {
          for (String p : prop.split(",")) {
            p = p.replaceAll("\\*\\*", "%%%%1%%%%");
            p = p.replaceAll("\\*", "%%%%2%%%%");
            p = p.replaceAll("%%%%1%%%%", ".*");
            p = p.replaceAll("%%%%2%%%%", "[^/]*");

            patterns.add(Pattern.compile(p));
          }
        }
      }

      public boolean isAllowed(String name) {
        for (Pattern p : patterns) {
          if (p.matcher(name).matches()) return true;
        }
        return false;
      }
    }

    public static enum InitiatedBy {
      CLASS_LOADING,
      JDK_NATIVE_LOADING,
      RECURSIVE_LOADING,
      SERVER_REQUEST,
      OTHER
    }

    private JarLoadMonitor() {
        MessageDigest digest = null;

        try {
          digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
          logger().error("Failed to initialize SHA-256 MessageDigest: %s", e);
          stop(Deadline.in(0, TimeUnit.MILLISECONDS));
        }

        md = digest;
        zt = ZipTools.createDefault();
        jarEntryAccess = new JarEntryAccess(sendJarEntriesList);
        jarFileAccess = new JarFileAccess(allowedToSendJarFiles);

        PrintWriter out = null;
        if (logger().isEnabled(Logger.Level.TRACE)) {
            try {
                Path traceOutFileName = Files.createTempFile("CRSJarLoadMonitor", ".log");
                logger().trace("writing JarLoadMonitor trace to file %s", traceOutFileName);
                out = new PrintWriter(Files.newBufferedWriter(traceOutFileName));
            } catch (IOException e) {
                logger().error("Cannot trace events into file: %s", e);
            }
        }
        traceOut = out;
    }

    public static JarLoadMonitor getInstance(Client client) {
        instance.client = client;
        return instance;
    }

    /** Creates VM event object with given class load details */
    private VMEvent<Map<String, Object>> jarLoadEvent(String url, InitiatedBy initiatedBy, int recursionDepth,
                                 String jarName, long eventTime, String hashString,
                                 String manifestHashString, String centralDirectoryProvider,
                                 Long centralDirectoryLength, List<String> entries,
                                 Set<MavenComponent> mavenComponents, Map<String, Long> stats) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("url", url);
        payload.put("jarName", jarName);
        payload.put("centralDirectoryHash", hashString);
        payload.put("manifestHash", manifestHashString);
        payload.put("centralDirectoryExtractionMethod", centralDirectoryProvider);
        payload.put("centralDirectoryLength", centralDirectoryLength != null ? Long.toString(centralDirectoryLength) : null);
        payload.put("entries", entries);
        payload.put("initiatedBy", initiatedBy);
        payload.put("recursionDepth", recursionDepth);
        payload.put("mavenComponents", mavenComponents);
        payload.put("stats", stats);

        return new VMEvent<Map<String, Object>>()
            .eventType(VM_JAR_LOADED)
            .eventTime(eventTime)
            .eventPayload(payload);
    }

    @Override
    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            logger().error("JarLoadMonitor has been started already");
            return;
        }

        if (hardstop()) {
            return;
        }

        // Register server requests listener
        // If a request arrives, issue VMJarLoadedEvent for the requested jar, forcing hashes calculation
        ServerRequestsService.addListener(VmJarInfoRequest.class, r -> processVmJarInfoRequest(r));
    }

    private void processVmJarInfoRequest(VmJarInfoRequest request) {
        if (hardstop()) {
            return;
        }

        if (request.getDetailsLevel() == VmJarInfoRequest.DetailsLevel.NONE) {
            // This is an ancknowlegement that server knows all needed details about this jar and we may remove a
            // temporary file associated with it (if any);
            final File file = new File(request.getPath());
            if (TempFilesFactory.deleteIfScheduled(file, () -> PerformanceMetrics.logJarCacheDeleted(file.length()))) {
                logger().debug("Removed temporary file %s after server acknowlegement", request.getPath());
            }
            return;
        }

        NotificationTask task = new NotificationTask(request.getPath());
        boolean result = false;
        try {
            addActiveTask(task);

            String path = request.getPath();
            try (JarFile file = new JarFile(path, false)) {
                File f = null;
                try {
                    f = new File(path);
                } catch (Exception e) {
                }
                result = postVMJarLoadedEvent(new URL("file://" + path), file, f, null, request.getUrl(), InitiatedBy.SERVER_REQUEST, 0, true);
            } catch (IOException ex) {
                logger().error("Exception %s", ex);
            }
        } finally {
            task.setCompletionStatus(result);
            removeActiveTask(task);
        }
    }

    @Override
    public synchronized void stop(Deadline deadline) {
        this.deadline = deadline;

        try {
            // Waiting for BOTH - server requests, and VM_JAR_LOADED in-flight
            // events to be processed, as they both can generate each other.
            while (!hardstop()
                    && (ServerRequestsService.getRequestsCount() > 0
                    || VM_JAR_LOADED.getInFlightEventsCounter() > 0)) {
                ServerRequestsService.waitAllRequestsProcessed(deadline);
                VM_JAR_LOADED.waitAllEventsProcessed(deadline);
            }
        } catch (InterruptedException ex) {
            // not expected, clear interrupted state.
            Thread.interrupted();
        }

        waitActiveTasks(deadline);

        if (traceOut != null) {
            traceOut.close();
        }
    }

    private boolean hardstop() {
        return deadline != null && deadline.hasExpired();
    }

    private void addActiveTask(NotificationTask task) {
        synchronized (activeTasksLock) {
            activeTasks.add(task);
        }
    }

    private void removeActiveTask(NotificationTask task) {
        synchronized (activeTasksLock) {
            activeTasks.remove(task);
            activeTasksLock.notify();
        }
        if (!task.isCompletedSuccessfully()) {
            logger().warning("There were issues processing %s", task.description);
        }
    }

    private void waitActiveTasks(Deadline deadline) {
        synchronized (activeTasksLock) {
            while (!hardstop() && !activeTasks.isEmpty()) {
                try {
                    logger().debug("Waiting for " + activeTasks + " in progress VM_JAR_LOADED events");
                    activeTasksLock.wait(Math.max(1, deadline.remainder(TimeUnit.MILLISECONDS)));
                } catch (InterruptedException ex) {
                    Thread.interrupted();
                    break;
                }
            }
            if (!activeTasks.isEmpty()) {
                logger().warning("VM_JAR_LOADED_EVENTs processing has timed out. %s still not processed. You may want to increase delayTermination to avoid this.", activeTasks);
            }
        }
    }

    private static URL getJarURL(URL url) {
        String source = url.toString();

        // sometimes (Jar)Loader's URL  may contain exact .class file path, or directory
        // extracting jar path in that case: hello to spring-boot
        if (source.contains("!/")) {
          source = source.substring(0, source.lastIndexOf("!/"));
          if (!source.contains("!/") && source.startsWith("jar:")) {
            source = source.substring(4);
          }
        }

        if (!source.endsWith(".jar!/") &&
            !source.endsWith(".war!/") &&
            !source.endsWith(".jar") &&
            !source.endsWith(".war")) {
          Logger.getLogger(JarLoadMonitor.class).debug("given url=" + url + " does not have jar to be reported for load event. source=" + source);
          return null;
        }

        try {
          return new URL(source);
        } catch (Exception e) {
          Logger.getLogger(JarLoadMonitor.class).warning("Failed to construct jar url from url=%s, modified source string=%s", url, source, e);
          return url;
        }
    }

    /**
     * VM callback invoked each time jar is loaded.
     *
     * @param url URL of the loaded jar.
     * @param jar JarFile of the loaded jar.
     * @return {@code JarShortDigest} for the requested file
     * @throws IOException if calculation of the digest failed due to I/O problems
     */
    private ZipTools.JarShortDigest getJarCentralDirectorySignature(URL url, JarFile jar) throws IOException {
        try {
            URL jarURL = getJarURL(url);
            if (null == jarURL) return null;

            ZipTools.JarShortDigest jd = zt.getDigest((MessageDigest) md.clone(), jarURL, jar);
            if (jd == null) return null;

            if (DEBUG) {
                System.out.println(">>> notifyJarLoad url=" + url
                        + "\njar=" + jar
                        + "\ncentralDirectoryHashString=" + encodeToStringOrNull(jd.getCentralDirectoryHash())
                        + "\nmanifestHashString=" + encodeToStringOrNull(jd.getManifestHash()));
            }

            if (traceOut != null) {
                traceOut.println(url.toString());
            }

            return jd;
        } catch (CloneNotSupportedException e) {
            // unexpected
            return null;
        }
    }

    /**
     * Synchronously upload jar file as an artifact.
     *
     * @param url jar url (is added as tag to the metadata)
     * @param jar jar file to upload
     * @return true on success. false otherwise
     */
    private boolean sendJar(URL url, JarFile jar) {
        if (!jarFileAccess.isAllowed(jar.getName())) {
            logger().debug("jar=%s file is not allowed to upload. skip", jar.getName());
            return true;
        }

        URL jarUrl = getJarURL(url);

        if (jarUrl == null) {
            return false;
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", jar.getName());
        metadata.put("url", url.toString());
        metadata.put("tags", Inventory.instanceTags());

        return syncUpload(jar.getName(), VMArtifact.Type.JAR, metadata, (artifactId, output) -> {
            logger().info("sendJar jar=%s url=%s artifactId=%d", jar.getName(), url, artifactId);
            try (InputStream is = jarUrl.openConnection().getInputStream()) {
                Utils.transfer(is, output);
            }
        });
    }

    private void visitJarEntries(JarFile jar, List<Consumer<JarEntry>> visitors) {
        Enumeration<JarEntry> entries = jar.entries();
        while (!hardstop() && entries.hasMoreElements()) {
            JarEntry je = entries.nextElement();
            if (!je.isDirectory()) {
                visitors.forEach(c -> c.accept(je));
            }
        }
    }

    /**
     * Enqueue VM_JAR_LOAD events for nested jars.
     *
     * Can be called concurrently from multiple threads!
     *
     * @param parentMetaUrl url of the enclosing jar
     * @param parentJar enclosing jar
     * @param recursionDepth level of recursion (jar-in-jar-in-jar..)
     * @return true on success (all entries have been processed), false otherwise. The method fails
     * on the first unsuccessfully processed entry
     */
    private boolean notifyNestedJars(String parentMetaUrl, JarFile parentJar, int recursionDepth) throws IOException {
        for (JarEntry je : Collections.list(parentJar.entries())) {
            if (ZipTools.isJarFile(je.getName())) {
                try (InputStream is = parentJar.getInputStream(je)) {
                    boolean keepTempFile = false;
                    final TempFilesFactory.TempFile tempFile = TempFilesFactory.createTempJarFile();
                    try {
                        Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        PerformanceMetrics.logJarCacheCreated(tempFile.length());
                        // url leading to real dumped file on fs
                        URL url = tempFile.toURL();
                        // "nice"-url that mimics nested jar convention below (reportableURL).
                        //
                        // Please notice that real URL for nested jars would look somehow like
                        //    jar:jar:file:1.jar!/2.jar!/some.class
                        // But by default nested jars are not allowed and since URL constructor
                        // is trying to instantiate protocol handler.
                        // Such urls will lead to MalformedURLException("Nested JAR URLs are not supported")
                        //
                        // So currently we are sending "fake"-URL to back-end
                        // trying to keep info about nesting at the same moment.
                        //
                        // In contrast to URL, URI is abstract and doesn't instantiate Handler,
                        // so it may be considered to change entire notify.jarload API to use
                        // URIs instead of URLs.
                        //
                        // TBD: anyway
                        String reportableURL = parentMetaUrl + je.getName() + "!/";

                        // Do not use URL.openConnection since this may cause to load jar by spring JarLoader
                        //     JarFile jarFile = ((JarURLConnection)url.openConnection()).getJarFile();
                        try (JarFile jarFile = new JarFile(tempFile)) {

                            // Ensure that jar was not processed already
                            ZipTools.JarShortDigest jd = getJarCentralDirectorySignature(url, jarFile);

                            String metaUrl = parentMetaUrl + "!/" + je.getName();
                            if (ProcessedJarFiles.isAlreadyProcessed(metaUrl, jarFile, jd)) {
                                continue;
                            }

                            // In case if ServerRequestsService is disabled there is not a reason to keep temp file,
                            // since there will not be any requests for details about specific jar file.
                            keepTempFile = !ServerRequestsService.isDisabled();
                            TempFilesFactory.scheduleDeletion(tempFile, tempfilesTimeToLive, () -> PerformanceMetrics.logJarCacheDeleted(tempFile.length()));

                            if (!notifyJarLoad(url, jarFile, tempFile, jd, InitiatedBy.RECURSIVE_LOADING, reportableURL, recursionDepth)) {
                                return false;
                            }
                        }
                    } finally {
                        if (!keepTempFile) {
                            PerformanceMetrics.logJarCacheDeleted(tempFile.length());
                            tempFile.delete();
                        }
                    }
                } catch (Exception e) {
                    logger().error("failed to dump nested jar entry %s/%s: %s", parentJar.getName(), je.getName(), e);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * VM callback invoked for each loaded jar file.
     *
     * Can be called concurrently from multiple threads!
     * Can be called recursively.
     * This is a synchronous operation.
     *
     * @param url URL of the jar file (loaded or cached copy)
     * @param jar JarFile of the loaded jar
     * @param file File representation of the loaded jar on filesystem if it exists.
     *             Need to be preserved for early deletion if it is the instance of
     *             the temp file
     * @param initiatedBy notification origin
     * @param metaUrl jar url (with ! for recursive jars...)
     * @param recursionDepth used when jar-in-jar is reported recursively
     * @return whether the notification was sent successfully
     */
    private boolean notifyJarLoad(URL url, JarFile jar, File file, ZipTools.JarShortDigest jd, InitiatedBy initiatedBy, String metaUrl, int recursionDepth) {
        logger().trace("notifyJarLoad %s initiated by %s", url, initiatedBy);

        if (hardstop()) {
            logger().debug("Skip processing notifyJarLoad because of hardstop");
            return false;
        }

        if (!started.get()) {
            logger().error("service is not yet started");
            return false;
        }

        if (url == null || jar == null) {
            logger().debug("Skip processing invalid notifyJarLoad");
            return false;
        }

        metaUrl = toNormalizedJarURL(metaUrl);

        boolean result = false;
        NotificationTask task = new NotificationTask(jar.getName());
        addActiveTask(task);

        try {
            /**
             * We do not have full control on the passed jar file and it can be closed concurrently at any moment. In
             * such case an attempt is made to open a jar by URL (but only if this url points to a local file).
             */
            if (url.toString().startsWith("file:") || url.toString().startsWith("jar:file:")) {
                boolean reopened = false;
                int attempts = 2;
                try {
                    while (!result && attempts-- > 0) {
                        try {
                            jd = jd == null ? getJarCentralDirectorySignature(url, jar) : jd;
                            result = notifyJarLoadImpl(url, jar, file, jd, initiatedBy, metaUrl, recursionDepth);
                        } catch (ZipFileClosedException ex) {
                            jar = new JarFile(url.getPath(), false);
                            reopened = true;
                            logger().debug("Reopening %s because jar file has been closed", url);
                        }
                    }
                } finally {
                    if (reopened) {
                        jar.close();
                    }
                }
            } else {
                result = notifyJarLoadImpl(url, jar, file, jd, initiatedBy, metaUrl, recursionDepth);
            }

            task.setCompletionStatus(result);
        } catch (IOException ex) {
            logger().warning("Exception while processing notifyJarLoad (%s)", url, ex);
        } finally {
            removeActiveTask(task);
        }
        return task.isCompletedSuccessfully();
    }

    /**
     * Depending on the settings, creates and enqueues VM_JAR_LOAD events, uploads jar entries,
     * processes nested jars, etc.
     *
     * Can be called concurrently from multiple threads!
     *
     * No notifications sent after the service is stopped (shutdown timeout expired or if the event
     * occurs after a shutdown request that has successfully waited for all previous requests).
     *
     * @return true if and only if notification was successfully enqueued and all pre-required
     * uploads were completed successfully.
     */
    private boolean notifyJarLoadImpl(URL url, JarFile jar, File file, ZipTools.JarShortDigest jd, InitiatedBy initiatedBy, String metaUrl, int recursionDepth) throws IOException {
        if (knownVmJars.contains(extractContainerPathFromURL(metaUrl))) {
            logger().trace("Skip VM JAR reporting: %s", metaUrl);
            return true;
        }

        if (jd == null) {
            jd = getJarCentralDirectorySignature(url, jar);
        }

        if (ProcessedJarFiles.isAlreadyProcessed(metaUrl, jar, jd)) {
          logger().trace("Skip already processed: %s", metaUrl);
          return true;
        }

        boolean result = !hardstop() & postVMJarLoadedEvent(url, jar, file, jd, metaUrl, initiatedBy, recursionDepth, forceFullJarLoadedEvents);
        if (recursiveJarDiscovery) result &= !hardstop() & notifyNestedJars(metaUrl, jar, recursionDepth + 1);
        // TODO: remove? restore?
        // if (sendJarFile)        result &= !hardstop() & sendJar(url, jar);
        // if (sendJarEntries)     result &= !hardstop() & sendEntries(url, jar);
        return result;
    }

    /**
     * Send jar entries as artifact.
     * @param url entry url (is added as tag to the metadata)
     * @param jar jar file to send entries from
     * @return true on success (all entries have been send), false otherwise. The method might exit
     * early in case of failure in sending an entry.
     */
    private boolean sendEntries(URL url, JarFile jar) {
        return jar.stream()
                .filter(e -> jarEntryAccess.isAllowed(e.getName()))
                .map(e -> uploadJarEntry(url, jar, e.getName()))
                .allMatch(Boolean.TRUE::equals);
    }

    private static final int VM_JAR_LOADED_EVENT_INLINE_PAYLOAD_THRESHOLD = 512 * 1024; // 512 Kb

    private static enum Hashes {
        ENTRY_CRC32,
        ENTRY_SIZE,
        SHA256,
        CLASS_SHADED_HASH
    }

    private boolean postVMJarLoadedEvent(URL url, JarFile jar, File file, ZipTools.JarShortDigest jd, String metaUrl, InitiatedBy initiatedBy, int recursionDepth, boolean withDetails) throws IOException {
        Map<String, Long> stats = new HashMap<>();
        jd = jd == null ? getJarCentralDirectorySignature(url, jar) : jd;

        logger().debug("postVMJarLoadedEvent %s initiated by %s (with details: %b)", metaUrl, initiatedBy, withDetails);

        List<Consumer<JarEntry>> visitors = new ArrayList<>();
        DataEntriesMap<Hashes> entriesMap = new DataEntriesMap(Hashes.class);
        Set<MavenComponent> mavenComponents = new HashSet<>();

        if (withDetails) {
            AtomicLong sendJarEntriesHashesTime = new AtomicLong();
            if (sendJarEntriesHashes) {
                visitors.add(e -> {
                    sendJarEntriesHashesTime.addAndGet(-System.nanoTime());
                    try {
                        DataEntriesMap.DataEntry entry = entriesMap.getEntry(e.getName());
                        entry.put(Hashes.ENTRY_CRC32, String.format("%08x", e.getCrc()));
                        entry.put(Hashes.ENTRY_SIZE, Long.toString(e.getSize()));
                    } finally {
                        sendJarEntriesHashesTime.addAndGet(System.nanoTime());
                    }
                });
            }

            AtomicLong sendJarEntriesShadedHashesTime = new AtomicLong();
            if (sendJarEntriesShadedHashes) {
                visitors.add(e -> {
                    sendJarEntriesShadedHashesTime.addAndGet(-System.nanoTime());
                    try {
                        String name = e.getName();
                        if (name.endsWith(".class")) {
                            try {
                                Digest sha256 = Digest.get();
                                Digest shadedHash = Digest.get();

                                try (InputStream is = jar.getInputStream(e)) {
                                    InputStream bis = new BufferedInputStream(is);
                                    bis = wrapStream(bis, sha256);
                                    ShadedClassHashCalculator.updateHash(bis, shadedHash);
                                    entriesMap.put(name, Hashes.CLASS_SHADED_HASH, shadedHash.asHexString());
                                    bis.skip(Long.MAX_VALUE); // Read to the end of the stream
                                    entriesMap.put(name, Hashes.SHA256, sha256.asHexString());
                                }
                            } catch (IOException | IllegalAccessException ex) {
                                logger().error("Exception: %s", ex);
                            }
                        }
                    } finally {
                        sendJarEntriesShadedHashesTime.addAndGet(System.nanoTime());
                    }
                });
            }

            AtomicLong pomPropertiesTime = new AtomicLong();
            visitors.add(e -> {
                pomPropertiesTime.addAndGet(-System.nanoTime());
                try {
                    if (e.getName().endsWith("/pom.properties")) {
                        try (InputStream is = jar.getInputStream(e)) {
                            MavenComponent mavenComponent = new MavenComponent();
                            mavenComponent.load(is);
                            mavenComponents.add(mavenComponent);
                        } catch (IOException ex) {
                            logger().warning("Failed to read %s: %s", e.getName(), ex.toString());
                        }
                    }
                } finally {
                    pomPropertiesTime.addAndGet(System.nanoTime());
                }
            });

            long start_ts = System.nanoTime();
            visitJarEntries(jar, visitors);
            stats.put("visitJarEntries", System.nanoTime() - start_ts);
            stats.put("pomProperties", pomPropertiesTime.get());
            stats.put("jarEntriesShaded", sendJarEntriesShadedHashesTime.get());
            stats.put("jarEntriesHashes", sendJarEntriesHashesTime.get());

            // All possible info about jar has been achieved, delete temp file if it is the copy of nested jar
            TempFilesFactory.deleteIfScheduled(file, () -> PerformanceMetrics.logJarCacheDeleted(file.length()));
        }
        if (jd == null && entriesMap.isEmpty()) return true;

        String centralDirectoryHashString = sendCentralDirectoryHash && jd == null ? null : encodeToStringOrNull(jd.getCentralDirectoryHash());
        String manifestHashString =         sendCentralDirectoryHash && jd == null ? null : encodeToStringOrNull(jd.getManifestHash());
        String centralDirectoryProvider =   sendCentralDirectoryHash && jd == null ? null : jd.getProvider();
        Long centralDirectoryLength =       sendCentralDirectoryHash && jd == null ? null : jd.getCentralDirectoryLength();

        long vmEventTimestamp = currentTimeMillis();

        VMEvent<Map<String, Object>> vmJarLoadedEvent = jarLoadEvent(
                metaUrl, initiatedBy, recursionDepth, jar.getName(), vmEventTimestamp,
                centralDirectoryHashString, manifestHashString,
                centralDirectoryProvider, centralDirectoryLength,
                entriesMap.pack().toExternalForm(), mavenComponents, stats);

        int eventSize = vmJarLoadedEvent.toJson().length();
        boolean isHugeEvent = eventSize > VM_JAR_LOADED_EVENT_INLINE_PAYLOAD_THRESHOLD;

        if (hardstop()) {
            return false;
        }

        if (!withDetails) {
             // Injects cookie that can be used by the server to request additional information about the jar.
             //
             // Added 'requestCookie' field contains restrictions on what can be requested. At the moment, it contains information
             // needed to identify the jar (later this can be extended to contain additional constrains). Data encoded in the
             // cookie cannot be falsified, because it is digested with a session-unique function known to the client only.
             // Server must use the cookie (as-is) in its requests along with any other data needed to process them.
             //
            VmJarInfoRequestCookie cookie = new VmJarInfoRequestCookie(jar.getName(), metaUrl);
            vmJarLoadedEvent.getEventPayload().put(ServerRequest.REQUEST_COOKIE_KEY, cookie.encode());
        }

        if (!isHugeEvent) {
            client.postVMEvent(vmJarLoadedEvent);
            ProcessedJarFiles.setAlreadyProcessed(metaUrl, jar, jd);
            return true;
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("eventId", vmJarLoadedEvent.getEventId());
        metadata.put("tags", Inventory.instanceTags());

        final ZipTools.JarShortDigest jdCopy = jd;

        return syncUpload("VM_JAR_LOADED event" + vmJarLoadedEvent.getEventId(), VMArtifact.Type.LARGE_VM_EVENT, metadata, (artifactId, output) -> {
            PrintStream printStream = new PrintStream(output);
            Utils.serializer.serialize(printStream, vmJarLoadedEvent);
            Map<String, Object> eventPayload = (Map<String, Object>) vmJarLoadedEvent.getEventPayload();
            eventPayload.remove("entries");
            eventPayload.put("STORED_VM_JAR_LOADED_EVENT", Client.artifactIdToString(artifactId));
            client.postVMEvent(vmJarLoadedEvent);
            ProcessedJarFiles.setAlreadyProcessed(metaUrl, jar, jdCopy);
        });
    }

    private static InputStream wrapStream(InputStream inputStream, Digest digest) {
        return new DigestInputStream(inputStream, digest.getMessageDigest()) {
            @Override
            public long skip(long n) throws IOException {
                if (n <= 0) {
                    return 0;
                }

                long remaining = n;
                int size = (int) Math.min(2048, remaining);
                byte[] skipBuffer = new byte[size];
                int r;
                while ((remaining > 0) && (r = read(skipBuffer, 0, (int) Math.min(size, remaining))) >= 0) {
                    remaining -= r;
                }
                return n - remaining;
            }

            @Override
            public boolean markSupported() {
                return false;
            }
        };
    }

    /**
     * Called by JVM (VMToolingClient) to notify about loaded jar.
     *
     * This method is called from the "CRSVMTooling" notification thread only.
     *
     * @param url - url of a jar as reported by the JVM
     * @param jar - jar file
     */
    public void notifyJarLoad(URL url, JarFile jar, File file) {
        InitiatedBy initiatedBy = ZipTools.isJDKNative(jar)
                ? InitiatedBy.JDK_NATIVE_LOADING
                : InitiatedBy.OTHER;

        notifyJarLoad(url, jar, file, null, initiatedBy, url.toString(), 0);
    }

    public void notifyJarLoad(URL url, JarFile jar) {
        notifyJarLoad(url, jar, null);
    }

    /**
     * Synchronously upload JAR_ENTRY
     *
     * @return true on success, false otherwise.
     */
    private boolean uploadJarEntry(URL url, JarFile jar, String entryName) {
        // check if uploading entry is allowed
        if (!jarEntryAccess.isAllowed(entryName)) {
            logger().error("it is not allowed to send jar=%s entry=%s url=%s list=%s", jar.getName(), entryName, url, sendJarEntriesList);
            return true;
        }
        JarEntry je = jar.getJarEntry(entryName);
        if (je == null) {
            logger().info("jar entry=%s is not present in jar=%s url=%s", entryName, jar.getName(), url);
            return true;
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("jar", jar.getName());
        metadata.put("url", url.toString());
        metadata.put("entry", entryName);
        metadata.put("tags", Inventory.instanceTags());

        return syncUpload(jar.getName() + "!" + je.getName(), VMArtifact.Type.JAR_ENTRY, metadata, (artifactId, output) -> {
            logger().info("uploadJarEntry: jar=%s entry=%s url=%s artifactId=%d", jar.getName(), entryName, url, artifactId);
            try (InputStream is = jar.getInputStream(je)) {
                Utils.transfer(is, output);
            }
        });
    }


    /**
     * Synchronously create and upload named artifact with provided metadata and content writer.
     * @param name name of the artifact (use for logging)
     * @param type artifact type
     * @param metadata associated metadata
     * @param writer content writer
     * @return true on success, false otherwise
     */
    private boolean syncUpload(String name, VMArtifact.Type type, Map<String, Object> metadata, ThrowingBiConsumer<Integer, OutputStream, IOException> writer) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        int artifactId = client.createArtifactId();
        client.postVMArtifact(type, artifactId, metadata, output -> {
            try {
                writer.accept(artifactId, output);
                success.set(true);
            } catch (IOException ex) {
                logger().error("syncUpload for %s failed: %s", name, ex);
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException ex) {
            logger().warning("syncUpload for %s interrupted", name);
        }

        return success.get();
    }

    @FunctionalInterface
    public interface ThrowingBiConsumer<T, U, E extends Throwable> {
        void accept(T t, U u) throws E;
    }

    // This method is called from the "CRS Listener Thread" only (this is a system thread).
    public void notifyClassSourceSeen(String source) {
      try {
        if (source == null) return;
        if (!jarLoadByClassLoad) return;
        if ("__JVM_DefineClass__".equals(source)) return;

        try {
          if (source.matches("^file:.*\\.jar$")) {
            source = "jar:" + source + "!/";
          }
          if (!source.endsWith(".jar!/")) return;
          URL url = new URL(source);
          String metaUrl = url.toString();

          if (ProcessedJarFiles.isAlreadyProcessedURL(metaUrl)) {
            logger().trace("Skip already processed: %s", metaUrl);
            return;
          }

          URLConnection uc = url.openConnection();
          if (uc instanceof JarURLConnection) {
            JarURLConnection jarConnection = (JarURLConnection)uc;

            JarFile jf = jarConnection.getJarFile();

            notifyJarLoad(url, jf, null, null, InitiatedBy.CLASS_LOADING, metaUrl, 0);
          } else {
            logger().debug("Cannot open JarURLConnection from given class source (%s) (URLConnection.class=%s)", source, uc.getClass().getName());
          }
        } catch (Exception e) {
          logger().debug("Class source (%s) is malformed or is not applicable to extract jar file", source, e);
        }
      } catch (Exception e) {
        // in case if there are some problems with logger we should not pass the exception
        System.out.println("!!! unexpected exception: " + e);
        e.printStackTrace();
      }
    }

    private static boolean getBooleanProperty(String prop, boolean defaultVal) {
      return Boolean.parseBoolean(System.getProperty(prop, String.valueOf(defaultVal)));
    }

    private static int getIntProperty(String prop, int defaultVal) {
        return Integer.parseInt(System.getProperty(prop, String.valueOf(defaultVal)));
    }

    private static final class MavenComponent extends Properties {
    }

    // TODO: This is, again a temporary solution - currently we send (and calculate) unconditionally all jar. But server
    // just ignores those 'known' ones. This causes significant waste of time/traffic. Once we have bi-directional,
    // this problem will not be that vexed problem, and we may want to re-consider this filtering.
    private static Set<String> initKnownVmJars() {
        String javaHome = System.getProperty("java.home");
        String specVersion = System.getProperty("java.specification.version");
        return KnownAzulRuntimeContainers.get(javaHome, specVersion);
    }

    private static class NotificationTask {

        private final AtomicBoolean completedSuccessfully = new AtomicBoolean();
        private final String description;

        private NotificationTask(String description) {
            this.description = description;
        }

        private void setCompletionStatus(boolean success) {
            completedSuccessfully.set(success);
        }

        private boolean isCompletedSuccessfully() {
            return completedSuccessfully.get();
        }

        @Override
        public String toString() {
            return description;
        }
    }
}
