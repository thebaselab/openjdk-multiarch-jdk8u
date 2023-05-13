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

package com.azul.tooling;

import com.azul.tooling.in.NetConnectionEvent;
import com.azul.tooling.in.Tooling;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;

// TODO package-private when CAT-side serializer is implemented
public class AnyConnectionEventModel implements Handler.EventModel {
    private static final boolean canSendHeaders = "true".equals(
            AccessController.doPrivileged(new PrivilegedAction<String>() {
                @Override
                public String run() {
                    return System.getProperty("com.azul.tooling.http.dumpHeaders");
                }
            }));

    private String protocol;
    private String toAddress;
    private Integer toPort;
    private String toHost = "";
    private String fromAddress;
    private Integer fromPort;
    private String fromHost = "";
    private Long connectionInitiatedTime = -1L;
    private Long connectionEstablishedTime = -1L;
    private String headers = "";
    private String url = "";
    private boolean originating;

    public String getProtocol() {
        return protocol;
    }

    public String getToAddress() {
        return toAddress;
    }

    public int getToPort() {
        return toPort;
    }

    public String getToHost() {
        return toHost;
    }

    public String getHeaders() {
        return headers;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public int getFromPort() {
        return fromPort;
    }

    public String getFromHost() {
        return fromHost;
    }

    public long getConnectionInitiatedTime() {
        return connectionInitiatedTime;
    }
    public long getConnectionEstablishedTime() {
        return connectionEstablishedTime;
    }

    public String getUrl() {
        return url;
    }

    public boolean getOriginating() {
        return originating;
    }

    @Override
    public void init(Tooling.ToolingEvent event) {
        NetConnectionEvent connectionInfo = (NetConnectionEvent) event;

        protocol = connectionInfo.protocol;
        if (connectionInfo.toAddress == null) {
            toAddress = "";
            toHost = "";
        } else {
            toAddress = connectionInfo.toAddress.getHostAddress();
            toHost = connectionInfo.toAddress.getHostName();
        }
        toPort = connectionInfo.toPort;
        if (connectionInfo.fromAddress == null) {
            fromAddress = "";
            fromHost =  "";
        } else {
            fromAddress = connectionInfo.fromAddress.getHostAddress();
            fromHost = connectionInfo.fromAddress.getHostName();
        }
        fromPort = connectionInfo.fromPort;
        connectionInitiatedTime = connectionInfo.connectionInitiatedTime;
        connectionEstablishedTime = connectionInfo.connectionEstablishedTime;
        headers = canSendHeaders && connectionInfo.headers != null ? dumpHeaders(connectionInfo.headers) : "";
        url = connectionInfo.url;
        originating = connectionInfo.originating;
    }

    private static String dumpHeaders(Map<String, List<String>> headers) {
        StringBuilder sb = new StringBuilder("{");
        //sb.append("<HEADERS url=\"" + conn.getURL().toString() + "\">");
        for (Map.Entry<String, List<String>> entry: headers.entrySet()) {
            sb.append(entry.getKey())
                    .append(":")
                    .append(entry.getValue())
                    .append(",");
        }
        sb.append("}");
        return sb.toString();
    }
}
