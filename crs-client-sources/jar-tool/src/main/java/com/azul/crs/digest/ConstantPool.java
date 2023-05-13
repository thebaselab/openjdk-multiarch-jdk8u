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
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/*package*/ final class ConstantPool {

    private final Map<Integer, Integer> dedup;
    private final ConstantPoolEntry[] entries;

    private static enum ConstantType {
        /*  0 */ RESERVED_0,
        /*  1 */ UTF8(ConstantUtf8Entry::new),
        /*  2 */ RESERVED_2,
        /*  3 */ INTEGER(ConstantIntegerEntry::new),
        /*  4 */ FLOAT(ConstantFloatEntry::new),
        /*  5 */ LONG(ConstantLongEntry::new),
        /*  6 */ DOUBLE(ConstantDoubleEntry::new),
        /*  7 */ CLASS(ConstantClassEntry::new),
        /*  8 */ STRING(ConstantStringEntry::new),
        /*  9 */ FIELDREF(ConstantFieldRefEntry::new),
        /* 10 */ METHODREF(ConstantMethodRefEntry::new),
        /* 11 */ INTERFACEMETHODREF(ConstantInterfaceMethodRefEntry::new),
        /* 12 */ NAMEANDTYPE(ConstantNameAndTypeEntry::new),
        /* 13 */ RESERVED_13,
        /* 14 */ RESERVED_14,
        /* 15 */ METHODHANDLE(ConstantMethodHandleEntry::new),
        /* 16 */ METHODTYPE(ConstantMethodTypeEntry::new),
        /* 17 */ RESERVED_17,
        /* 18 */ INVOKEDYNAMIC(ConstantInvokeDynamicEntry::new),
        /* 19 */ MODULE(ConstantModuleEntry::new),
        /* 20 */ PACKAGE(ConstantPackageEntry::new);

        private final Supplier<ConstantPoolEntry> supplier;

        private ConstantType() {
            this(null);
        }

        private ConstantType(Supplier<ConstantPoolEntry> supplier) {
            this.supplier = supplier;
        }

        public int slots() {
            return this == LONG || this == DOUBLE ? 2 : 1;
        }
    }

    private static enum MethodHandleKind {
        /*  0 */ RESERVED_0,
        /*  1 */ GET_FIELD,
        /*  2 */ GET_STATIC,
        /*  3 */ PUT_FIELD,
        /*  4 */ PUT_STATIC,
        /*  5 */ INVOKE_VIRTUAL,
        /*  6 */ INVOKE_STATIC,
        /*  7 */ INVOKE_SPECIAL,
        /*  8 */ NEW_INVOKE_SPECIAL,
        /*  9 */ INVOKE_INTERFACE
    }

    private static ThreadLocal<Map<String, Integer>> tmpRef = ThreadLocal.withInitial(HashMap::new);
    private static ThreadLocal<StringBuilder> sbRef = ThreadLocal.withInitial(StringBuilder::new);

    private ConstantPool(ConstantPoolEntry[] entries) {
        this.entries = entries;
        this.dedup = new HashMap<>();

        Map<String, Integer> tmp = tmpRef.get();
        tmp.clear();

        StringBuilder sb = sbRef.get();

        for (int i = 0; i < entries.length; i++) {
            ConstantPoolEntry entry = entries[i];
            if (entry == null) {
                continue;
            }
            sb.setLength(0);
            entry.toString(sb, entries);
            String s = sb.toString();
            Integer idx = tmp.get(s);
            if (idx != null) {
                dedup.put(i, idx);
            } else {
                tmp.put(s, i);
            }
        }
    }

    public static ConstantPool readConstantPool(DataInputStream r) throws IOException {
        ConstantPoolEntry[] cp = new ConstantPoolEntry[r.readUnsignedShort()];
        for (int slot = 1; slot < cp.length;) {
            ConstantType type = ConstantType.values()[r.readByte()];
            ConstantPoolEntry cpInfo = type.supplier.get().read(r);
            cp[slot] = cpInfo;
            slot += type.slots();
        }
        return new ConstantPool(cp);
    }

    int dedup(int index) {
        return dedup.getOrDefault(index, index);
    }

    public String getStringConstant(int index) {
        return ((ConstantUtf8Entry) entries[index]).str;
    }

    public String getClassShortName(int index) {
        if (index > 0) {
            int name_idx = ((ConstantClassEntry) entries[index]).name;
            String str = ((ConstantUtf8Entry) entries[name_idx]).str;
            int idx = str.lastIndexOf('/');
            return idx < 0
                    ? str
                    : str.substring(idx + 1);
        }
        return ""; // i.e. module-info.class
    }

    private static abstract class ConstantPoolEntry {

        public ConstantPoolEntry read(DataInputStream r) throws IOException {
            readImpl(r);
            return this;
        }

        protected abstract void readImpl(DataInputStream r) throws IOException;

        // This method is used exclusively for resolving CP duplicates.
        // In every implementation, the fisrt sb.append() is a marker to identify a type of the entry - just to avoid
        // collisions (like String vs utf8 constant or int vs long). These markers are not part of the hashcode and just
        // need to be unique.
        protected final void toString(StringBuilder sb, ConstantPoolEntry[] cp) {
            toStringImpl(sb, cp);
        }

        protected abstract void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp);
    }

    private static class ConstantClassEntry extends ConstantPoolEntry {

        private int name;

        @Override
        protected void readImpl(DataInputStream r) throws IOException {
            name = r.readUnsignedShort();
        }

        @Override
        protected void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp) {
            sb.append('0');
            cp[name].toString(sb, cp);
        }
    }

    private static abstract class ConstantRefEntry extends ConstantPoolEntry {

        protected int class_index;
        protected int name_and_type_index;

        @Override
        protected void readImpl(DataInputStream r) throws IOException {
            class_index = r.readUnsignedShort();
            name_and_type_index = r.readUnsignedShort();
        }
    }

    private static class ConstantFieldRefEntry extends ConstantRefEntry {

        @Override
        protected void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp) {
            sb.append('1');
            cp[class_index].toString(sb, cp);
            cp[name_and_type_index].toString(sb, cp);
        }
    }

    private static class ConstantMethodRefEntry extends ConstantRefEntry {

        @Override
        protected void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp) {
            sb.append('2');
            cp[class_index].toString(sb, cp);
            cp[name_and_type_index].toString(sb, cp);
        }
    }

    private static class ConstantInterfaceMethodRefEntry extends ConstantRefEntry {

        @Override
        protected void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp) {
            sb.append('3');
            cp[class_index].toString(sb, cp);
            cp[name_and_type_index].toString(sb, cp);
        }
    }

    private static class ConstantStringEntry extends ConstantPoolEntry {

        private int string_index;

        @Override
        protected void readImpl(DataInputStream r) throws IOException {
            string_index = r.readUnsignedShort();
        }

        @Override
        protected void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp) {
            sb.append('4');
            cp[string_index].toString(sb, cp);
        }
    }

    private static class ConstantIntegerEntry extends ConstantPoolEntry {

        private int value;

        @Override
        protected void readImpl(DataInputStream r) throws IOException {
            value = r.readInt();
        }

        @Override
        protected void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp) {
            sb.append('5');
            sb.append(value);
        }
    }

    private static class ConstantFloatEntry extends ConstantPoolEntry {

        private float value;

        @Override
        protected void readImpl(DataInputStream r) throws IOException {
            value = r.readFloat();
        }

        @Override
        protected void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp) {
            sb.append('6');
            sb.append(value);
        }
    }

    private static class ConstantLongEntry extends ConstantPoolEntry {

        private long value;

        @Override
        protected void readImpl(DataInputStream r) throws IOException {
            value = r.readLong();
        }

        @Override
        protected void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp) {
            sb.append('7');
            sb.append(value);
        }
    }

    private static class ConstantDoubleEntry extends ConstantPoolEntry {

        private double value;

        @Override
        protected void readImpl(DataInputStream r) throws IOException {
            value = r.readDouble();
        }

        @Override
        protected void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp) {
            sb.append('8');
            sb.append(value);
        }
    }

    private static class ConstantNameAndTypeEntry extends ConstantPoolEntry {

        private int name_index;
        private int descriptor_index;

        @Override
        protected void readImpl(DataInputStream r) throws IOException {
            name_index = r.readUnsignedShort();
            descriptor_index = r.readUnsignedShort();
        }

        @Override
        protected void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp) {
            sb.append('9');
            cp[name_index].toString(sb, cp);
            cp[descriptor_index].toString(sb, cp);
        }
    }

    private static class ConstantUtf8Entry extends ConstantPoolEntry {

        private String str;

        @Override
        protected void readImpl(DataInputStream r) throws IOException {
            int size = r.readUnsignedShort();
            int read = 0;
            byte[] bytes = new byte[size];
            while (read != size) {
                read += r.read(bytes, read, size - read);
            }
            str = new String(bytes);
        }

        @Override
        protected void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp) {
            sb.append('a');
            sb.append(str);
        }
    }

    private static class ConstantMethodHandleEntry extends ConstantPoolEntry {

        private MethodHandleKind reference_kind;
        private int reference_index;

        @Override
        protected void readImpl(DataInputStream r) throws IOException {
            reference_kind = MethodHandleKind.values()[r.readByte()];
            reference_index = r.readUnsignedShort();
        }

        @Override
        protected void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp) {
            sb.append('b');
            sb.append(reference_kind);
            cp[reference_index].toString(sb, cp);
        }
    }

    private static class ConstantMethodTypeEntry extends ConstantPoolEntry {

        private int descriptorIndex;

        @Override
        protected void readImpl(DataInputStream r) throws IOException {
            descriptorIndex = r.readUnsignedShort();
        }

        @Override
        protected void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp) {
            sb.append('c');
            cp[descriptorIndex].toString(sb, cp);
        }
    }

    private static class ConstantInvokeDynamicEntry extends ConstantPoolEntry {

        int bootstrap_method_attr_index;
        int name_and_type_index;

        @Override
        protected void readImpl(DataInputStream r) throws IOException {
            bootstrap_method_attr_index = r.readUnsignedShort();
            name_and_type_index = r.readUnsignedShort();
        }

        @Override
        protected void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp) {
            sb.append('d');
            sb.append(bootstrap_method_attr_index); // for now just use index of the BootstrapMethods
            cp[name_and_type_index].toString(sb, cp);
        }
    }

    private static abstract class ConstantNameEntry extends ConstantPoolEntry {

        protected int name_and_type_index;

        @Override
        protected void readImpl(DataInputStream r) throws IOException {
            name_and_type_index = r.readUnsignedShort();
        }
    }

    private static class ConstantModuleEntry extends ConstantNameEntry {

        @Override
        protected void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp) {
            sb.append('e');
            cp[name_and_type_index].toString(sb, cp);
        }
    }

    private static class ConstantPackageEntry extends ConstantNameEntry {

        @Override
        protected void toStringImpl(StringBuilder sb, ConstantPoolEntry[] cp) {
            sb.append('f');
            cp[name_and_type_index].toString(sb, cp);
        }
    }
}
