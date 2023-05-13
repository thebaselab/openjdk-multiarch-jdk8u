/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug 8268427
 * @summary benchmark for the AlgorithmConstraints:checkAlgorithm performance
 * @run main/manual AlgorithmConstraintsPermits
 */

import java.security.AlgorithmConstraints;
import java.security.CryptoPrimitive;
import java.util.concurrent.CountDownLatch;
import java.util.EnumSet;
import java.util.Set;
import sun.security.util.DisabledAlgorithmConstraints;

public class AlgorithmConstraintsPermits {
    static class Job {
        private final String alg;
        private final boolean permitted;
        private final int iterations;
        private final Set<CryptoPrimitive> primitives;
        private final AlgorithmConstraints tlsDisabledAlgConstraints;

        Job(String alg, int iterations) {
            this.alg = alg;
            this.iterations = iterations;
            this.primitives = EnumSet.of(CryptoPrimitive.KEY_AGREEMENT);
            this.tlsDisabledAlgConstraints =
                    new DisabledAlgorithmConstraints(DisabledAlgorithmConstraints.PROPERTY_TLS_DISABLED_ALGS);
            permitted = tlsDisabledAlgConstraints.permits(primitives, alg(), null);
        }
        String alg() { return alg; }
        int iterations() { return iterations; }
        void work() {
            for (int i = 0; i < iterations; i++) {
                if (permitted != tlsDisabledAlgConstraints.permits(primitives, alg(), null))
                    throw new Error();
            }
        }
    }

    private static void collectAllGarbage() {
        final CountDownLatch drained = new CountDownLatch(1);
        try {
            System.gc();        // enqueue finalizable objects
            new Object() { protected void finalize() {
                drained.countDown(); }};
            System.gc();        // enqueue detector
            drained.await();    // wait for finalizer queue to drain
            System.gc();        // cleanup finalized objects
        } catch (InterruptedException e) { throw new Error(e); }
    }

    /**
     * Runs each job for long enough that all the runtime compilers
     * have had plenty of time to warm up, i.e. get around to
     * compiling everything worth compiling.
     * Returns array of average times per job per run.
     */
    private static double[] time0(Job ... jobs) throws Throwable {
        final long warmupNanos = 5L * 1000L * 1000L * 1000L;
        double[] nanos = new double[jobs.length];
        for (int i = 0; i < jobs.length; i++) {
            collectAllGarbage();
            long t0 = System.nanoTime();
            long t;
            int j = 0;
            do { jobs[i].work(); j++; }
            while ((t = System.nanoTime() - t0) < warmupNanos);
            nanos[i] = ((double)t/j)/jobs[i].iterations;
        }
        return nanos;
    }

    private static void time(Job ... jobs) throws Throwable {

        time0(jobs); // Warm up run
        double[] nanos = time0(jobs); // Real timing run

        final String algHeader   = "Algorithm";
        final String nanosHeader  = "Nanos";

        int algWidth   = algHeader.length();
        int nanosWidth  = nanosHeader.length();

        for (int i = 0; i < jobs.length; i++) {
            algWidth = Math.max(algWidth, jobs[i].alg().length());

            nanosWidth = Math.max(nanosWidth,
                    String.format("%.3f", nanos[i]).length());
        }

        String format = String.format("%%-%ds %%%d.3f%%n",
                algWidth, nanosWidth);
        String headerFormat = String.format("%%-%ds %%%ds%%n",
                algWidth, nanosWidth);
        System.out.printf(headerFormat, algHeader, nanosHeader);

        // Print out warmup and real times
        for (int i = 0; i < jobs.length; i++)
            System.out.printf(format, jobs[i].alg(), nanos[i]);
    }

    private static String keywordValue(String[] args, String keyword) {
        for (String arg : args)
            if (arg.startsWith(keyword))
                return arg.substring(keyword.length() + 1);
        return null;
    }

    private static int intArg(String[] args, String keyword, int defaultValue) {
        String val = keywordValue(args, keyword);
        return val == null ? defaultValue : Integer.parseInt(val);
    }

    /**
     * Usage: [iterations=N]
     */
    public static void main(String[] args) throws Throwable {
        final int iterations = intArg(args, "iterations", 10000);
        final Job[] jobs = new Job[]{
                new Job("SSLv3", iterations),
                new Job("DES", iterations),
                new Job("NULL", iterations),
                new Job("TLS1.3", iterations),
        };

        time(jobs);
    }
}
