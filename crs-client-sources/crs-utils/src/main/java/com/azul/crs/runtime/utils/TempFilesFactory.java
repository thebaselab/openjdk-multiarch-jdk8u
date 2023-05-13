/*
 *
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public final class TempFilesFactory {

    private TempFilesFactory() {
    }

    public static class TempFile extends File {

        private TempFile(File f) {
            super(f.toURI());
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o);
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    public static TempFile createTempJarFile() throws IOException {
        return new TempFile(Files.createTempFile(tempJarFileNamePrefix, tempJarFileNameSuffix).toFile());
    }

    private static final String tempJarFileNamePrefix = "jar_cache";
    private static final String tempJarFileNameSuffix = ".jar";

    private static Timer timer;
    private static boolean shutdownStarted = false;
    private static final Map<File, TimerTask> map = new HashMap<>();

    public static synchronized void shutdown() {
        shutdownStarted = true;

        timer.cancel();

        map.forEach((f, t) -> {
            try {
                f.delete();
                t.cancel();
            } catch (Throwable th) {
                // catch any possible SecurityExceptions here...
            }
        });

        map.clear();
        timer.purge();
    }

    private static synchronized Timer getTimer() {
        if (timer == null) {
            timer = new Timer("CRSTempFilesExpirationTimer", true);
        }
        return timer;
    }

    /**
     * Schedule {@code file} deletion with {@code delay} milliseconds delay.
     *
     * @param file file to delete
     * @param delay milliseconds to wait before deletion
     * @param action action to be done before file deletion
     * @throws IllegalStateException if it is too late to schedule file for deletion.
     */
    public static synchronized void scheduleDeletion(TempFile file, long delay, Runnable action) {
        if (shutdownStarted) {
            throw new IllegalStateException("Too late to schedule deletion: shutdown in progress");
        }

        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                deleteIfScheduled(file, action);
            }

        };

        map.put(file, task);
        getTimer().schedule(task, delay);
    }

    public static synchronized void scheduleDeletion(TempFile file, long delay) {
        scheduleDeletion(file, delay, null);
    }

    /**
     * Delete {@code file} immediately, but only if it was previously scheduled for deletion.
     *
     * @param file file to delete
     * @param action action to be done before file deletion
     * @return {@code true} if file was deleted, {@code false} otherwise.
     */
    public static synchronized boolean deleteIfScheduled(File file, Runnable action) {
        TimerTask task = map.remove(file);
        if (task != null) {
            task.cancel();
            try {
                if (action != null && file.exists())
                    action.run();
            } finally {
                return file.delete();
            }
        }
        return false;
    }

    public static synchronized boolean deleteIfScheduled(File file) {
        return deleteIfScheduled(file, null);
    }
}

