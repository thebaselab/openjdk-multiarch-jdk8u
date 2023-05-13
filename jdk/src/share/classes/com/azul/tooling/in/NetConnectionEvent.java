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

package com.azul.tooling.in;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static java.lang.System.currentTimeMillis;

@Model("com.azul.tooling.AnyConnectionEventModel")
public final class NetConnectionEvent implements Tooling.ToolingEvent {
    private static final boolean isEnabled;

    static {
        isEnabled = Tooling.isEventTypeEnabled(NetConnectionEvent.class);
    }

    private NetConnectionEvent(String protocol, InetAddress toAddress, int toPort, InetAddress fromAddress,
                               int fromPort, long connectionInitiatedTime, long connectionEstablishedTime, boolean originating,
                               Map<String, List<String>> headers, String url) {
        this.protocol = protocol;
        this.toAddress = toAddress;
        this.toPort = toPort;
        this.fromAddress = fromAddress;
        this.fromPort = fromPort;
        this.connectionInitiatedTime = connectionInitiatedTime;
        this.connectionEstablishedTime = connectionEstablishedTime;
        this.originating = originating;
        this.headers = headers;
        this.url = url;
    }

    public static NetConnectionEvent udpConnectionEvent(InetAddress toAddress, int toPort, InetAddress fromAddress,
                                                        int fromPort, long endTimeConnection, boolean originating) {
        return new NetConnectionEvent("udp", toAddress, toPort, fromAddress, fromPort, -1,
                endTimeConnection, originating, null, null);
    }

    public static NetConnectionEvent httpConnectionEvent(String protocol, InetAddress toAddress, int toPort,
                                                         Map<String, List<String>> headers, String url) {
        return new NetConnectionEvent(protocol, toAddress, toPort, null, -1,
                -1, currentTimeMillis(), true, headers, url);
    }

    public static NetConnectionEvent tcpConnectionEvent(InetAddress toAddress, int toPort, InetAddress fromAddress,
                              int fromPort, long startTimeConnection, long endTimeConnection, boolean originating) {
        return new NetConnectionEvent("tcp", toAddress, toPort, fromAddress, fromPort,
                startTimeConnection, endTimeConnection, originating, null, null);
    }

    public static boolean isEnabled() {
        return isEnabled;
    }

    public boolean isEventEnabled() {
        return isEnabled();
    }

    public final String protocol;
    public final InetAddress toAddress;
    public final int toPort;
    public final InetAddress fromAddress;
    public final int fromPort;
    public final long connectionInitiatedTime;
    public final long connectionEstablishedTime;
    public final Map<String, List<String>> headers;
    public final String url;
    public final boolean originating; // true for send/request, false for receive/respond
}
