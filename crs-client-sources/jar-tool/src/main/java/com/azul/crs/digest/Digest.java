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
package com.azul.crs.digest;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Digest {

    private static final MessageDigest DIGEST = initDigest("SHA-256");
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();
    private static final int BLOCK_SIZE = 64;
    private final MessageDigest md;
    private final ByteBuffer mdbuffer = ByteBuffer.wrap(new byte[BLOCK_SIZE]);

    private Digest(MessageDigest md) {
        this.md = md;
    }

    public static Digest get() {
        return new Digest(cloneDigest(DIGEST));
    }

    private static MessageDigest initDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException ex) {
            Logger.getLogger(Digest.class.getName()).log(Level.SEVERE, null, ex);
            System.exit(0);
            return null;
        }
    }

    private static MessageDigest cloneDigest(MessageDigest from) {
        try {
            return (MessageDigest) from.clone();
        } catch (CloneNotSupportedException ex) {
            Logger.getLogger(Digest.class.getName()).log(Level.SEVERE, null, ex);
            System.exit(0);
            return null;
        }
    }

    private void update(byte[] data, int pos, int length) {
        int rem = mdbuffer.remaining();
        if (length > rem) {
            mdbuffer.put(data, pos, rem);
            md.update(mdbuffer.array(), 0, BLOCK_SIZE);
            pos += rem;
            length -= rem;
            mdbuffer.rewind();
        }

        while (length >= BLOCK_SIZE) {
            md.update(data, pos, BLOCK_SIZE);
            pos += BLOCK_SIZE;
            length -= BLOCK_SIZE;
        }

        if (length > 0) {
            mdbuffer.put(data, pos, length);
        }
    }

    private void flushMDBuffer() {
        int pos = mdbuffer.position();
        if (pos > 0) {
            md.update(mdbuffer.array(), 0, pos);
            mdbuffer.rewind();
        }
    }

    /*package*/ void update(ByteBuffer data) {
        update(data.array(), 0, data.position());
    }

    /*package*/ void update(short data) {
        if (mdbuffer.remaining() < 2) {
            flushMDBuffer();
        }

        mdbuffer.put((byte) (data & 0xFF));
        mdbuffer.put((byte) ((data >> 8) & 0xFF));
    }

    /*package*/ void update(String data) {
        update(data.getBytes(), 0, data.length());
    }

    // Encode given array to hex string
    private static String encode(byte[] bytes) {
        int strlen = bytes.length << 1;
        char[] str = new char[strlen];
        for (int i = 0, j = 0; i < strlen; j++) {
            byte b = bytes[j];
            str[i++] = DIGITS[(b >> 4) & 0xf];
            str[i++] = DIGITS[b & 0xf];
        }
        return new String(str);
    }

    // Access to the MessageDigest
    public MessageDigest getMessageDigest() {
        return md;
    }

    public String asHexString() {
        flushMDBuffer();
        return encode(md.digest());
    }
}
