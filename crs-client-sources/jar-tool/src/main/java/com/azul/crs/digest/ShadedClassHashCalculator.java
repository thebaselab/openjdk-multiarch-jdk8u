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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.SortedSet;
import java.util.TreeSet;

public final class ShadedClassHashCalculator {

    private ShadedClassHashCalculator() {
    }

    public static void updateHash(InputStream in, Digest hash) throws IllegalAccessException, IOException {
        DataInputStream is = new DataInputStream(in);
        int magic = is.readInt();
        if (magic != 0xCAFEBABE) {
            throw new IllegalAccessException("Error reading class - not valid type");
        }

        hash.update(is.readShort()); // minor_version
        hash.update(is.readShort()); // major_version

        ConstantPool cp = ConstantPool.readConstantPool(is);

        hash.update(is.readShort()); // access_flags
        hash.update(cp.getClassShortName(is.readUnsignedShort())); // this class name
        hash.update(cp.getClassShortName(is.readUnsignedShort())); // superclass name

        int count;

        // interfaces (sorted by name)
        TreeSet<String> interfaces = new TreeSet<>();
        count = is.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            interfaces.add(cp.getClassShortName(is.readUnsignedShort()));
        }
        interfaces.forEach(hash::update);

        StringBuilder sb = new StringBuilder();

        // fields with access_flags (sorted by name)
        TreeSet<String> fields = new TreeSet<>();
        count = is.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            int access_flags = is.readShort();
            int name_index = is.readUnsignedShort();
            is.readShort(); // descriptor_index. Not used in digest
            skipAttributes(is); // attributes. Not used in digest

            sb.setLength(0);
            sb.append(cp.getStringConstant(name_index));
            sb.append(access_flags);

            fields.add(sb.toString());
        }
        fields.forEach(hash::update);

        // method digests
        SortedSet<String> method_digests = new TreeSet<>();

        count = is.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            Digest methodHash = Digest.get();
            methodHash.update(is.readShort()); // access flags
            methodHash.update(cp.getStringConstant(is.readUnsignedShort())); // method name
            is.readShort(); // descriptor_index. Not used in digest
            processMethodAttribures(is, cp, methodHash);
            method_digests.add(methodHash.asHexString());
        }

        method_digests.forEach(hash::update);

        // do not read/process class attributes
    }

    private static void skipAttributes(DataInputStream r) throws IOException {
        int count = r.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            r.readShort(); // attrNameIndex
            r.skipBytes(r.readInt());
        }
    }

    private static void processMethodAttribures(DataInputStream r, ConstantPool cp, Digest hash) throws IOException {
        int count = r.readUnsignedShort();

        for (int i = 0; i < count; i++) {
            int name_index = r.readUnsignedShort();
            int size = r.readInt();
            if ("Code".equals(cp.getStringConstant(name_index))) {
                hash.update(r.readShort()); // max_stack
                r.readShort(); // max_locals -- skipped, as shaders may manipulate this field
                int code_length = r.readInt();
                ByteCodeProcessor bcp = new ByteCodeProcessor(cp);
                CountingInputStream bytes = new CountingInputStream(r);
                while (bytes.position() < code_length) {
                    bcp.processMethodByteCode(bytes, hash);
                }
                // skip rest of the attribute block
                r.skipBytes(size - 8 - code_length);
            } else {
                // skip the whole attribute block
                r.skipBytes(size);
            }
        }
    }
}
