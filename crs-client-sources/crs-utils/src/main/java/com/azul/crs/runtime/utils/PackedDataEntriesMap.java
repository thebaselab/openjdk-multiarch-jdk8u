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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import com.azul.crs.runtime.utils.PackedDataEntriesMap.Entry;

public final class PackedDataEntriesMap<T extends Enum> implements Iterable<Entry<T>> {

    /* package */ static final String MAGIC = "PEM01"; // Packed Entry Map v0.1

    /**
     * A structure that holds all information from the DataEntriesMap
     *
     * @param <T> enum of data kinds.
     *
     * @see DataEntriesMap#pack()
     */
    private final List<T> dataFieldKinds;
    private final List<String> packedList;

    @SuppressWarnings("unchecked")
    private PackedDataEntriesMap(List<String> packedList, List<T> dataFieldKinds) {
        this.packedList = packedList;
        this.dataFieldKinds = dataFieldKinds;
    }

    /**
     * Constructs {@code PackedDataEntriesMap} from the provided Strings list.
     *
     * @param packedList list retrieved from the {@link #toExternalForm() }
     * @param kindsEnum enum of data kinds. It does not need to be exactly the same class that was used to produce the
     * list.
     *
     * @see #toExternalForm()
     */
    public static <T extends Enum> PackedDataEntriesMap<T> fromExternalForm(List<String> packedList, Class<T> kindsEnum) {
        if (packedList.isEmpty()) {
            return new PackedDataEntriesMap<>(Collections.emptyList(), Collections.emptyList());
        }

        String descr = packedList.get(0);
        if (!descr.startsWith(MAGIC)) {
            throw new IllegalArgumentException();
        }

        descr = descr.substring(MAGIC.length());

        // TODO: remove later
        // There was a typo in the initial change and old agents can send fileds named incorrectly
        descr = descr.replace("ENRTY", "ENTRY");

        String[] kinds = descr.split(":");
        List<T> dataFieldKinds = new ArrayList<>();
        for (String kind : kinds) {
            try {
                dataFieldKinds.add((T) Enum.<T>valueOf(kindsEnum, kind));
            } catch (IllegalArgumentException ex) {
                // mark the position with an unknown type
                dataFieldKinds.add(null);
            }
        }
        return new PackedDataEntriesMap<>(packedList, dataFieldKinds);
    }

    /**
     * Returns all the data in a form of Strings list. This list can be used to re-construct
     * {@code PackedDataEntriesMap}.
     *
     * @see #fromExternalForm(List, Class)
     */
    public List<String> toExternalForm() {
        return Collections.unmodifiableList(packedList);
    }

    /**
     * Returns array of data kinds that can be extracted from the list.
     */
    public List<T> dataFieldKinds() {
        return dataFieldKinds;
    }

    /**
     * Search for the entry {@code path} in the structure. {@code path} is a full path (includes dir and filename). A
     * positive result is the index in the {@code packedList} list for the searched entry; a negative result means that
     * entry with the provided {@code path} is not present.
     */
    private int indexOf(String path) {
        int lastIndex, currentIndex = 1; // start with 1, as the first line is a header
        int lastSlash = path.lastIndexOf('/') + 1;
        String entryDir = path.substring(0, lastSlash);
        String entryName = path.substring(lastSlash) + "|"; // Add separator so that the path comparision becomes s.startsWith(entryName)

        String str = packedList.get(currentIndex++);
        if (entryDir.isEmpty()) {
            lastIndex = currentIndex + Integer.parseInt(str.substring(0, str.indexOf(':')));
        } else {
            currentIndex += Integer.parseInt(str.substring(0, str.indexOf(':'))); // skip root entries
            str = packedList.get(currentIndex++);
            outer:
            while (true) {
                while (!entryDir.startsWith(str)) {
                    str = packedList.get(currentIndex++);
                    currentIndex += Integer.parseInt(str.substring(str.indexOf(':') + 1));
                    if (currentIndex == packedList.size()) {
                        return -1;
                    }
                    str = packedList.get(currentIndex++);
                }

                while (entryDir.startsWith(str)) {
                    if (str.equals(entryDir)) {
                        break outer;
                    }
                    str = packedList.get(currentIndex++);
                    currentIndex += Integer.parseInt(str.substring(0, str.indexOf(':')));
                    if (currentIndex == packedList.size()) {
                        return -1;
                    }
                    str = packedList.get(currentIndex++);
                }
            }

            str = packedList.get(currentIndex++);
            lastIndex = currentIndex + Integer.parseInt(str.substring(0, str.indexOf(':')));
        }
        int idx = Collections.binarySearch(
                packedList.subList(currentIndex, lastIndex),
                entryName,
                (s1, s2) -> s1.startsWith(s2) ? 0 : s1.compareTo(s2));
        return idx < 0 ? idx : idx + currentIndex;
    }

    /**
     * Test whether an entry with the specified path exists.
     */
    public boolean contains(String path) {
        return indexOf(path) > 0;
    }

    /**
     * Get data associated with the {@code path} or {@code null} if no entry found.
     *
     * The returned String contains all the data associated with the entry, separated by ':'.
     *
     * @see #dataFieldKinds()
     */
    public String getData(String path) {
        int index = indexOf(path);
        String entry = index > 0 ? packedList.get(index) : null;
        return entry == null ? null : entry.substring(entry.indexOf('|') + 1);
    }

    /**
     * Get data of the specified {@code kind} associated with the {@code path} or {@code null} if no entry found.
     */
    public String getData(String path, T kind) {
        return extractField(getData(path), kind, dataFieldKinds);
    }

    private static <T> String extractField(String data, T kind, List<T> dataFieldKinds) {
        if (data == null || kind == null) {
            return data;
        }
        int idx = dataFieldKinds.indexOf(kind);
        if (idx < 0) {
            return null;
        }
        int p1 = -1;
        for (int i = 0; i < idx; i++) {
            if ((p1 = data.indexOf(':', p1 + 1)) < 0) {
                return null;
            }
        }
        int p2 = data.indexOf(':', ++p1);
        if (p1 == p2) {
            return null;
        }

        return p2 < 0 ? data.substring(p1) : data.substring(p1, p2);
    }

    /**
     * Returns iterator that can be used to retrieve pairs {@code [path, data]}.
     */
    @Override
    public Iterator<Entry<T>> iterator() {
        return iterator(null);
    }

    /**
     * Returns iterator that can be used to retrieve pairs {@code [path, data-of-specific-kind]}.
     */
    public Iterator<Entry<T>> iterator(T kind) {
        return new Iterator<Entry<T>>() {
            private final ListIterator<String> it = packedList.listIterator();
            private String prefix = "";

            // Iteration over the list is trivial and doesn't require indexes analysis -
            // just concatenate files to the last observed directory.

            {
                if (hasNext()) {
                    it.next(); // Skip header
                }
                if (hasNext()) {
                    it.next(); // Skip first line (indexes for the root dir)
                }
            }

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public Entry<T> next() {
                try {
                    String s = it.next();
                    while (s.endsWith("/")) {
                        prefix = s;
                        it.next();
                        s = it.next();
                    }
                    int idx = s.indexOf('|');
                    String path = prefix + s.substring(0, idx);
                    String value = extractField(s.substring(idx + 1), kind, dataFieldKinds);
                    return kind == null
                            ? new Entry<>(path, value, dataFieldKinds)
                            : new SingleFieldEntry<>(path, value, kind);
                } catch (Exception ex) {
                    throw new IllegalStateException("Format error", ex);
                }
            }
        };
    }

    private static class SingleFieldEntry<T> extends Entry<T> {

        private final T kind;

        private SingleFieldEntry(String path, String value, T kind) {
            super(path, value, null);
            this.kind = kind;
        }

        @Override
        public String getValue(T kind) {
            return this.kind == kind ? getValue() : null;
        }
    }

    public static class Entry<T> extends AbstractMap.SimpleEntry<String, String> {

        private final List<T> dataFieldKinds;

        private Entry(String path, String value, List<T> dataFieldKinds) {
            super(path, value);
            this.dataFieldKinds = dataFieldKinds;
        }

        public String getValue(T kind) {
            return extractField(getValue(), kind, dataFieldKinds);
        }
    }
}
