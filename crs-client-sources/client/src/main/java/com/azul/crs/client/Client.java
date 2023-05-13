/*
 * Copyright 2019-2020 Azul Systems,
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
import com.azul.crs.client.service.EventService;
import com.azul.crs.client.service.UploadService;
import static com.azul.crs.client.Utils.currentTimeMillis;
import com.azul.crs.client.models.VMArtifact;
import com.azul.crs.client.models.VMArtifactChunk;
import com.azul.crs.client.models.VMEvent;
import static com.azul.crs.client.models.VMEvent.Type.*;
import com.azul.crs.client.models.VMInstance;
import com.azul.crs.client.service.ServerRequestsService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;


/** CRS client provides functional API to CRS agents and hides CRS backend and infrastructure details */
public final class Client {

    public interface ClientListener {
        void authenticated();
        void syncFailed(Result<String[]> reason);
    }

    /** Recognized Client properties */
    public enum ClientProp {
        API_URL                    ("api.url", true),
        API_MAILBOX                ("api.mailbox", true),
        KS                         ("keystore", false),
        ACCESS_KEY                 ("accessKey", false),
        HEAP_BUFFER_SIZE           ("heapBufferSize", false),
        FILE_SYSTEM_BUFFER_SIZE    ("fileSystemBufferSize", false),
        FILE_SYSTEM_BUFFER_LOCATION("fileSystemBufferLocation", false),
        NUM_CONCURRENT_CONNECTIONS ("numConcurrentConnections", false), // maximum number of simultaneous connection to the cloud
        BACKUP_JFR_CHUNKS          ("backupJfrChunks", false), // bool, backup JFR data which is pending send to cloud, otherwise the data is marked as "used" and counts toward JFR own file space quotas
        VM_SHUTDOWN_DELAY          ("delayShutdownInternal", true), // how much time to wait for data to be sent to the cloud during VM shutdown
        INV_ENV_ALLOW              ("inventory.environment.allow", true),
        INV_ENV_DENY               ("inventory.environment.deny", true),
        INV_SYS_PROPS_ALLOW        ("inventory.system.properties.allow", true),
        INV_SYS_PROPS_DENY         ("inventory.system.properties.deny", true);

        private final Object value;
        private final boolean mandatory;
        ClientProp(String value, boolean mandatory) { this.value = value; this.mandatory = mandatory; }
        Object value() { return value; }
        Object defaultValue() { return getDefaultValue(value); }
        boolean isMandatory() { return mandatory; }
    }

    private static final Properties defaults = new Properties();
    private static Object getDefaultValue(Object value) {
        if (defaults.isEmpty()) {
            try (InputStream is = Client.class.getResourceAsStream("default.properties")) {
                defaults.load(is);
            } catch (IOException ex) {
                defaults.put("", ""); // to avoid retries
            }
        }
        return defaults.get(value);
    }

    public static interface DataWriter {

        void writeData(OutputStream stream) throws IOException;
        default void handleException(IOException ex) {};
    }

    private final ConnectionManager connectionManager;
    private final UploadService uploadService;
    private final EventService eventService;
    private final ServerRequestsService serverRequestsService;
    private final AtomicInteger nextArtifactId = new AtomicInteger();
    private final AtomicLong nextArtifactChunkId = new AtomicLong();
    private static volatile Deadline vmShutdownDeadline;
    private final PropertiesFilter envFilter;
    private final PropertiesFilter sysPropsFilter;

    private String vmId;

    /** Validates whether all client properties are provided */
    private void validateProps(Map<ClientProp, Object> props) {
        for (ClientProp p : ClientProp.values()) {
            if (props.get(p) == null && p.defaultValue() != null) {
                props.put(p, p.defaultValue());
            }
            if (p.isMandatory() && props.get(p) == null)
                throw new IllegalArgumentException(
                    "Invalid CRS properties file: missing value for " + p.value());
        }
    }

    /** Constructs CRS client with cloud properties provided in a given file */
    public Client(Map<ClientProp, Object> props, final ClientListener listener) {
        validateProps(props);

        eventService = EventService.getInstance(this);
        serverRequestsService = new ServerRequestsService();
        connectionManager = new ConnectionManager(props, this, new ConnectionManager.ConnectionListener() {

            @Override
            public void authenticated() {
                vmId = connectionManager.getVmId();
                listener.authenticated();
            }

            @Override
            public void syncFailed(Result<String[]> reason) {
                // TODO remember and report the problem to the cloud, avoid infinite recursion
                listener.syncFailed(reason);
            }
        });

        uploadService = new UploadService(this);

        sysPropsFilter = new PropertiesFilter(
                (String) props.get(ClientProp.INV_SYS_PROPS_ALLOW),
                (String) props.get(ClientProp.INV_SYS_PROPS_DENY),
                Arrays.asList(
                        "java.home",
                        "java.specification.version",
                        "java.vm.vendor",
                        "java.vm.version",
                        "java.vendor.version",
                        "jdk.vendor.version",
                        "os.arch",
                        "os.name")
        );

        envFilter = new PropertiesFilter(
                (String) props.get(ClientProp.INV_ENV_ALLOW),
                (String) props.get(ClientProp.INV_ENV_DENY),
                Collections.emptyList()
        );
    }

    public static class PropertiesFilter implements Predicate<String> {

        private final List<SimpleRegEx> whitelist;
        private final List<SimpleRegEx> allow;
        private final List<SimpleRegEx> deny;

        private PropertiesFilter(String allowList, String denyList, List<String> whitelist) {
            this.whitelist = whitelist.stream().map(SimpleRegEx::new).collect(Collectors.toList());
            this.allow = Arrays.stream(allowList.split("\\|")).map(SimpleRegEx::new).collect(Collectors.toList());
            this.deny = Arrays.stream(denyList.split("\\|")).map(SimpleRegEx::new).collect(Collectors.toList());
        }

        @Override
        public boolean test(String key) {
            return whitelist.stream().anyMatch(p -> p.test(key))
                    || (allow.stream().anyMatch(p -> p.test(key)) && !deny.stream().anyMatch(p -> p.test(key)));
        }
    }

    /** Posts VM start event to register in CRS new VM instance with given inventory */
    public void postVMStart(Map<String, Object> inventory, long startTime) throws IOException {
        postVMEvent(new VMEvent<>()
            .eventType(VMEvent.Type.VM_CREATE)
            .eventPayload(new VMInstance()
                .agentVersion(getClientVersion())
                .agentRevision(getClientRevision())
                .owner(connectionManager.getMailbox())
                .inventory(inventory)
                .startTime(startTime)
                )
        );
    }

    public void patchInventory(Map<String, Object> inventory) {
        postVMEvent(new VMEvent<>()
            .eventType(VM_PATCH)
            .eventPayload(new VMInstance()
                .inventory(inventory))
        );
    }

    /** Posts VM event associated with VM instance known to CRS */
    public void postVMEvent(VMEvent event) {
        eventService.add(event
            .randomEventId()
        );
    }

    /** Posts VM shutdown event for VM instance known to CRS */
    public void postVMShutdown(Collection<VMEvent> trailingEvents) {
        eventService.addAll(trailingEvents);
        eventService.add(new VMEvent()
            .randomEventId()
            .eventType(VM_SHUTDOWN)
            .eventTime(currentTimeMillis()));
    }

    public int createArtifactId() {
        return nextArtifactId.incrementAndGet();
    }

    public long createArtifactChunkId() {
        return nextArtifactChunkId.incrementAndGet();
    }

    public void postVMArtifactCreate(VMArtifact.Type type, int artifactId, Map<String, Object> attributes) {
        postVMEvent(new VMEvent<>()
            .eventType(VM_ARTIFACT_CREATE)
            .eventPayload(new VMArtifact()
                .artifactType(type)
                .artifactId(artifactIdToString(artifactId))
                .metadata(attributes)));
    }

    public void postVMArtifactPatch(VMArtifact.Type type, int artifactId, Map<String, Object> attributes) {
        postVMEvent(new VMEvent<>()
            .eventType(VM_ARTIFACT_PATCH)
            .eventPayload(new VMArtifact()
                .artifactType(type)
                .artifactId(artifactIdToString(artifactId))
                .metadata(attributes)));
    }

    public void postVMArtifactData(Integer artifactId, String data) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("artifactId", artifactIdToString(artifactId));
        payload.put("data", data);
        postVMEvent(new VMEvent<>()
                .eventType(VM_ARTIFACT_DATA)
                .eventPayload(payload));
        PerformanceMetrics.logArtifactBytes(data.length());
    }

    public void postVMArtifact(VMArtifact.Type type, int artifactId, Map<String, Object> attributes, DataWriter dataWriter) {
        postVMArtifactCreate(type, artifactId, attributes);
        postVMArtifactChunk(Collections.singleton(artifactId), Collections.emptyMap(), dataWriter);
    }

    public void postVMArtifactChunk(Set<Integer> artifactIds, Map<String, Object> attributes, DataWriter dataWriter) {
        Set<String> ids = new HashSet<>();
        artifactIds.forEach(id -> ids.add(artifactIdToString(id)));
        uploadService.post(new VMArtifactChunk().artifactIds(ids).metadata(attributes), dataWriter);
    }

    public void finishChunkPost() {
        // make sure that we post the data asap
        uploadService.sync();
    }

    public static String artifactIdToString(int artifactId) {
        return Integer.toString(artifactId, 36);
    }

    public void startup() throws IOException {
        connectionManager.start();
        eventService.start();
        uploadService.start();
        serverRequestsService.start();
    }

    public void connectionEstablished() {
        eventService.connectionEstablished();
        uploadService.connectionEstablished();
    }

    public static boolean isVMShutdownInitiated() { return vmShutdownDeadline != null; }

    public static void setVMShutdownInitiated(Deadline deadline) { vmShutdownDeadline = deadline; }

    public static Deadline getVMShutdownDeadline() { return vmShutdownDeadline; }

    /**
     * Proper shutdown of Client operation.
     * @param deadline the time shutdown shall complete
     */
    public void shutdown(Deadline deadline) {
        eventService.stop(deadline);
        uploadService.stop(deadline);
        connectionManager.stop(deadline);
        serverRequestsService.stop(deadline);
    }

    /**
     * Forcibly cancel pending operations. To be used in case of abnormal termination of CRS agent.
     */
    public void cancel() {
        eventService.cancel();
        uploadService.cancel();
        serverRequestsService.cancel();
    }

    public String getVmId() { return vmId; }

    public String getClientVersion() throws IOException { return new Version().clientVersion(); }
    public String getClientRevision() throws IOException { return new Version().clientRevision(); }

    public String getMailbox() { return connectionManager.getMailbox(); }

    public String getRestAPI() { return connectionManager.getRestAPI(); }

    public ConnectionManager getConnectionManager() { return connectionManager; }

    public PropertiesFilter getEnvFilter() {
        return envFilter;
    }

    public PropertiesFilter getSysPropsFilter() {
        return sysPropsFilter;
    }
}
