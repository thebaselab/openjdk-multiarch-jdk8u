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

import com.azul.crs.json.CompileJsonSerializer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code VMArtifact} is used to represent information about a file associated
 * with a specific {@code VMInstance} (e.g. GC log or JFR recording). The
 * content of the file is not a part of the model. It is hosted by a suitable
 * storage and is simply referenced by the {@code VMArtifact}.
 */
@CompileJsonSerializer
public class VMArtifact extends Payload {

    /**
     * Well known types of artifacts.
     * <p>
     * Used to correctly define MIME types when working with files/URLs.
     * </p>
     */
    public enum Type {
        GC_LOG,  // GC log
        VM_LOG,  // VM log
        CRS_LOG, // CRS Agent log
        JFR,     // Java Flight Recording
        JAR,     // Jar file sent on jar loading envent
        JAR_ENTRY, // Jar entry sent by request
        LARGE_VM_EVENT,
        OTHER    // Unrecognized; probably a screen shot or other stuff that describes an issue, etc
    }

    private String artifactId;            // VM artifact ID
    private Type artifactType;            // VM artifact type
    private Long createTime;              // Epoch time of the artifact creation
    private String filename;              // Artifact filename
    private Map<String, Object> metadata; // VM artifact metadata without schema
    private String vmId;                  // VM instance associated with this artifact

    transient private Long size = -1L;    // Calculated total size of artifact chunks
    transient private String snapshot;    // Transient URL presigned for the artifact snapshot download
    transient private String uploadURL;   // Transient URL presigned for the artifact upload

    public String getArtifactId() { return artifactId; }
    public Type getArtifactType() { return artifactType; }
    public Map<String, Object> getMetadata() { return metadata; }
    public String getVmId() { return vmId; }
    public String getFilename() { return filename; }
    public Long getCreateTime() { return createTime; }
    public String getSnapshot() { return snapshot; }
    public String getUploadURL() { return uploadURL; }
    public Long getSize() { return size; }

    public void setArtifactId(String artifactId) { this.artifactId = artifactId; }
    public void setArtifactType(Type artifactType) { this.artifactType = artifactType; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public void setVmId(String vmId) { this.vmId = vmId; }
    public void setFilename(String name) { this.filename = name; }
    public void setCreateTime(Long createTime) { this.createTime = createTime; }
    public void setSnapshot(String snapshotStorageKey) { this.snapshot = snapshotStorageKey; }
    public void setUploadURL(String uploadURL) { this.uploadURL = uploadURL; }
    public void setSize(Long size) { this.size = size; }

    public VMArtifact artifactId(String artifactId) { setArtifactId(artifactId); return this; }
    public VMArtifact artifactType(Type artifactType) { setArtifactType(artifactType); return this; }
    public VMArtifact artifactType(String artifactType) { if (artifactType != null) setArtifactType(Type.valueOf(artifactType)); return this; }
    public VMArtifact metadata(Map<String, Object> metadata) { setMetadata(metadata); return this; }
    public VMArtifact vmId(String vmId) { setVmId(vmId); return this; }
    public VMArtifact filename(String name) { setFilename(name); return this; }
    public VMArtifact createTime(Long createTime) { setCreateTime(createTime); return this; }
    public VMArtifact snapshot(String snapshotDownloadURL) { setSnapshot(snapshotDownloadURL); return this; }
    public VMArtifact uploadURL(String uploadURL) { setUploadURL(uploadURL); return this; }
    public VMArtifact size(Long size) { setSize(size); return this; }

    /** Sets key-value pair to VM artifact metadata */
    public VMArtifact metadata(String key, Object value) {
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        metadata.put(key, value);
        return this;
    }

    @Override
    public boolean equals(Object o) {
        // Transient and computed fields are not compared
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VMArtifact that = (VMArtifact) o;
        return artifactType == that.artifactType
                && Objects.equals(artifactId, that.artifactId)
                && Objects.equals(createTime, that.createTime)
                && Objects.equals(filename, that.filename)
                && Objects.equals(metadata, that.metadata)
                && Objects.equals(vmId, that.vmId);
    }

    @Override
    public int hashCode() {
        // Transient and computed fields are not hashed
        return Objects.hash(artifactId, artifactType, metadata, vmId, filename, createTime);
    }

    /** Makes a copy of this model */
    public VMArtifact copy() {
        // Transient and computed fields are not copied
        return new VMArtifact()
            .artifactType(artifactType)
            .artifactId(artifactId)
            .createTime(createTime)
            .filename(filename)
            .metadata(metadata)
            .vmId(vmId);
    }
}
