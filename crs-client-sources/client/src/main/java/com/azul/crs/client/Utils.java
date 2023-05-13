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

import com.azul.crs.json.JSONSerializer;
import com.azul.crs.json.JSONStaticSerializer;
import com.azul.crs.util.logging.LogChannel;
import com.azul.crs.util.logging.Logger;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;


import static java.util.concurrent.TimeUnit.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Generic utils shared by server and client CRS components */
@LogChannel("utils")
public final class Utils {
    private static final Logger logger = Logger.getLogger(Utils.class);

    private Utils() {
    }

    /** Generates random UUID string */
    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    /** Generates deterministic UUID by given string value */
    public static String uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes()).toString();
    }

    /** Generates deterministic UUID by array of values */
    public static String uuid(Object... values) {
        return uuid(Arrays.toString(values));
    }

    /** Gets lower-cased string or null */
    public static String lower(String s) {
        return s != null ? s.toLowerCase() : null;
    }

    /** Returns a current time count in abstract units. To be used along with the following methods */
    public static long currentTimeCount() {
        return System.nanoTime();
    }

    /** Returns a time count in abstract units which is timeoutMillis ms in the future. */
    public static long nextTimeCount(long timeoutMillis) { return System.nanoTime() + timeoutMillis*1000_000; }

    /** Helper to log measured time */
    public static String elapsedTimeString(long startTimeStamp) {
        return String.format(" (%,d ms)", elapsedTimeMillis(startTimeStamp));
    }

    public static long elapsedTimeMillis(long startTimeCount) {
        return (System.nanoTime() - startTimeCount + 500_000) / 1000_000;
    }

    /** Sleep helper to hide try/catch boilerplate */
    public static void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException ie) {}
    }

    /** Helper to get current wall clock time for VM and backend events (ms).*/
    public static long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    private static final char[] digit = "0123456789abcdef".toCharArray();

    public static String encodeToStringOrNull(byte[] hash, int off, int len) {
        if (null == hash) return null;

        char[] str = new char[len*2];
        for(int i = off; i < off + len; i++) {
            byte b = hash[i];
            str[(i-off)*2] = digit[(b >>> 4) & 0x0f];
            str[(i-off)*2+1] = digit[b & 0x0f];
        }
        return new String(str);
    }

    public static String encodeToStringOrNull(byte[] hash) {
        if (null == hash) return null;

        return encodeToStringOrNull(hash, 0, hash.length);
    }

    public static final JSONSerializer serializer = new JSONStaticSerializer();
    public static final JSONSerializer prettySerializer = new JSONStaticSerializer(true);
    public static final int BUFFER_SIZE = 8 * 1024;
    private static final ThreadLocal<WeakReference<byte[]>> buffers = new ThreadLocal<>();

    public static void transfer(InputStream is, OutputStream output) throws IOException {
        WeakReference<byte[]> ref = buffers.get();
        byte[] buffer;
        if (ref == null || (buffer = ref.get()) == null) {
            buffer = new byte[BUFFER_SIZE];
            buffers.set(new WeakReference<>(buffer));
        }
        int read;
        while ((read = is.read(buffer, 0, buffer.length)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    public static final class Digest {
        // Digest can be used concurrently from multiple threads.
        // There is a pool of identically initialized MACs that are used for digest calculation.
        // It is done this way because clone() is not supported by all MAC implementations and
        // we don't want to do expensive initialization every time - so we pre-populate the pool
        // with several (MAC_POOL_SIZE) pre-initialized MACs. We have several instances to avoid
        // contantion.

        // Number of pre-initialized MACs. Ideally should be as many as number of concurrent threads
        // that call digest()
        private static final int MAC_POOL_SIZE = 2;
        private static volatile BlockingQueue<Mac> macPool = null;

        private static final String DIGEST_ALGO = "HmacSHA256";
        private static final Object lock = new Object();
        private static boolean macPoolInitialized = false;

        private Digest() {
        }

        private static Mac acquireMAC() throws InterruptedException {
            BlockingQueue<Mac> pool = macPool;

            if (pool == null) {
                synchronized (lock) {
                    pool = macPool;
                    if (pool == null && !macPoolInitialized) {
                        macPoolInitialized = true;
                        ArrayBlockingQueue p = new ArrayBlockingQueue<>(MAC_POOL_SIZE);

                        // Initialize all MACs in pool with the same secret
                        byte[] secret = new byte[256];
                        new SecureRandom().nextBytes(secret);
                        SecretKeySpec secretKeySpec = new SecretKeySpec(secret, DIGEST_ALGO);

                        for (int i = 0; i < MAC_POOL_SIZE; i++) {
                            try {
                                Mac mac = Mac.getInstance(DIGEST_ALGO);
                                if (mac == null) {
                                    logger.error("Unable to instantiate MAC for %s", DIGEST_ALGO);
                                    return null;
                                }
                                mac.init(secretKeySpec);
                                p.put(mac);
                            } catch (InvalidKeyException | NoSuchAlgorithmException ex) {
                                logger.error("Unable to instantiate MAC for %s", DIGEST_ALGO);
                                return null;
                            }
                        }

                        macPool = p;
                        pool = p;
                    }
                }
            }

            return pool == null ? null : pool.take();
        }

        private static void releaseMAC(Mac mac) throws InterruptedException {
            BlockingQueue<Mac> pool = macPool;
            if (pool != null) {
                mac.reset();
                pool.put(mac);
            }
        }

        public static byte[] digest(byte[] bytes) {
            byte[] result = null;
            try {
                Mac mac = acquireMAC();
                if (mac != null) {
                    try {
                        result = mac.doFinal(bytes);
                    } finally {
                        releaseMAC(mac);
                    }
                }
            } catch (InterruptedException ex) {
                // Unexpected
                Thread.interrupted();
            }
            return result;
        }

        public static String digestBase64(String data) {
            byte[] digest = digest(data.getBytes());
            return digest == null ? null : Base64.getEncoder().encodeToString(digest);
        }
    }

    public static final class CountingOutputStream extends FilterOutputStream {

        private final Consumer<Long> onClose;
        private long counter = 0;

        public CountingOutputStream(OutputStream orig, Consumer<Long> onClose) {
            super(orig);
            this.onClose = onClose;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            counter += len;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            counter++;
        }

        @Override
        public void close() throws IOException {
            super.close();
            onClose.accept(counter);
        }
    }

    public static final class Deadline {

        private final long deadline;
        private volatile boolean expired = false;

        private Deadline(long deadline) {
            this.deadline = deadline;
        }

        /**
         * Create a new deadline object that will expire at the specified offset from the current moment.
         *
         * @param time time offset
         * @param units time offset units
         * @throws IllegalArgumentException if {@code time} is negative.
         */
        public static Deadline in(long time, TimeUnit units) {
            if (time < 0) {
                throw new IllegalArgumentException("Can setup deadline in the future only");
            }
            return new Deadline(System.nanoTime() + units.toNanos(time));
        }

        /**
         * Check whether the deadline has been expired.
         *
         * @return {@code false} if the deadline has not been expired yet.
         * {@code true} otherwise.
         */
        public boolean hasExpired() {
            remainder(NANOSECONDS);
            return expired;
        }

        /**
         * Get amount of time before the deadline.
         *
         * @param timeUnit time units to get remainder in
         * @return time before the deadline in specified units. If deadline has expired, 0 is returned.
         */
        public long remainder(TimeUnit timeUnit) {
            if (expired) {
                return 0;
            }
            long nanosecondsBeforeDeadline = Math.max(0, deadline - System.nanoTime());
            if (nanosecondsBeforeDeadline == 0) {
                expired = true;
                return 0;
            }
            return timeUnit.convert(nanosecondsBeforeDeadline, NANOSECONDS);
        }

        public <R, E extends Exception> Optional<R> applyIfNotExpired(TimeLimitedFunction<R, E> function) throws E {
            long reminder = remainder(MILLISECONDS);
            return reminder > 0 ? Optional.ofNullable(function.apply(reminder)) : Optional.empty();
        }

        public <E extends Exception> void runIfNotExpired(TimeLimitedRunnable<E> action) throws E {
            long reminder = remainder(MILLISECONDS);
            if (reminder > 0) {
                action.run(reminder);
            }
        }

        @FunctionalInterface
        public static interface TimeLimitedFunction<R, E extends Exception> {

            R apply(long reminderMillis) throws E;
        }

        @FunctionalInterface
        public static interface TimeLimitedRunnable<E extends Exception> {

            void run(long reminderMillis) throws E;
        }
    }
}
