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
package com.azul.crs.util.logging;

import static com.azul.crs.util.logging.Logger.Level.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Configurable multi-channel logger.
 *
 * <p>
 * To register log channel, apply {@link LogChannel} annotation on a class that
 * needs to be associated with a tag.</p>
 * <p>
 * <b>Example:</b><pre><code>
 * &#64;LogChannel("traffic")
 * public class TrafficWatcher {
 * ....
 * }</code></pre></p>
 * <p>
 * To obtain an instance of a logger, associated with the tagged class, use
 * <pre><code>
 *     Logger logger = Logger.getLogger(TrafficWatcher.class);
 * </code></pre>
 * </p>
 * <p>
 * <b>Implementation details:</b><br>{@link LogChannel} annotations are
 * processed during compile-time of your application and produce a registry of
 * log channels -- a file in <code>META-INF</code> directory. If your
 * application uses <code>maven-shade-plugin</code> (or other means) to merge
 * several jar files into a single jar, you need to make sure that registries
 * from different jar files get merged. The registry files are simple text files
 * that can be concatenated. <code>maven-shade-plugin</code> allows to do this
 * merge with <code>AppendingTransformer</code>:</p>
 * <p>
 * <b>Example of <code>maven-shade-plugin</code> pom.xml entry:</b>
 * <pre>
 *   ....
 *     &lt;plugin&gt;
 *       &lt;groupId&gt;org.apache.maven.plugins&lt;/groupId&gt;
 *       &lt;artifactId&gt;maven-shade-plugin&lt;/artifactId&gt;
 *       &lt;executions&gt;
 *         &lt;execution&gt;
 *           &lt;phase&gt;package&lt;/phase&gt;
 *           &lt;goals&gt;
 *             &lt;goal&gt;shade&lt;/goal&gt;
 *           &lt;/goals&gt;
 *         &lt;/execution&gt;
 *       &lt;/executions&gt;
 *       &lt;configuration&gt;
 *         &lt;transformers&gt;
 *           &lt;transformer implementation="org.apache.maven.plugins.shade.resource.AppendingTransformer"&gt;
 *             &lt;resource&gt;META-INF/crslog.channels.cfg&lt;/resource&gt;
 *           &lt;/transformer&gt;
 *         &lt;/transformers&gt;
 *       &lt;/configuration&gt;
 *     &lt;/plugin&gt;
 *   ....
 * </pre></p>
 *
 */
public final class Logger {

    private static final String REGISTRY = "META-INF/crslog.channels.cfg";

    private static final Map<String, Logger> TAG_TO_LOGGER = new HashMap<>();
    private static final Map<String, String> CLASS_TO_TAG = new HashMap<>();
    private static boolean registryLoaded = false;
    private static final List<PrintWriter> writers = new CopyOnWriteArrayList<>();
    private static final long vmStartTime = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getUptime();

    private static synchronized void readRegistry() {
        if (registryLoaded) {
            return;
        }

        try {
            Set<URL> urls = new HashSet<>();
            ClassLoader classLoader = Logger.class.getClassLoader();
            if (classLoader != null) {
                urls.addAll(Collections.list(classLoader.getResources(REGISTRY)));
            }
            urls.addAll(Collections.list(ClassLoader.getSystemResources(REGISTRY)));

            for (URL url : urls) {
                Properties props = new Properties();
                try (InputStream is = url.openStream()) {
                    props.load(is);
                    Enumeration<?> names = props.propertyNames();
                    while (names.hasMoreElements()) {
                        String klassName = names.nextElement().toString();
                        String cfg = props.getProperty(klassName);
                        Level lowestUpstreamLevel = Level.DEBUG;
                        int delim = cfg.lastIndexOf(':');
                        String tag;
                        if (delim > 0) {
                            tag = cfg.substring(0, delim);
                            lowestUpstreamLevel = Level.valueOf(cfg.substring(delim + 1));
                        } else {
                            tag = cfg;
                        }
                        TAG_TO_LOGGER.put(tag, new Logger(tag, lowestUpstreamLevel));
                        CLASS_TO_TAG.put(klassName, tag);
                    }
                }
            }
        } catch (Throwable th) {
            java.util.logging.Logger.getLogger(Logger.class.getName()).log(java.util.logging.Level.SEVERE, null, th);
        } finally {
            registryLoaded = true;
        }
    }

    // Accessible for unit-tests
    static void reset() {
        CLASS_TO_TAG.clear();
        TAG_TO_LOGGER.clear();
        globalLevel = ERROR;
        globalShowStacktrace = false;
        globalShowTimestamp = false;
        registryLoaded = false;
    }

    public static enum Level {
        TRACE,
        DEBUG,
        INFO,
        WARNING,
        ERROR,
        OFF;
        private final String n;

        private Level() {
            n = name().toLowerCase();
        }
    }

    private static Level globalLevel = ERROR;
    private static boolean globalShowStacktrace = false;
    private static boolean globalShowTimestamp = false;

    private final String tag;
    private final Level lowestUpstreamLevel;
    private Level level = null;
    private Boolean showStacktrace = null;
    private static Logger defaultLogger; // normally null, only used for unexpected situations

    private Logger(String tag, Level lowestUpstreamLevel) {
        this.tag = tag;
        this.lowestUpstreamLevel = lowestUpstreamLevel;
    }

    private static Logger loggerForTag(String tag) {
        return TAG_TO_LOGGER.get(tag);
    }

    public static void addOutputStream(OutputStream handler) {
        writers.add(new PrintWriter(handler));
    }

    private static Logger getDefaultLogger() {
        synchronized (Logger.class) {
            if (defaultLogger == null)
                defaultLogger = new Logger("default", Level.TRACE);
        }
        return defaultLogger;
    }

    public static Logger getLogger(Class klass) {
        String name = klass.getCanonicalName();
        String tag = CLASS_TO_TAG.get(name);
        if (tag == null) {
            readRegistry();
            tag = CLASS_TO_TAG.get(name);
        }
        return tag == null ? getDefaultLogger() : loggerForTag(tag);
    }

    /**
     * Parses options and configures global/individual log channel settings.
     *
     * <p>
     * {@link parseOption} can be called several times. Only <code>name</code>s
     * started with the <code>'log'</code> prefix are processed.
     * </p>
     *
     * <p>
     * Supported <code>name</code> format: <code>log[+&lt;tag&gt;]</code><br>
     * Supported <code>value</code> format:
     * <code>&lt;level&gt;[+stack][+time]</code>
     * </p>
     *
     * <p>
     * If a <code>tag</code> is not specified in the <code>name</code> (that is,
     * <code>name == "log"</code>), the <code>value</code> is applied globally
     * and affects all tagged log channels (with some exceptions. See
     * below).</p>
     * <p>
     * When an option is specified multiple times for the same channel, the last
     * configuration wins.</p>
     * <p>
     * Individual channel's configuration discards effect of global
     * <code>+stack</code> setting for that channel.</p>
     * <p>
     * <code>+time</code> provided for <i>any</i> parameter has global effect.
     * So time is either printed for all log lines or not printed at all.</p>
     * <p>
     * By default, stacks are not dumped, time is not printed.</p>
     * <p>
     * <b>Examples</b>:<br>
     * <ul>
     * <li><code>log=trace</code>: all channels will log with trace level;</li>
     * <li><code>log=debug+time</code> followed by
     * <code>log+inventory=trace</code>: All channels except
     * <code>inventory</code> will log with <code>debug</code> level,
     * <code>inventory</code> channel will use <code>trace</code> level and
     * timestamps will be printed for all log entries;</li>
     * <li><code>"log=info+stack"</code> followed by
     * <code>"log+inventory=debug"</code>: All channels except
     * <code>inventory</code> will log with <code>info</code> level and with
     * stacktraces, <code>inventory</code> channel will use <code>debug</code>
     * level and no stacktraces will be printed for it.</li>
     * </ul>
     * </p>
     *
     * @param name name part of the parameter
     * @param value value part of the parameter
     *
     */
    public static void parseOption(String name, String value) {
        readRegistry();

        boolean showStacktrace = false;
        boolean showTimestamp = false;
        while (true) {
            int len;
            if (value.endsWith("+stack")) {
                showStacktrace = true;
                len = 6;
            } else if (value.endsWith("+time")) {
                showTimestamp = true;
                len = 5;
            } else {
                break;
            }
            value = value.substring(0, value.length() - len);
        }

        Level level;
        try {
            level = Level.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            System.err.println("[CRS.log][error] unsupported log level '" + value + "'");
            return;
        }

        if (name.equals("log")) {
            globalLevel = level;
            globalShowStacktrace = showStacktrace;
        } else if (name.startsWith("log+")) {
            String tag = name.substring(4);
            // vm channel configuration is managed by VM
            if (!"vm".equals(tag)) {
                Logger logger = loggerForTag(tag);
                if (logger != null) {
                    logger.setLevel(level).setShowStacktrace(showStacktrace);
                } else {
                    System.err.println("[CRS.log][error] unknown CRS log channel " + name.substring(4));
                }
            }
        } else {
            System.err.println("[CRS.log][error] unknown CRS log option " + name);
            return;
        }

        if (showTimestamp && !globalShowTimestamp) {
            globalShowTimestamp = true;
        }
    }

    private boolean isLogLevelEnabled(Level level) {
        return getLevel().ordinal() <= level.ordinal();
    }

    private boolean isUpsreamLevelEnabled(Level level) {
        return lowestUpstreamLevel.ordinal() <= level.ordinal();
    }

    public boolean isEnabled(Level level) {
        if (getLevel().equals(OFF)) {
            return false;
        }
        return ((!writers.isEmpty()) || isLogLevelEnabled(level));
    }

    private Logger setLevel(Level level) {
        this.level = level;
        return this;
    }

    private Logger setShowStacktrace(boolean showStacktrace) {
        this.showStacktrace = showStacktrace;
        return this;
    }

    // Accessible for unit-tests
    boolean showStacktrace() {
        return showStacktrace == null ? globalShowStacktrace : showStacktrace;
    }

    // Accessible for unit-tests
    boolean showTimestamp() {
        return globalShowTimestamp;
    }

    public Level getLevel() {
        return level == null ? globalLevel : level;
    }

    public void trace(String format, Object... args) {
        log(TRACE, format, args);
    }

    public void debug(String format, Object... args) {
        log(DEBUG, format, args);
    }

    public void info(String format, Object... args) {
        log(INFO, format, args);
    }

    public void warning(String format, Object... args) {
        log(WARNING, format, args);
    }

    public void error(String format, Object... args) {
        log(ERROR, format, args);
    }

    public void log(Level level, String format, Object... args) {
        try {
            boolean logLevelEnabled = isLogLevelEnabled(level);
            boolean upstreamLevelEnabled = isUpsreamLevelEnabled(level);
            if (!upstreamLevelEnabled && !logLevelEnabled) {
                return;
            }

            StringBuilder prefix = new StringBuilder();
            if (showTimestamp()) {
                prefix.append(elapsedTime());
            }

            prefix.append("[CRS.")
                    .append(tag)
                    .append("][").append(level.n).append("] ");

            if (getLevel() == TRACE) {
                Throwable t = new Throwable();
                StackTraceElement e = t.getStackTrace()[2];
                prefix.append(e.getClassName())
                        .append('.')
                        .append(e.getMethodName())
                        .append(": ");
            }

            if (logLevelEnabled) {
                logDecoratedLines(new PrintWriter(System.err), prefix, format, args);
            }

            if (upstreamLevelEnabled) {
                if (!showTimestamp()) {
                    // Force timestamp reporting for writers
                    prefix.insert(0, elapsedTime());
                }
                for (PrintWriter out : writers) {
                    logDecoratedLines(out, prefix, format, args);
                }
            }
        } catch (Throwable th) {
            java.util.logging.Logger.getLogger(Logger.class.getName()).log(java.util.logging.Level.SEVERE, null, th);
        }
    }

    private CharSequence elapsedTime() {
        long delta = System.currentTimeMillis() - vmStartTime;
        long sec = delta / 1000;
        long ms = delta % 1000;
        CharSequence millis = Long.toString(1000 + ms).subSequence(1, 4);
        return sec + "." + millis + ": ";
    }

    private void logDecoratedLines(PrintWriter out, StringBuilder prefix, String format, Object... args) {
        out.append(prefix);
        for (char c : String.format(format, args).toCharArray()) {
            out.append(c);
            if (c == '\n') {
                out.append(prefix);
            }
        }
        out.println();

        if (showStacktrace()) {
            for (Object arg : args) {
                if (arg instanceof Throwable) {
                    ((Throwable) arg).printStackTrace(out);
                }
            }
        }

        out.flush();
    }
}
