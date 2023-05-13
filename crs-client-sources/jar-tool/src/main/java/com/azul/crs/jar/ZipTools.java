/*
 * Copyright 2019-2022 Azul Systems,
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
package com.azul.crs.jar;

import com.azul.crs.util.logging.LogChannel;
import com.azul.crs.util.logging.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import sun.net.www.protocol.jar.URLJarFile;

import static com.azul.crs.client.Utils.encodeToStringOrNull;
import static com.azul.crs.util.logging.Logger.Level.TRACE;
import java.io.IOException;
import java.util.zip.ZipException;

@LogChannel(value = "service.jarload.ziptools", lowestUpstreamLevel = TRACE)
public final class ZipTools {

    public static final int DEFAULT_BUFFER_SIZE = 8 * 1024;
    private static final boolean DEBUG = Boolean.getBoolean("com.azul.crs.jarload.debug");
    private static final Logger logger = Logger.getLogger(ZipTools.class);

    private final boolean forceToUseGenericProvider;
    private final boolean allowAdvancedJarLoadDetection;
    private final CentralDirectoryProviderFactory jdkCentralDirectoryFactory;
    private final CentralDirectoryProviderFactory genericCentralDirectoryFactory;
    private final MultiMap<String, CentralDirectoryProviderFactory> cdTools;

    public ZipTools(boolean forceToUseGenericProvider,
            boolean allowAdvancedJarLoadDetection,
            String genericDirectoryProvider) {
        this.forceToUseGenericProvider = forceToUseGenericProvider;
        this.allowAdvancedJarLoadDetection = allowAdvancedJarLoadDetection;
        this.jdkCentralDirectoryFactory = getJdkCentralDirectoryFactory();
        this.genericCentralDirectoryFactory = getGenericCentralDirectoryFactory(genericDirectoryProvider);

        cdTools = new MultiMap<>();
        cdTools.put(JarFile.class.getName(), jdkCentralDirectoryFactory);
        cdTools.put(URLJarFile.class.getName(), jdkCentralDirectoryFactory);
        cdTools.put("org.springframework.boot.loader.jar.JarFile", new Spring1xCentralDirectoryFactory());
        cdTools.put("org.springframework.boot.loader.jar.JarFile", new Spring2xCentralDirectoryFactory());
        cdTools.put("org.springframework.boot.loader.jar.JarFileWrapper", new Spring26xCentralDirectoryFactory());
    }

    public static ZipTools createDefault() {
        return new ZipTools(
                Boolean.getBoolean("com.azul.crs.jarload.forceToUseGenericProvider"),
                Boolean.getBoolean("com.azul.crs.jarload.allowAdvancedJarLoadDetection"),
                System.getProperty("com.azul.crs.jarload.genericCentralDirectoryProvider")
        );
    }

    private CentralDirectoryProviderFactory getJdkCentralDirectoryFactory() {
        return System.getProperty("java.version").startsWith("1.8.")
                ? new JDK8CentralDirectoryFactory()
                : new JDKCentralDirectoryFactory();
    }

    private CentralDirectoryProviderFactory getGenericCentralDirectoryFactory(String directoryProvider) {
        if (directoryProvider != null) {
            switch (directoryProvider) {
                case "generic":
                    return new GenericCentralDirectoryFactory();
                case "jdk":
                    return getJdkCentralDirectoryFactory();
                case "spring.1.x":
                    return new Spring1xCentralDirectoryFactory();
                case "spring.2.x":
                    return new Spring2xCentralDirectoryFactory();
            }
        }
        return new GenericCentralDirectoryFactory();
    }

    /**
     * Tries to detect a factory to use with this specific class.
     *
     * @param klass class to examine
     * @return CentralDirectoryProviderFactory to use. If match cannot be found, {@code genericCentralDirectoryFactory}
     * is returned.
     */
    private CentralDirectoryProviderFactory findCentralDirectoryProviderFactoryForClass(Class klass) {
        CentralDirectoryProviderFactory provider;
        Class c = klass;
        // TODO: do we really want to rely on super class type?
        //       spring.JarFile extends JarFile, but CD cannot be
        //       extracted by the same method as for JarFile
        while (!c.equals(Object.class)) {
            if ((provider = cdTools.get(c.getName())) != null) {
                return provider;
            }
            c = c.getSuperclass();
        }

        return genericCentralDirectoryFactory;
    }

    private class MultiMap<K, V> {

        Map<K, List<V>> map = new HashMap<>();

        synchronized void put(K k, V v) {
            List<V> l = map.computeIfAbsent(k, kk -> new ArrayList<>());
            if (!l.contains(v)) {
                l.add(v);
            }
        }

        synchronized V get(K k) {
            List<V> list = map.get(k);
            return list == null || list.isEmpty() ? null : list.get(0);
        }

        synchronized void remove(K k, V v) {
            List<V> l = map.get(k);
            if (l != null) {
                l.remove(v);
            }
        }
    }

    public static interface DataConsumer {

        void consume(byte[] data, int off, int len) throws IOException;

        default void consume(byte[] data) throws IOException {
            consume(data, 0, data.length);
        }

    }

    public static interface DataProvider {

        void deliver(DataConsumer consumer) throws Exception;
    }

    // central directory exporter interface
    public static interface CentralDirectoryProviderFactory {

        public DataProvider getCentralDirectoryProvider(URL url, ZipFile f);
    }

    // parsing support for JDK default JarFile classes:
    //   sun.net.www.protocol.jar.URLJarFile;
    //   java.util.jar.JarFile;
    public static class JDKCentralDirectoryFactory implements CentralDirectoryProviderFactory {

        private boolean initialized = false;

        private Field res; // CleanableResource
        private Class cleanableResource;
        private Field zsrc; // Source
        private Class source;
        private Field cen; // byte[] // CEN & ENDHDR

        private synchronized void lazyInit() {
            if (initialized) {
                return;
            }

            try {
                res = ZipFile.class.getDeclaredField("res");
                res.setAccessible(true);
                cleanableResource = res.getType();
                zsrc = cleanableResource.getDeclaredField("zsrc");
                zsrc.setAccessible(true);
                source = zsrc.getType();
                cen = source.getDeclaredField("cen");
                cen.setAccessible(true);
            } catch (NoSuchFieldException | SecurityException ex) {
                logger.warning("%s initialization failed: %s", getClass().getSimpleName(), ex);
                res = null;
                cleanableResource = null;
                zsrc = null;
                source = null;
                cen = null;
            } finally {
                initialized = true;
            }
        }

        @Override
        public DataProvider getCentralDirectoryProvider(URL url, ZipFile f) {
            lazyInit();

            if (res == null) {
                return null;
            }

            return consumer -> {
                Object cleanableResource1 = res.get(f);
                Object source1 = zsrc.get(cleanableResource1);
                byte[] cd = (byte[]) cen.get(source1);
                consumer.consume(cd);
            };
        }
    }

    public static class JDK8CentralDirectoryFactory implements CentralDirectoryProviderFactory {

        private boolean initialized = false;

        private Method getCentralDirectory;

        private synchronized void lazyInit() {
            if (initialized) {
                return;
            }
            try {
                getCentralDirectory = ZipFile.class.getDeclaredMethod("getCentralDirectory");
                getCentralDirectory.setAccessible(true);
            } catch (NoSuchMethodException | SecurityException ex) {
                logger.warning("%s initialization failed: %s", getClass().getSimpleName(), ex);
                getCentralDirectory = null;
            } finally {
                initialized = true;
            }
        }

        @Override
        public DataProvider getCentralDirectoryProvider(URL url, ZipFile f) {
            lazyInit();

            if (getCentralDirectory == null) {
                return null;
            }

            return consumer -> {
                byte[] cd = (byte[]) getCentralDirectory.invoke(f);
                consumer.consume(cd);
            };
        }
    }

    public static class GenericCentralDirectoryFactory implements CentralDirectoryProviderFactory {

        private static class ZipInputStreamCentralDirectoryParser implements DataProvider {

            private final RandomAccessBuffer buffer;

            // read data for debug:
            public long endpos;
            public long endtot;
            public long endsiz;
            public long endoff;
            public long endcom;
            public long cenpos;
            public long locpos;

            private static class RandomAccessBuffer {
                // max central directory length == maxChunks * DEFAULT_BUFFER_SIZE

                private final int maxChunks = 100;
                private final Queue<Chunk> queue;
                private final Chunk[] chunks;
                private final InputStream stream;
                private boolean eof;
                private long dataStartOffset;
                private long dataEndOffset;

                private static class Chunk {

                    public byte[] buf;
                    public int filled;

                    Chunk() {
                        filled = 0;
                        buf = new byte[DEFAULT_BUFFER_SIZE];
                    }

                    public void clear() {
                        filled = 0;
                    }
                }

                private void renumerate() {
                    int i = 0;
                    for (Chunk c : queue) {
                        chunks[i++] = c;
                    }
                }

                private Chunk getFreeChunk() {
                    Chunk c;

                    if (queue.size() < maxChunks) {
                        c = new Chunk();
                        queue.add(c);
                        return c;
                    }

                    c = queue.remove();
                    if (c.buf.length != c.filled) {
                        throw new RuntimeException("!");
                    }
                    dataStartOffset += c.filled;
                    c.clear();
                    queue.add(c);

                    renumerate();
                    return c;
                }

                public RandomAccessBuffer(InputStream stream) {
                    this.stream = stream;
                    this.eof = false;
                    this.dataEndOffset = 0;
                    this.dataStartOffset = 0;
                    this.queue = new LinkedList();
                    this.chunks = new Chunk[maxChunks];
                }

                public void deliver(DataConsumer consumer, long off, long len) throws IOException {
                    if (off < this.dataStartOffset || off + len > this.dataEndOffset) {
                        throw new RuntimeException(
                                String.format("off=%dl, len=%dl are out of range [%dl, %dl]", off, len, dataStartOffset, dataEndOffset)
                        );
                    }
                    int firstChunk = getChunkNumber(off);
                    int firstOffset = getOffsetInsideChunk(off);
                    int lastChunk = getChunkNumber(off + len);
                    int lastOffset = getOffsetInsideChunk(off + len);

                    if (firstChunk == lastChunk) {
                        // TODO: check
                        if (lastOffset < firstOffset) {
                            throw new RuntimeException(String.format("lastOffset=%dl < firstOffset=%dl", lastOffset, firstOffset));
                        }
                        consumer.consume(chunks[firstChunk].buf, firstOffset, lastOffset - firstOffset);
                    } else {
                        consumer.consume(chunks[firstChunk].buf, firstOffset, DEFAULT_BUFFER_SIZE - firstOffset);
                        for (int j = firstChunk + 1; j < lastChunk; ++j) {
                            consumer.consume(chunks[j].buf, 0, DEFAULT_BUFFER_SIZE);
                        }
                        consumer.consume(chunks[lastChunk].buf, 0, lastOffset);
                    }
                }

                public boolean meetEOF() {
                    return !this.eof;
                }

                public long getStart() {
                    return this.dataStartOffset;
                }

                public long getEnd() {
                    return this.dataEndOffset;
                }

                public boolean readNextPage() throws IOException {
                    if (this.eof) {
                        return false;
                    }

                    Chunk c = getFreeChunk();
                    long read = -1;
                    while (c.buf.length - c.filled > 0
                            && (read = stream.read(c.buf, c.filled, c.buf.length - c.filled)) != -1) {
                        c.filled += read;
                        dataEndOffset += read;
                    }
                    renumerate();

                    if (read == -1) {
                        this.eof = true;
                    }

                    return !this.eof;
                }

                public void readUntilEOF() throws IOException {
                    while (readNextPage()) {
                    }
                }

                private int getChunkNumber(long i) {
                    if (i > this.dataEndOffset || i < this.dataStartOffset) {
                        throw new RuntimeException(
                                String.format("i is not in range: i=%dl range=[%dl, %dl]", i, dataStartOffset, dataEndOffset)
                        );
                    }
                    return (int) ((i - this.dataStartOffset) / DEFAULT_BUFFER_SIZE);
                }

                private int getOffsetInsideChunk(long i) {
                    if (i > this.dataEndOffset || i < this.dataStartOffset) {
                        throw new RuntimeException(
                                String.format("i is not in range: i=%dl range=[%dl, %dl]", i, dataStartOffset, dataEndOffset)
                        );
                    }
                    return (int) (i % DEFAULT_BUFFER_SIZE);
                }

                public byte get8(long off) {
                    if (off < dataStartOffset || off >= dataEndOffset) {
                        throw new RuntimeException(
                                String.format("RandomAccessBuffer out of index access off=%dl, expecting range: [%dl, %dl]", off, dataStartOffset, dataEndOffset)
                        );
                    }
                    return chunks[getChunkNumber(off)].buf[getOffsetInsideChunk(off)];
                }

                public int get16(long off) {
                    return (get8(off) & 0xff) | ((get8(off + 1) & 0xff) << 8);
                }

                public long get32(long off) {
                    return (get16(off) | ((long) get16(off + 2) << 16)) & 0xffffffffL;
                }

                public long get64(long off) {
                    return get32(off) | (get32(off + 4) << 32);
                }
            }

            ZipInputStreamCentralDirectoryParser(InputStream is) {
                this.buffer = new RandomAccessBuffer(is);
            }

            // some zip consts
            static final int ENDTOT = 10;       // total number of entries
            static final int ENDSIZ = 12;       // central directory size in bytes
            static final int ENDOFF = 16;       // offset of first CEN header
            static final int ENDCOM = 20;       // zip file comment length

            static final long LOCSIG = 0x04034b50L;   // "PK\003\004"
            static final long CENSIG = 0x02014b50L;   // "PK\001\002"
            static final long ENDSIG = 0x06054b50L;   // "PK\005\006"
            static final int ENDHDR = 22;       // END header size

            static final long ZIP64_MAGICVAL = 0xFFFFFFFFL;
            static final int ZIP64_LOCHDR = 20;           // ZIP64 end loc header size
            static final long ZIP64_LOCSIG = 0x07064b50L;  // "PK\006\007"
            static final int ZIP64_LOCOFF = 8;       // offset of zip64 end
            static final long ZIP64_ENDSIG = 0x06064b50L;  // "PK\006\006"
            static final int ZIP64_ENDOFF = 48;      // offset of first CEN header
            static final int ZIP64_ENDTOT = 32;      // total number of entries
            static final int ZIP64_MAGICCOUNT = 0xFFFF;
            static final int ZIP64_ENDSIZ = 40;      // central directory size in bytes

            private void readCentralDirectory(DataConsumer consumer) throws IOException {
                // ZipEntry iterator was not implemented since it seems that most jar
                // deflated entries has no specified csize property.
                // ZipInputStream relies in that case on Inflator which reads compressed entry
                // until end and then next LOC header may be read
                // however we will find here first LOC header just for verification of END header
                // later
                long firstLOC = -1;

                buffer.readNextPage();

                for (int i = 0; !buffer.meetEOF() && i >= buffer.getStart() && i + 4 < buffer.getEnd(); ++i) {
                    if (buffer.get32(i) == LOCSIG) {
                        firstLOC = i;
                    }
                }

                // read tail
                buffer.readUntilEOF();

                // Find END Header
                for (long i = buffer.getEnd() - ENDHDR; i > buffer.getStart(); --i) {

                    if (buffer.get32(i) == ENDSIG) {
                        long endpos = i;
                        long endtot = buffer.get16(endpos + ENDTOT);
                        long endsiz = buffer.get32(endpos + ENDSIZ);
                        long endoff = buffer.get32(endpos + ENDOFF);
                        long endcom = buffer.get16(endpos + ENDCOM);
                        long cenpos = endpos - endsiz;
                        long locpos = cenpos - endoff;

                        if ((endpos + ENDHDR + endcom != buffer.getEnd())
                                && ((cenpos < 0 || locpos < 0 || (firstLOC != -1 && locpos != firstLOC)
                                || buffer.get32(cenpos) != CENSIG))) {
                            continue;
                        }

                        do {
                            if (endpos < ZIP64_LOCHDR || buffer.get32(endpos - ZIP64_LOCHDR) != ZIP64_LOCSIG) {
                                break;
                            }

                            long endpos64 = buffer.get64(endpos - ZIP64_LOCHDR + ZIP64_LOCOFF);

                            if (buffer.get32(endpos64) != ZIP64_ENDSIG) {
                                break;
                            }

                            long endsiz64 = buffer.get64(endpos64 + ZIP64_ENDSIZ);
                            long endoff64 = buffer.get64(endpos64 + ZIP64_ENDOFF);
                            long endtot64 = buffer.get64(endpos64 + ZIP64_ENDTOT);

                            if (endsiz64 != endsiz && endsiz != ZIP64_MAGICVAL
                                    || endoff64 != endoff && endoff != ZIP64_MAGICVAL
                                    || endtot64 != endtot && endtot != ZIP64_MAGICCOUNT) {
                                break;
                            }

                            endsiz = endsiz64;
                            endoff = endoff64;
                            endtot = endtot64;
                            endpos = endpos64;
                            cenpos = endpos - endsiz;
                            locpos = cenpos - endoff;

                        } while (false);

                        if (DEBUG) {
                            // For debug only
                            this.endpos = endpos;
                            this.endtot = endtot;
                            this.endsiz = endsiz;
                            this.endoff = endoff;
                            this.endcom = endcom;
                            this.cenpos = cenpos;
                            this.locpos = locpos;
                        }

                        buffer.deliver(consumer, cenpos, endpos + ENDHDR - cenpos);

                        return;
                    }
                }
            }

            @Override
            public void deliver(DataConsumer consumer) throws IOException {
                readCentralDirectory(consumer);
            }
        }

        @Override
        public DataProvider getCentralDirectoryProvider(URL url, ZipFile f) {
            return consumer -> {
                try (InputStream is = url.openConnection().getInputStream()) {
                    ZipInputStreamCentralDirectoryParser zis = new ZipInputStreamCentralDirectoryParser(is);

                    zis.deliver(consumer);

                    if (DEBUG) {
                        // verifying number of entries
                        long numberOfEntries = Collections.list(f.entries()).size();
                        if (zis.endtot != numberOfEntries) {
                            throw new RuntimeException(String.format(
                                    "ERROR: file=%s:%s contains %dl entries, but we calculated the number as %dl.",
                                    f, f.getClass().getName(), numberOfEntries, zis.endtot)
                            );
                        }
                        // TODO: verify cdLength here
                    }
                }
            };
        }
    }

    // Sprint 2.x
    public static class Spring2xCentralDirectoryFactory implements CentralDirectoryProviderFactory {

        private boolean initialized = false;

        Class jarFile;
        Field data;
        Class randomAccessData;
        Method read;
        Method getSize;
        Class centralDirectoryEndRecord;
        Field centralDirectoryEndRecordBlock;
        Field centralDirectoryEndRecordBlockOffset;
        Field centralDirectoryEndRecordBlockLength;
        Method getCentralDirectory;
        Constructor<Object> newCentralDirectoryEndRecord;

        private synchronized void lazyInit(ClassLoader cl) {
            if (initialized) {
                return;
            }

            if (cl == null) {
                cl = this.getClass().getClassLoader();
            }
            try {
                jarFile = Class.forName("org.springframework.boot.loader.jar.JarFile", true, cl);

                data = jarFile.getDeclaredField("data");
                data.setAccessible(true);
                randomAccessData = data.getType();
                read = randomAccessData.getDeclaredMethod("read");
                read.setAccessible(true);
                getSize = randomAccessData.getDeclaredMethod("getSize", null);
                getSize.setAccessible(true);
                centralDirectoryEndRecord = Class.forName("org.springframework.boot.loader.jar.CentralDirectoryEndRecord", true, cl);
                centralDirectoryEndRecordBlock = centralDirectoryEndRecord.getDeclaredField("block");
                centralDirectoryEndRecordBlock.setAccessible(true);
                Class blockType = centralDirectoryEndRecordBlock.getType();
                if (!blockType.equals(byte[].class)) {
                    throw new RuntimeException("CentralDirectoryEndRecord.block is not of byte[] type, as expected ==" + blockType.getName());
                }
                centralDirectoryEndRecordBlockOffset = centralDirectoryEndRecord.getDeclaredField("offset");
                centralDirectoryEndRecordBlockOffset.setAccessible(true);
                if (!int.class.equals(centralDirectoryEndRecordBlockOffset.getType())) {
                    throw new RuntimeException("CentralDirectoryEndRecord.offset is not of int type, as expected ==" + centralDirectoryEndRecordBlockOffset.getType());
                }
                centralDirectoryEndRecordBlockLength = centralDirectoryEndRecord.getDeclaredField("size");
                centralDirectoryEndRecordBlockLength.setAccessible(true);
                if (!int.class.equals(centralDirectoryEndRecordBlockLength.getType())) {
                    throw new RuntimeException("CentralDirectoryEndRecord.size is not of int type, as expected ==" + centralDirectoryEndRecordBlockLength.getType());
                }
                getCentralDirectory = centralDirectoryEndRecord.getDeclaredMethod("getCentralDirectory", randomAccessData);
                getCentralDirectory.setAccessible(true);
                newCentralDirectoryEndRecord = centralDirectoryEndRecord.getDeclaredConstructor(randomAccessData);
                newCentralDirectoryEndRecord.setAccessible(true);
            } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException | SecurityException ex) {
                logger.warning("%s initialization failed: %s", getClass().getSimpleName(), ex);
                jarFile = null;
                data = null;
                randomAccessData = null;
                read = null;
                getSize = null;
                centralDirectoryEndRecord = null;
                centralDirectoryEndRecordBlock = null;
                centralDirectoryEndRecordBlockOffset = null;
                centralDirectoryEndRecordBlockLength = null;
                getCentralDirectory = null;
                newCentralDirectoryEndRecord = null;
            } finally {
                initialized = true;
            }
        }

        private void check(ZipFile f) throws IllegalArgumentException {
            if (!f.getClass().equals(jarFile)) {
                throw new IllegalArgumentException("Wrong extractor chosen");
            }
        }

        @Override
        public DataProvider getCentralDirectoryProvider(URL url, ZipFile f) {
            lazyInit(f.getClass().getClassLoader());

            if (jarFile == null) {
                return null;
            }

            check(f);

            return consumer -> {
                Object d = data.get(f);
                Object end = newCentralDirectoryEndRecord.newInstance(d);
                Object cd = getCentralDirectory.invoke(end, d);
                byte[] res = (byte[]) read.invoke(cd);
                byte[] endBlock = (byte[]) centralDirectoryEndRecordBlock.get(end);
                int offset = (int) centralDirectoryEndRecordBlockOffset.get(end);
                int length = (int) centralDirectoryEndRecordBlockLength.get(end);
                consumer.consume(res);
                consumer.consume(endBlock, offset, length);
            };
        }
    }

    // Spring 2.6.x for JarFileWrapper
    public static class Spring26xCentralDirectoryFactory implements CentralDirectoryProviderFactory {

        private boolean initialized = false;

        private Field parent;
        private Class jarFileWrapper;
        private Class jarFile;
        private Spring2xCentralDirectoryFactory delegate;

        private synchronized void lazyInit(ClassLoader cl) {
            if (initialized) {
                return;
            }

            if (cl == null) {
                cl = this.getClass().getClassLoader();
            }

            try {
                jarFile = Class.forName("org.springframework.boot.loader.jar.JarFile", true, cl);
                jarFileWrapper = Class.forName("org.springframework.boot.loader.jar.JarFileWrapper", true, cl);

                parent = jarFileWrapper.getDeclaredField("parent");
                delegate = new Spring2xCentralDirectoryFactory();
                parent.setAccessible(true);
                if (!jarFile.equals(parent.getType())) {
                    throw new IllegalArgumentException(
                            String.format("Field 'parent' expecting to have type %s, but has %s", jarFile.getName(), parent.getType())
                    );
                }
            } catch (IllegalArgumentException | ClassNotFoundException | NoSuchFieldException | SecurityException ex) {
                logger.warning("%s initialization failed: %s", getClass().getSimpleName(), ex);
                parent = null;
                jarFileWrapper = null;
                jarFile = null;
                delegate = null;
            } finally {
                initialized = true;
            }
        }

        private void check(ZipFile f) throws IllegalArgumentException {
            if (!f.getClass().equals(jarFileWrapper)) {
                throw new IllegalArgumentException("Wrong extractor chosen");
            }
        }

        @Override
        public DataProvider getCentralDirectoryProvider(URL url, ZipFile f) {
            lazyInit(f.getClass().getClassLoader());

            if (jarFile == null) {
                return null;
            }

            check(f);

            try {
                Object fParent = parent.get(f);
                return delegate.getCentralDirectoryProvider(url, (ZipFile) fParent);
            } catch (IllegalArgumentException | IllegalAccessException ex) {
                logger.warning(getClass().getSimpleName() + " initialization failed", ex);
                return null;
            }
        }
    }

    // Sprint 1.x
    public static class Spring1xCentralDirectoryFactory implements CentralDirectoryProviderFactory {

        private boolean initialized = false;

        Class jarFile;
        Field data;
        Class randomAccessData;
        Class resourceAccess;
        Object resourceAccessOnce;
        Method getInputStream;
        Class centralDirectoryEndRecord;
        Field centralDirectoryEndRecordBlock;
        Field centralDirectoryEndRecordBlockOffset;
        Field centralDirectoryEndRecordBlockLength;
        Method getCentralDirectory;
        Constructor<Object> newCentralDirectoryEndRecord;

        private void lazyInit(ClassLoader cl) {
            if (initialized) {
                return;
            }
            try {
                if (cl == null) {
                    cl = this.getClass().getClassLoader();
                }

                jarFile = Class.forName("org.springframework.boot.loader.jar.JarFile", true, cl);

                data = jarFile.getDeclaredField("data");
                data.setAccessible(true);
                randomAccessData = data.getType();
                resourceAccess = Class.forName("org.springframework.boot.loader.data.RandomAccessData$ResourceAccess", true, cl);
                for (Object o : resourceAccess.getEnumConstants()) {
                    if ("ONCE".equals(o.toString())) {
                        resourceAccessOnce = o;
                    }
                }
                getInputStream = randomAccessData.getDeclaredMethod("getInputStream", resourceAccess);
                getInputStream.setAccessible(true);
                centralDirectoryEndRecord = Class.forName("org.springframework.boot.loader.jar.CentralDirectoryEndRecord", true, cl);
                centralDirectoryEndRecordBlock = centralDirectoryEndRecord.getDeclaredField("block");
                centralDirectoryEndRecordBlock.setAccessible(true);
                Class blockType = centralDirectoryEndRecordBlock.getType();
                if (!blockType.equals(byte[].class)) {
                    throw new RuntimeException("CentralDirectoryEndRecord.block is not of byte[] type, as expected ==" + blockType.getName());
                }
                centralDirectoryEndRecordBlockOffset = centralDirectoryEndRecord.getDeclaredField("offset");
                centralDirectoryEndRecordBlockOffset.setAccessible(true);
                if (!int.class.equals(centralDirectoryEndRecordBlockOffset.getType())) {
                    throw new RuntimeException("CentralDirectoryEndRecord.offset is not of int type, as expected ==" + centralDirectoryEndRecordBlockOffset.getType());
                }
                centralDirectoryEndRecordBlockLength = centralDirectoryEndRecord.getDeclaredField("size");
                centralDirectoryEndRecordBlockLength.setAccessible(true);
                if (!int.class.equals(centralDirectoryEndRecordBlockLength.getType())) {
                    throw new RuntimeException("CentralDirectoryEndRecord.size is not of int type, as expected ==" + centralDirectoryEndRecordBlockLength.getType());
                }
                getCentralDirectory = centralDirectoryEndRecord.getDeclaredMethod("getCentralDirectory", randomAccessData);
                getCentralDirectory.setAccessible(true);
                newCentralDirectoryEndRecord = centralDirectoryEndRecord.getDeclaredConstructor(randomAccessData);
                newCentralDirectoryEndRecord.setAccessible(true);
            } catch (IllegalArgumentException | ClassNotFoundException | NoSuchFieldException | NoSuchMethodException | SecurityException ex) {
                logger.warning("%s initialization failed: %s", getClass().getSimpleName(), ex);
                jarFile = null;
                data = null;
                randomAccessData = null;
                resourceAccess = null;
                resourceAccessOnce = null;
                getInputStream = null;
                centralDirectoryEndRecord = null;
                centralDirectoryEndRecordBlock = null;
                centralDirectoryEndRecordBlockOffset = null;
                centralDirectoryEndRecordBlockLength = null;
                getCentralDirectory = null;
                newCentralDirectoryEndRecord = null;
            } finally {
                initialized = true;
            }
        }

        private void check(ZipFile f) throws IllegalArgumentException {
            if (!f.getClass().equals(jarFile)) {
                throw new IllegalArgumentException("Wrong extractor chosen");
            }
        }

        @Override
        public DataProvider getCentralDirectoryProvider(URL url, ZipFile f) {
            lazyInit(f.getClass().getClassLoader());

            if (jarFile == null) {
                return null;
            }

            check(f);

            return consumer -> {
                Object d = data.get(f);
                Object end = newCentralDirectoryEndRecord.newInstance(d);
                Object cd = getCentralDirectory.invoke(end, d);
                byte[] endBlock = (byte[]) centralDirectoryEndRecordBlock.get(end);
                int offset = (int) centralDirectoryEndRecordBlockOffset.get(end);
                int length = (int) centralDirectoryEndRecordBlockLength.get(end);
                try (InputStream is = (InputStream) getInputStream.invoke(cd, resourceAccessOnce)) {
                    byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                    int read;
                    while ((read = is.read(buffer)) > 0) {
                        consumer.consume(buffer, 0, read);
                    }
                    consumer.consume(endBlock, offset, length);
                }
            };
        }
    }

    public final static class JarShortDigest {

        public byte[] centralDirectoryHash;
        public byte[] manifestHash; // CEN & ENDHDR
        public String provider; // TODO: use index instead of string?
        public long centralDirectoryLength; // CEN & ENDHDR

        private JarShortDigest(byte[] centralDirectoryHash, byte[] manifestHash, String provider, long centralDirectoryLength) {
            this.centralDirectoryHash = centralDirectoryHash;
            this.manifestHash = manifestHash;
            this.provider = provider;
            this.centralDirectoryLength = centralDirectoryLength;
        }

        public byte[] getCentralDirectoryHash() {
            return centralDirectoryHash;
        }

        public byte[] getManifestHash() {
            return manifestHash;
        }

        public String getProvider() {
            return provider;
        }

        public long getCentralDirectoryLength() {
            return centralDirectoryLength;
        }
    }

    public byte[] getManifestHash(final MessageDigest digest, final URL url, final JarFile file) throws IOException {
        ZipEntry entry = file.getEntry("META-INF/MANIFEST.MF");
        if (entry == null) {
            logger.trace("ZipTools.getDigest got JarFile=%s [%s] without manifest file", file, url);
            return null;
        }
        InputStream manifestInputStream = file.getInputStream(entry);

        char[] buffer = new char[DEFAULT_BUFFER_SIZE];
        StringBuilder out = new StringBuilder();
        Reader in = new InputStreamReader(manifestInputStream, StandardCharsets.UTF_8);

        for (int numRead; (numRead = in.read(buffer, 0, buffer.length)) != -1;) {
            out.append(buffer, 0, numRead);
        }

        digest.reset();
        return digest.digest(out.toString().getBytes());
    }

    public static boolean isJDKNative(JarFile file) {
        return file != null && isKnownJarFileImplementation(file.getClass());
    }

    private static boolean isKnownJarFileImplementation(Class<? extends JarFile> c) {
        return c.equals(JarFile.class) || c.equals(URLJarFile.class);
    }

    public static boolean isJarFile(String name) {
        return (name != null && (name.endsWith(".jar") || name.endsWith(".war")));
    }

    /**
     * Get JarShortDigest for the provided jar.
     *
     * NOTE: Side effect: this method resets provided {@code MessageDigest}!
     *
     * @param digest a MessageDigest to use for storing
     * @param url
     * @param file
     * @return calculated digest
     * @throws IOException
     */
    public JarShortDigest getDigest(final MessageDigest digest, final URL url, final JarFile file) throws IOException {
        CentralDirectoryProviderFactory providerFactory = null;
        Throwable exception;

        try {
            // By default calculate central directory hash only for known JDK JarFile classes that corresponds to real jar
            // files from classpath
            if (!allowAdvancedJarLoadDetection) {
                if (isJDKNative(file)) {
                    providerFactory = jdkCentralDirectoryFactory;
                } else {
                    logger.debug("Unsupported jarFile type %s skip notification for %s", file.getClass().getName(), url.toString());
                    return null;
                }
            } else {
                Class fileClass = file.getClass();
                providerFactory = forceToUseGenericProvider
                        ? genericCentralDirectoryFactory
                        : findCentralDirectoryProviderFactoryForClass(fileClass);

                // Cache the result
                cdTools.put(fileClass.getName(), providerFactory);
            }

            return getDigest(providerFactory, digest, url, file);
        } catch (Exception ex) {
            exception = ZipFileClosedException.isZipFileClosedException(ex)
                    ? new ZipFileClosedException(ex)
                    : ex;
        }

        if (exception != null) {
            if (DEBUG) {
                exception.fillInStackTrace().printStackTrace(System.err);
            }

            if (exception instanceof IOException) {
                throw (IOException) exception;
            }

            logger.warning("central directory sha256 calculation ended with exception (%s). url=%s, file=%s, zip-cd provider=%s",
                    exception, url, file, providerFactory == null ? "null" : providerFactory.getClass().getName());

            if (providerFactory != null && !isJDKNative(file)) {
                logger.trace("Removing cached providerFactory for class %s", file.getClass().getName());
                cdTools.remove(file.getClass().getName(), providerFactory);
            }
        }

        return null;
    }

    private JarShortDigest getDigest(final CentralDirectoryProviderFactory providerFactory, final MessageDigest digest, final URL url, final JarFile file) throws Exception {
        logger.trace("zip tools calculating sha256 of central directory: digest=%s, url=%s, file=%s, file.class=%s, ze=%s",
                digest.toString().trim(), url, file, file.getClass().getName(), providerFactory.getClass().getName());

        DataProvider provider = providerFactory.getCentralDirectoryProvider(url, file);

        if (provider == null) {
            logger.trace("Failed to craete DataProvider for %s", url);
            return null;
        }

        digest.reset();
        AtomicLong centralDirectoryLength = new AtomicLong(0);

        provider.deliver((b, off, len) -> {
            digest.update(b, off, len);
            centralDirectoryLength.getAndAdd(len);

            if (DEBUG) {
                System.out.printf(">>> += encodeToStringOrNull(%d, %d, %d);\n", b.length, off, len);
                System.out.printf(">>> += %s\n", encodeToStringOrNull(b, off, len));
            }
        });

        // digest should be preserved here, since getManifestHash will reset it
        byte[] centralDirectoryHash = digest.digest();
        byte[] manifestHash = getManifestHash(digest, url, file);
        return new JarShortDigest(centralDirectoryHash, manifestHash, jdkCentralDirectoryFactory.getClass().getName(), centralDirectoryLength.get());
    }

    public final static class ZipFileClosedException extends IOException {

        public ZipFileClosedException(Throwable cause) {
            super(cause);
        }

        private static boolean isZipFileClosedException(Throwable ex) {
            while (ex != null) {
                // See ZipFile.ensureOpen()
                if (ex instanceof IllegalStateException && "zip file closed".equals(ex.getMessage())) {
                    return true;
                }
                // See ZipFile.ensureOpenOrZipException()
                if (ex instanceof ZipException && "ZipFile closed".equals(ex.getMessage())) {
                    return true;
                }
                ex = ex.getCause();
            }
            return false;
        }
    }
}
