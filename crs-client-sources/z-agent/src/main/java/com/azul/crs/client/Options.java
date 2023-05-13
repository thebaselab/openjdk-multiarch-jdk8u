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
package com.azul.crs.client;


import com.azul.crs.util.logging.Logger;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import com.azul.crs.util.logging.LogChannel;

// Note, it's impossible to change logger level for Options class because most of Options code is executed
// before the Logger levels could be set up
@LogChannel("args")
enum Options {
    // also see Client.ClientProps for list of known arguments
    props, // properties file to load
    lifetimejfr, // settings of lifetime JFR recording or "disable" to disable
    stackRecordId, // suffix of DNS record to query for endpoing
    mode, // on/off/auto, on -- always start Agent, auto -- check main method (do not launch in case of tools javac/javap/...)
    enable, // bool, parsed in native, leaving here to omit "unknown flag error"
    notifyFirstCall, // bool, parsed in native, leaving here to omit "unknown flag error"
    UnlockExperimentalCRS, // bool, allows use of CRS via environment variable
    forceSyncTimeout, // timeout for synchronization of cached data
    delayTermination, // how much time to wait for data to be sent to the cloud during VM shutdown
    delayInitiation, // how much time to wait before start CRS agent
    sendJVMLogs("yes", new String[] {"yes", "no"}); // bool, do we allow to send (GC/JFR/JVM) logs to cloud

    private static final String DEFAULT_SHARED_PROPS_FILE = "az_crs.properties";
    private static final String DEFAULT_USER_PROPS_FILE = ".az_crs" + File.separatorChar + "config.properties";

    private String value;
    private static Map<Client.ClientProp, Object> clientProps = new HashMap<>();
    private static LinkedList<HashMap.Entry<String, String>> loggerOptions;

    private static final String agentAuthPrefix = "agentAuth=";
    private static int connectionPort;
    private static int connectionSecret;

    private final String emptyValueAlias;
    private final String[] allowedValuesList;

    Options() {
        this.emptyValueAlias = null;
        this.allowedValuesList = null;
    }

    Options(String emptyValueAlias, String[] allowedValuesList) {
        this.emptyValueAlias = emptyValueAlias;
        this.allowedValuesList = allowedValuesList;
    }

    private boolean valueIsFrom(String[] values) {
        return values != null && Arrays.stream(values).anyMatch(x -> x == this.value);
    }

    private void verify() {
        if ("".equals(value) && emptyValueAlias != null) {
            value = emptyValueAlias;
        }
        if (allowedValuesList != null) {
              if (!valueIsFrom(allowedValuesList)) {
                  Logger.getLogger(Options.class).error("AzCRSArguments contains '%s'='%s' which restricted to be set to one of the following values: %s", this.toString(), value,
                      String.join(", ", Arrays.stream(allowedValuesList).map(x -> "'" + x + "'").toArray(String[]::new)));
                  value = null; // default value
              }
        }
    }

    String get() {
        return value;
    }

    boolean isSet() { return value != null; }

    int getInt() { return Integer.parseInt(value); }

    long getLong() { return Long.parseLong(value); }

    boolean isYes() { return "yes".equals(value); }

    private void set(String value) {
        this.value = value;
        verify();
    }

    static int getConnectionPort() { return connectionPort; }
    static int getConnectionSecret() { return connectionSecret; }

    static void read(String commandLineArgs) {
        // logger options are not applied during parsing in order to have predictable
        // behavior of the logger during parsing
        loggerOptions = new LinkedList<>();

        // First extract injected vmListenerPort property from the commandLineArgs
        if (commandLineArgs.startsWith(agentAuthPrefix)) {
            commandLineArgs = initAgentConnectionInfo(commandLineArgs);
        }

        // first get property file name specified by the user
        String envArgs = System.getenv("AZ_CRS_ARGUMENTS");
        String explicitPropsFile = getPropFileNameFromArgs(commandLineArgs);
        if (explicitPropsFile == null)
            explicitPropsFile = getPropFileNameFromArgs(envArgs);

        File propsFile;
        if (explicitPropsFile != null) {
            // if there is user specified property file use it, reporting problem it it does not exist
            propsFile = new File(explicitPropsFile);
            if (!propsFile.exists())
                Logger.getLogger(Options.class).error("specified properties file %s does not exist", propsFile.getPath());
        } else {
            // the user specified file does not exist, use one of default ones
            propsFile = new File(System.getProperty("user.home") + File.separatorChar + DEFAULT_USER_PROPS_FILE);
            if (!propsFile.exists())
                propsFile = new File(System.getProperty("java.home") + File.separatorChar + "lib" + File.separatorChar + DEFAULT_SHARED_PROPS_FILE);
        }
        if (propsFile.exists())
            tryLoadingProps(propsFile);

        // then read properties from environment, overriding configuration read from property file
        readArgs(envArgs);
        // then read command line
        readArgs(commandLineArgs);

        // apply read logger levels
        for (HashMap.Entry<String, String> e: loggerOptions)
            Logger.parseOption(e.getKey(), e.getValue());
        // don't waste memory
        loggerOptions = null;
    }

    private static String initAgentConnectionInfo(String args) {
        int pos = agentAuthPrefix.length();
        int argsLen = args.length();
        while (pos < argsLen) {
            char c = args.charAt(pos);
            if (!Character.isDigit(c)) {
                break;
            }
            connectionPort = connectionPort * 10 + (c - '0');
            pos++;
        }
        if (pos < argsLen && args.charAt(pos) == '+') {
            pos++;
            while (pos < argsLen) {
                char c = args.charAt(pos);
                if (!Character.isDigit(c)) {
                    break;
                }
                connectionSecret = connectionSecret * 10 + (c - '0');
                pos++;
            }
        }
        if (pos < argsLen && args.charAt(pos) == ',') {
            pos++;
        }
        return args.substring(pos);
    }

    private static String getPropFileNameFromArgs(String args) {
        String propsFile = null;

        int sPos = 0;
        // changeset ZULU-37670: flag/path renamings accordingly PMM-282
        // introduced mode=on/auto parameter that may be prepended to CRS arguments
        // the second parameter after mode=??? still may be property file that
        // need to be handled
        final String mode1 = "mode=on,";
        final String mode2 = "mode=auto,";
        if (args != null && args.startsWith(mode1)) {
          sPos += mode1.length();
        }
        if (args != null && args.startsWith(mode2)) {
          sPos += mode2.length();
        }
        final String failOn = "failJVMOnError,";
        if (args != null && args.startsWith(failOn)) {
          sPos += failOn.length();
        }

        if (args != null && args.length() > sPos) {
            int cPos = args.indexOf(',', sPos);
            if (cPos < 0)
                cPos = args.length();
            if (args.charAt(sPos) != ',') {
                // special case, the first part of options string can be properties file alone
                int ePos = args.indexOf('=', sPos);
                if (ePos < 0 || ePos > cPos) {
                    // not in form of "name=value". any unknown word is treated as a file name
                    try {
                        Options.valueOf(args.substring(sPos, cPos));
                    }
                    catch (IllegalArgumentException theArgumentIsNotAnOptionName) {
                        propsFile = args.substring(sPos, cPos);
                    }
                }
            }
            String propsName = props.name() + "=";
            int propsNameLength = propsName.length();
            do {
                cPos = args.indexOf(',', sPos);
                if (cPos < 0)
                    cPos = args.length();
                if (args.startsWith(propsName, sPos)) {
                    propsFile = args.substring(sPos + propsNameLength, cPos);
                    break;
                }
                sPos = cPos + 1;
            } while (sPos < args.length());
        }

        return propsFile;
    }

    private static void readArgs(String args) {
        // regex would be more appropriate but let's attempt not to load too much of JDK early
        if (args == null)
            return;

        int sPos = 0;
        do {
            int cPos = args.indexOf(',', sPos);
            if (cPos == -1)
                cPos = args.length();
            int ePos = args.indexOf('=', sPos);
            if (ePos == -1 || ePos > cPos)
                ePos = cPos;

            String name = args.substring(sPos, ePos);
            String value = ePos == cPos ? "" : args.substring(ePos + 1, cPos);

            // need to avoid loading property files on this stage
            if (!props.name().equals(name))
                process(name, value, sPos == 0 && ePos == cPos);

            sPos = cPos + 1;
        } while (sPos < args.length());
    }

    private static void process(String name, String value, boolean ignoreMaybePropsFile) {
        if (name.equals("log") || name.startsWith("log+")) {
            // Logger has it's own command line syntax
            loggerOptions.add(new HashMap.SimpleEntry<>(name, value));
        } else if (props.name().equals(name)) {
            tryLoadingProps(new File(value));
        } else if (name.length() > 0) {
            for (Client.ClientProp p: Client.ClientProp.class.getEnumConstants()) {
                if (p.value().equals(name)) {
                    clientProps.put(p, value);
                    return;
                }
            }
            try {
                Options.valueOf(name).set(value);
            }
            catch (IllegalArgumentException iae) {
                if (!ignoreMaybePropsFile)
                    Logger.getLogger(Options.class).error("unrecognized CRS agent option %s ignored", name);
            }
        }
    }

    /**
     * Tries loading properties from files in the list.
     **/
    private static void tryLoadingProps(File file) {
        Properties props = new Properties();
        try {
            props.load(new FileInputStream(file));
            // Logger.getLogger(Options.class).info("loaded properties file %s", file.getPath()); impossible to enable
            for (String name: props.stringPropertyNames()) {
                process(name, props.getProperty(name), false);
            }
        }
        catch (IOException ex) {
            Logger.getLogger(Options.class).error("cannot load specified properties file %s: %s", file.getPath(), ex.getMessage());
        }
    }

    public static Map<Client.ClientProp, Object> getClientProps() { return clientProps; }
}
