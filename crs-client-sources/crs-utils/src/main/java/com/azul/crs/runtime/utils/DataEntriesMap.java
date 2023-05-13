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
package com.azul.crs.runtime.utils;

import java.util.*;
import java.util.stream.Collectors;

public final class DataEntriesMap<T extends Enum> {

    private final DirNode<T> root = new DirNode<>("");
    private final Class<T> dataKindsEnum;
    private final Enum[] usedKinds;
    private DirNode<T> cache = root;

    /**
     * Constructs a new instance of the {@code DataEntriesMap} that can hold path to data of provided kinds
     * associations.
     */
    public DataEntriesMap(Class<T> dataKindsEnum) {
        this.dataKindsEnum = dataKindsEnum;
        usedKinds = new Enum[dataKindsEnum.getEnumConstants().length];
    }

    /**
     * Associates passed {@code data} of the named {@code kind} with the provided {@code path}.
     *
     * Call to the {@code put} is an equivalent of calling {@code getEntry(path).put(kind, data)}.
     *
     * @see #getEntry(String)
     */
    public void put(String path, T kind, String data) throws IllegalArgumentException {
        getEntry(path).put(kind, data);
    }

    /**
     * Gets or creates an entry for the given {@code path}.
     *
     * @param path - entry path (must not end with '/')
     * @throws IllegalArgumentException if path is {@code null} or ends with '/'
     * @see #put(String, Enum, String)
     */
    public DataEntry getEntry(String path) throws IllegalArgumentException {
        if (path == null || path.endsWith("/")) {
            throw new IllegalArgumentException();
        }
        DirNode<T> node;
        int idx = path.lastIndexOf('/');
        String name = path.substring(idx + 1, path.length());
        String dir = path.substring(0, idx + 1);
        if (cache.path.equals(dir)) {
            node = cache;
        } else {
            node = findOrCreateDirNode(root, 0, dir);
            cache = node;
        }

        return new DataEntry(node.files.computeIfAbsent(name,
                n -> new String[dataKindsEnum.getEnumConstants().length])
        );
    }

    /**
     * Create and return a packed structure of the constructed {@code DataEntriesMap}.
     */
    public PackedDataEntriesMap<T> pack() {
        List<String> output = new ArrayList<>();
        output.add(PackedDataEntriesMap.MAGIC + Arrays.stream(usedKinds)
                .filter(Objects::nonNull)
                .map(Enum::name)
                .collect(Collectors.joining(":")));
        pack(root, output);
        return PackedDataEntriesMap.fromExternalForm(output, dataKindsEnum);
    }

    private <T extends Enum> void pack(DirNode<T> node, List<String> result) {
        if (!node.path.isEmpty()) {
            result.add(node.path);
        }
        int idx = result.size();
        result.add((node.files.size()) + ":");
        node.files.forEach((String name, String[] data) -> result.add(name + "|" + encode(data)));

        node.dirs.forEach(dir -> pack(dir, result));
        result.set(idx, result.get(idx) + (result.size() - idx - 1));
    }

    private DirNode<T> findOrCreateDirNode(DirNode<T> node, int idx, String dir) {
        if (idx == dir.length()) {
            return node;
        }
        idx = dir.indexOf('/', idx);
        String dirname = dir.substring(0, idx + 1);
        for (DirNode<T> d : node.dirs) {
            if (d.path.equals(dirname)) {
                return findOrCreateDirNode(d, idx + 1, dir);
            }
        }
        DirNode<T> newNode = new DirNode<>(dirname);
        node.dirs.add(newNode);
        return findOrCreateDirNode(newNode, idx + 1, dir);
    }

    private String encode(String[] data) {
        StringBuilder sb = new StringBuilder();
        for (Enum usedKind : usedKinds) {
            if (usedKind != null) {
                String val = data[usedKind.ordinal()];
                if (val != null) {
                    sb.append(val);
                }
                sb.append(':');
            }
        }
        int idx = sb.length();
        while (sb.charAt(--idx) == ':') {
            sb.setLength(idx);
        }
        return sb.toString();
    }

    /**
     * Returns true if this {@code DataEntriesMap} is empty.
     */
    public boolean isEmpty() {
        return root.dirs.isEmpty() && root.files.isEmpty();
    }

    private static class DirNode<T extends Enum> implements Comparable<DirNode> {

        private final String path;
        private final TreeMap<String, String[]> files = new TreeMap<>();
        private final TreeSet<DirNode<T>> dirs = new TreeSet<>();

        private DirNode(String path) {
            this.path = path;
        }

        @Override
        public int compareTo(DirNode o) {
            return path.compareTo(o.path);
        }
    }

    public final class DataEntry<T extends Enum> {

        private final String[] data;

        private DataEntry(String[] data) {
            this.data = data;
        }

        /** Checks whether entry has not data */
        public boolean isEmpty() {
            if (data != null) {
                for (String d : data) {
                    if (d != null) return false;
                }
            }
            return true;
        }

        /**
         * Put {@code data} of {@code kind} to this entry.
         */
        public void put(T kind, String data) {
            int kindIdx = kind.ordinal();
            if (usedKinds[kindIdx] == null) {
                usedKinds[kindIdx] = kind;
            }
            this.data[kindIdx] = data;
        }
    }
}
