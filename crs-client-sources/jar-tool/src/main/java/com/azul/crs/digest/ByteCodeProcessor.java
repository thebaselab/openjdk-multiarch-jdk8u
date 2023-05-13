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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;

/*package*/ final class ByteCodeProcessor {

    private final ByteBuffer buffer = ByteBuffer.allocate(20);
    private final ConstantPoolMapper cp;
    private boolean wide = false;

    ByteCodeProcessor(ConstantPool constantPool) {
        this.cp = new ConstantPoolMapper(constantPool);
    }

    private static enum OpCodeCase {
        SWITCH_DEFAULT, TABLESWITCH, LOOKUPSWITCH, DISCARD_SHORT_IMMEDIATE,
        DISCARD_INT_IMMEDIATE, DISCARD_IMMEDIATE_REF, WIDE, NEWARRAY, CONSTANT_FIELDREF,
        CONSTANT_CLASS, INVOKESPECIAL, INVOKEVIRTUAL, INVOKEINTERFACE, INVOKEDYNAMIC,
        LDC_W, LDC, ANEWARRAY, MULTIANEWARRAY, IINC, NO_ARG
    }

    private static final T[] NONE = new T[0];

    private static enum T {
        BYTE,
        SHORT,
        INT;
    }

    static enum OpCode {
        /*   0 */ NOP,
        /*   1 */ ACONST_NULL,
        /*   2 */ ICONST_M1,
        /*   3 */ ICONST_0,
        /*   4 */ ICONST_1,
        /*   5 */ ICONST_2,
        /*   6 */ ICONST_3,
        /*   7 */ ICONST_4,
        /*   8 */ ICONST_5,
        /*   9 */ LCONST_0,
        /*  10 */ LCONST_1,
        /*  11 */ FCONST_0,
        /*  12 */ FCONST_1,
        /*  13 */ FCONST_2,
        /*  14 */ DCONST_0,
        /*  15 */ DCONST_1,
        /*  16 */ BIPUSH(T.BYTE),
        /*  17 */ SIPUSH(T.SHORT),
        /*  18 */ LDC(OpCodeCase.LDC, T.BYTE),
        /*  19 */ LDC_W(LDC, OpCodeCase.LDC_W, T.SHORT),
        /*  20 */ LDC2_W(LDC, OpCodeCase.LDC_W, T.SHORT),
        /*  21 */ ILOAD(OpCodeCase.DISCARD_IMMEDIATE_REF, T.BYTE),
        /*  22 */ LLOAD(OpCodeCase.DISCARD_IMMEDIATE_REF, T.BYTE),
        /*  23 */ FLOAD(OpCodeCase.DISCARD_IMMEDIATE_REF, T.BYTE),
        /*  24 */ DLOAD(OpCodeCase.DISCARD_IMMEDIATE_REF, T.BYTE),
        /*  25 */ ALOAD(OpCodeCase.DISCARD_IMMEDIATE_REF, T.BYTE),
        /*  26 */ ILOAD_0(ILOAD, OpCodeCase.NO_ARG),
        /*  27 */ ILOAD_1(ILOAD, OpCodeCase.NO_ARG),
        /*  28 */ ILOAD_2(ILOAD, OpCodeCase.NO_ARG),
        /*  29 */ ILOAD_3(ILOAD, OpCodeCase.NO_ARG),
        /*  30 */ LLOAD_0(LLOAD, OpCodeCase.NO_ARG),
        /*  31 */ LLOAD_1(LLOAD, OpCodeCase.NO_ARG),
        /*  32 */ LLOAD_2(LLOAD, OpCodeCase.NO_ARG),
        /*  33 */ LLOAD_3(LLOAD, OpCodeCase.NO_ARG),
        /*  34 */ FLOAD_0(FLOAD, OpCodeCase.NO_ARG),
        /*  35 */ FLOAD_1(FLOAD, OpCodeCase.NO_ARG),
        /*  36 */ FLOAD_2(FLOAD, OpCodeCase.NO_ARG),
        /*  37 */ FLOAD_3(FLOAD, OpCodeCase.NO_ARG),
        /*  38 */ DLOAD_0(DLOAD, OpCodeCase.NO_ARG),
        /*  39 */ DLOAD_1(DLOAD, OpCodeCase.NO_ARG),
        /*  40 */ DLOAD_2(DLOAD, OpCodeCase.NO_ARG),
        /*  41 */ DLOAD_3(DLOAD, OpCodeCase.NO_ARG),
        /*  42 */ ALOAD_0(ALOAD, OpCodeCase.NO_ARG),
        /*  43 */ ALOAD_1(ALOAD, OpCodeCase.NO_ARG),
        /*  44 */ ALOAD_2(ALOAD, OpCodeCase.NO_ARG),
        /*  45 */ ALOAD_3(ALOAD, OpCodeCase.NO_ARG),
        /*  46 */ IALOAD,
        /*  47 */ LALOAD,
        /*  48 */ FALOAD,
        /*  49 */ DALOAD,
        /*  50 */ AALOAD,
        /*  51 */ BALOAD,
        /*  52 */ CALOAD,
        /*  53 */ SALOAD,
        /*  54 */ ISTORE(OpCodeCase.DISCARD_IMMEDIATE_REF, T.BYTE),
        /*  55 */ LSTORE(OpCodeCase.DISCARD_IMMEDIATE_REF, T.BYTE),
        /*  56 */ FSTORE(OpCodeCase.DISCARD_IMMEDIATE_REF, T.BYTE),
        /*  57 */ DSTORE(OpCodeCase.DISCARD_IMMEDIATE_REF, T.BYTE),
        /*  58 */ ASTORE(OpCodeCase.DISCARD_IMMEDIATE_REF, T.BYTE),
        /*  59 */ ISTORE_0(ISTORE, OpCodeCase.NO_ARG),
        /*  60 */ ISTORE_1(ISTORE, OpCodeCase.NO_ARG),
        /*  61 */ ISTORE_2(ISTORE, OpCodeCase.NO_ARG),
        /*  62 */ ISTORE_3(ISTORE, OpCodeCase.NO_ARG),
        /*  63 */ LSTORE_0(LSTORE, OpCodeCase.NO_ARG),
        /*  64 */ LSTORE_1(LSTORE, OpCodeCase.NO_ARG),
        /*  65 */ LSTORE_2(LSTORE, OpCodeCase.NO_ARG),
        /*  66 */ LSTORE_3(LSTORE, OpCodeCase.NO_ARG),
        /*  67 */ FSTORE_0(FSTORE, OpCodeCase.NO_ARG),
        /*  68 */ FSTORE_1(FSTORE, OpCodeCase.NO_ARG),
        /*  69 */ FSTORE_2(FSTORE, OpCodeCase.NO_ARG),
        /*  70 */ FSTORE_3(FSTORE, OpCodeCase.NO_ARG),
        /*  71 */ DSTORE_0(DSTORE, OpCodeCase.NO_ARG),
        /*  72 */ DSTORE_1(DSTORE, OpCodeCase.NO_ARG),
        /*  73 */ DSTORE_2(DSTORE, OpCodeCase.NO_ARG),
        /*  74 */ DSTORE_3(DSTORE, OpCodeCase.NO_ARG),
        /*  75 */ ASTORE_0(ASTORE, OpCodeCase.NO_ARG),
        /*  76 */ ASTORE_1(ASTORE, OpCodeCase.NO_ARG),
        /*  77 */ ASTORE_2(ASTORE, OpCodeCase.NO_ARG),
        /*  78 */ ASTORE_3(ASTORE, OpCodeCase.NO_ARG),
        /*  79 */ IASTORE,
        /*  80 */ LASTORE,
        /*  81 */ FASTORE,
        /*  82 */ DASTORE,
        /*  83 */ AASTORE,
        /*  84 */ BASTORE,
        /*  85 */ CASTORE,
        /*  86 */ SASTORE,
        /*  87 */ POP,
        /*  88 */ POP2,
        /*  89 */ DUP,
        /*  90 */ DUP_X1,
        /*  91 */ DUP_X2,
        /*  92 */ DUP2,
        /*  93 */ DUP2_X1,
        /*  94 */ DUP2_X2,
        /*  95 */ SWAP,
        /*  96 */ IADD,
        /*  97 */ LADD,
        /*  98 */ FADD,
        /*  99 */ DADD,
        /* 100 */ ISUB,
        /* 101 */ LSUB,
        /* 102 */ FSUB,
        /* 103 */ DSUB,
        /* 104 */ IMUL,
        /* 105 */ LMUL,
        /* 106 */ FMUL,
        /* 107 */ DMUL,
        /* 108 */ IDIV,
        /* 109 */ LDIV,
        /* 110 */ FDIV,
        /* 111 */ DDIV,
        /* 112 */ IREM,
        /* 113 */ LREM,
        /* 114 */ FREM,
        /* 115 */ DREM,
        /* 116 */ INEG,
        /* 117 */ LNEG,
        /* 118 */ FNEG,
        /* 119 */ DNEG,
        /* 120 */ ISHL,
        /* 121 */ LSHL,
        /* 122 */ ISHR,
        /* 123 */ LSHR,
        /* 124 */ IUSHR,
        /* 125 */ LUSHR,
        /* 126 */ IAND,
        /* 127 */ LAND,
        /* 128 */ IOR,
        /* 129 */ LOR,
        /* 130 */ IXOR,
        /* 131 */ LXOR,
        /* 132 */ IINC(OpCodeCase.IINC, T.BYTE, T.BYTE),
        /* 133 */ I2L,
        /* 134 */ I2F,
        /* 135 */ I2D,
        /* 136 */ L2I,
        /* 137 */ L2F,
        /* 138 */ L2D,
        /* 139 */ F2I,
        /* 140 */ F2L,
        /* 141 */ F2D,
        /* 142 */ D2I,
        /* 143 */ D2L,
        /* 144 */ D2F,
        /* 145 */ I2B,
        /* 146 */ I2C,
        /* 147 */ I2S,
        /* 148 */ LCMP,
        /* 149 */ FCMPL,
        /* 150 */ FCMPG,
        /* 151 */ DCMPL,
        /* 152 */ DCMPG,
        /* 153 */ IFEQ(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 154 */ IFNE(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 155 */ IFLT(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 156 */ IFGE(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 157 */ IFGT(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 158 */ IFLE(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 159 */ IF_ICMPEQ(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 160 */ IF_ICMPNE(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 161 */ IF_ICMPLT(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 162 */ IF_ICMPGE(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 163 */ IF_ICMPGT(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 164 */ IF_ICMPLE(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 165 */ IF_ACMPEQ(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 166 */ IF_ACMPNE(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 167 */ GOTO(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 168 */ JSR(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 169 */ RET(OpCodeCase.DISCARD_IMMEDIATE_REF, T.BYTE),
        /* 170 */ TABLESWITCH(OpCodeCase.TABLESWITCH),
        /* 171 */ LOOKUPSWITCH(OpCodeCase.LOOKUPSWITCH),
        /* 172 */ IRETURN,
        /* 173 */ LRETURN,
        /* 174 */ FRETURN,
        /* 175 */ DRETURN,
        /* 176 */ ARETURN,
        /* 177 */ RETURN,
        /* 178 */ GETSTATIC(OpCodeCase.CONSTANT_FIELDREF, T.SHORT),
        /* 179 */ PUTSTATIC(OpCodeCase.CONSTANT_FIELDREF, T.SHORT),
        /* 180 */ GETFIELD(OpCodeCase.CONSTANT_FIELDREF, T.SHORT),
        /* 181 */ PUTFIELD(OpCodeCase.CONSTANT_FIELDREF, T.SHORT),
        /* 182 */ INVOKEVIRTUAL(OpCodeCase.INVOKEVIRTUAL, T.SHORT),
        /* 183 */ INVOKESPECIAL(OpCodeCase.INVOKESPECIAL, T.SHORT),
        /* 184 */ INVOKESTATIC(OpCodeCase.INVOKESPECIAL, T.SHORT),
        /* 185 */ INVOKEINTERFACE(OpCodeCase.INVOKEINTERFACE, T.SHORT, T.BYTE, T.BYTE),
        /* 186 */ INVOKEDYNAMIC(OpCodeCase.INVOKEDYNAMIC, T.SHORT, T.BYTE, T.BYTE),
        /* 187 */ NEW(OpCodeCase.CONSTANT_CLASS, T.SHORT),
        /* 188 */ NEWARRAY(OpCodeCase.NEWARRAY, T.BYTE),
        /* 189 */ ANEWARRAY(OpCodeCase.ANEWARRAY, T.SHORT),
        /* 190 */ ARRAYLENGTH,
        /* 191 */ ATHROW,
        /* 192 */ CHECKCAST(OpCodeCase.CONSTANT_CLASS, T.SHORT),
        /* 193 */ INSTANCEOF(OpCodeCase.CONSTANT_CLASS, T.SHORT),
        /* 194 */ MONITORENTER,
        /* 195 */ MONITOREXIT,
        /* 196 */ WIDE(OpCodeCase.WIDE),
        /* 197 */ MULTIANEWARRAY(OpCodeCase.MULTIANEWARRAY, T.SHORT, T.BYTE),
        /* 198 */ IFNULL(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 199 */ IFNONNULL(OpCodeCase.DISCARD_SHORT_IMMEDIATE, T.SHORT),
        /* 200 */ GOTO_W(GOTO, OpCodeCase.DISCARD_INT_IMMEDIATE, T.INT),
        /* 201 */ JSR_W(JSR, OpCodeCase.DISCARD_INT_IMMEDIATE, T.INT),
        /* 202 */ BREAKPOINT,
        /* 203 */ LDC_QUICK,
        /* 204 */ LDC_W_QUICK(LDC_QUICK),
        /* 205 */ LDC2_W_QUICK(LDC_QUICK),
        /* 206 */ GETFIELD_QUICK,
        /* 207 */ PUTFIELD_QUICK,
        /* 208 */ GETFIELD2_QUICK,
        /* 209 */ PUTFIELD2_QUICK,
        /* 210 */ GETSTATIC_QUICK,
        /* 211 */ PUTSTATIC_QUICK,
        /* 212 */ GETSTATIC2_QUICK,
        /* 213 */ PUTSTATIC2_QUICK,
        /* 214 */ INVOKEVIRTUAL_QUICK,
        /* 215 */ INVOKENONVIRTUAL_QUICK,
        /* 216 */ INVOKESUPER_QUICK,
        /* 217 */ INVOKESTATIC_QUICK,
        /* 218 */ INVOKEINTERFACE_QUICK,
        /* 219 */ INVOKEVIRTUALOBJECT_QUICK,
        /* 220 */ RESERVED_220,
        /* 221 */ NEW_QUICK,
        /* 222 */ ANEWARRAY_QUICK,
        /* 223 */ MULTIANEWARRAY_QUICK,
        /* 224 */ CHECKCAST_QUICK,
        /* 225 */ INSTANCEOF_QUICK,
        /* 226 */ INVOKEVIRTUAL_QUICK_W(INVOKEVIRTUAL_QUICK),
        /* 227 */ GETFIELD_QUICK_W(GETFIELD_QUICK),
        /* 228 */ PUTFIELD_QUICK_W(PUTFIELD_QUICK),
        /* 229 */ RESERVED_229,
        /* 230 */ RESERVED_230,
        /* 231 */ RESERVED_231,
        /* 232 */ RESERVED_232,
        /* 233 */ RESERVED_233,
        /* 234 */ RESERVED_234,
        /* 235 */ RESERVED_235,
        /* 236 */ RESERVED_236,
        /* 237 */ RESERVED_237,
        /* 238 */ RESERVED_238,
        /* 239 */ RESERVED_239,
        /* 240 */ RESERVED_240,
        /* 241 */ RESERVED_241,
        /* 242 */ RESERVED_242,
        /* 243 */ RESERVED_243,
        /* 244 */ RESERVED_244,
        /* 245 */ RESERVED_245,
        /* 246 */ RESERVED_246,
        /* 247 */ RESERVED_247,
        /* 248 */ RESERVED_248,
        /* 249 */ RESERVED_249,
        /* 250 */ RESERVED_250,
        /* 251 */ RESERVED_251,
        /* 252 */ RESERVED_252,
        /* 253 */ RESERVED_253,
        /* 254 */ IMPDEP1,
        /* 255 */ IMPDEP2;

        final OpCode replaceWith;
        final OpCodeCase switchCase;
        final T[] operandTypes;

        private final static OpCode[] opCodes = OpCode.values();

        static OpCode getOpCode(int i) {
            return opCodes[i];
        }

        private OpCode() {
            this.replaceWith = this;
            this.switchCase = OpCodeCase.SWITCH_DEFAULT;
            this.operandTypes = NONE;
        }

        private OpCode(T... operands) {
            this.replaceWith = this;
            this.switchCase = OpCodeCase.SWITCH_DEFAULT;
            this.operandTypes = operands;
        }

        private OpCode(OpCode replaceWith) {
            this.replaceWith = replaceWith;
            this.switchCase = OpCodeCase.SWITCH_DEFAULT;
            this.operandTypes = NONE;
        }

        private OpCode(OpCodeCase switchCase) {
            this.replaceWith = this;
            this.switchCase = switchCase;
            this.operandTypes = NONE;
        }

        private OpCode(OpCodeCase switchCase, T... operandTypes) {
            this.replaceWith = this;
            this.switchCase = switchCase;
            this.operandTypes = operandTypes;
        }

        private OpCode(OpCode replaceWith, OpCodeCase switchCase) {
            this.replaceWith = replaceWith;
            this.switchCase = switchCase;
            this.operandTypes = NONE;
        }

        private OpCode(OpCode replaceWith, OpCodeCase switchCase, T... operandTypes) {
            this.replaceWith = replaceWith;
            this.switchCase = switchCase;
            this.operandTypes = operandTypes;
        }
    }

    /*
     * The code below is inspired by the org.apache.bcel.classfile.Utility.codeToString.
     *
     * Basically this is a disassembling logic with several details removed from the output. As we want to have hashcode
     * that is agnostic to shading, instead of using real constants their (re-mapped) indexes are used. CP indexes
     * remapping is done so that CP indexes are assigned in the order of appearance in the code (all methods are sorted
     * before processing each class). Also, as shading re-orders constants, the indexes may change in sizes (1-byte vs
     * 2-bytes and vise-versa). That causes changes in the bytecode for instructions like LDC, GOTO, etc (they are
     * replaced with LDC_W, GOTO_W analogues) - so in the output all wide instructions are replaced with correcponding
     * 'regular' codes.
     *
     * For the same reason offsets for JUMPs are also ommited from the output.
     */
    void processMethodByteCode(final CountingInputStream bytes, final Digest hash) throws IOException {
        int low;
        int high;
        int npairs;
        int index;
        int constant;

        final OpCode opcode = OpCode.getOpCode(bytes.readUnsignedByte());

        buffer.clear();
        buffer.putShort((short) opcode.replaceWith.ordinal());

        // Special case: Skip (0-3) padding bytes, i.e., the following bytes are 4-byte-aligned
        if ((opcode == OpCode.TABLESWITCH) || (opcode == OpCode.LOOKUPSWITCH)) {
            int remainder = bytes.position() % 4;
            int no_pad_bytes = (remainder == 0) ? 0 : 4 - remainder;
            for (int i = 0; i < no_pad_bytes; i++) {
                byte b;
                if ((b = bytes.readByte()) != 0) {
                    System.err.println("Warning: Padding byte != 0 in " + opcode + ":" + b);
                }
            }
            // Both cases have a field default_offset in common
            // we do not report it - so just discard it
            bytes.readInt();
        }

        switch (opcode.switchCase) {
            case TABLESWITCH:
                // Table switch has variable length arguments.
                // Read the table, but do not report jump destinations...
                low = bytes.readInt();
                high = bytes.readInt();
                buffer.putInt(low);
                buffer.putInt(high);
                // read-out jump table
                for (int i = 0; i < (high - low + 1) << 2; i++) {
                    bytes.read();
                }
                break;
            case LOOKUPSWITCH:
                // Lookup switch has variable length arguments.
                // Read the table, but do not report jump destinations...
                npairs = bytes.readInt();
                buffer.putInt(npairs);
                // read-out match and jump tables
                for (int i = 0; i < npairs << 3; i++) {
                    bytes.read();
                }
                break;
            case DISCARD_SHORT_IMMEDIATE:
                bytes.readShort();
                break;
            case DISCARD_INT_IMMEDIATE:
                bytes.readInt();
                break;
            case DISCARD_IMMEDIATE_REF:
                if (wide) {
                    bytes.readUnsignedShort();
                    wide = false;
                } else {
                    bytes.readUnsignedByte();
                }
                break;
            /*
             * Remember wide byte which is used to form a 16-bit address in the
             * following instruction. Relies on that the method is called again with
             * the following opcode.
             */
            case WIDE:
                wide = true;
                break;
            case NEWARRAY:
                buffer.put(bytes.readByte());
                break;
            case CONSTANT_FIELDREF:
                index = bytes.readUnsignedShort();
                buffer.putInt(cp.mapIndex(index));
                break;
            case CONSTANT_CLASS:
                index = bytes.readUnsignedShort();
                buffer.putInt(cp.mapIndex(index));
                break;
            case INVOKESPECIAL:
                index = bytes.readUnsignedShort();
                buffer.putInt(cp.mapIndex(index));
                break;
            case INVOKEVIRTUAL:
                index = bytes.readUnsignedShort();
                buffer.putInt(cp.mapIndex(index));
                break;
            case INVOKEINTERFACE:
                index = bytes.readUnsignedShort();
                buffer.putInt(cp.mapIndex(index));
                buffer.putInt(bytes.readUnsignedByte()); // nargs, historical, redundant
                buffer.putInt(bytes.readUnsignedByte()); // Last byte is a reserved space
                break;
            case INVOKEDYNAMIC:
                index = bytes.readUnsignedShort();
                buffer.putInt(cp.mapIndex(index));
                buffer.putInt(bytes.readUnsignedByte()); // Thrid byte is a reserved space
                buffer.putInt(bytes.readUnsignedByte()); // Last byte is a reserved space
                break;
            case LDC_W:
                index = bytes.readUnsignedShort();
                buffer.putInt(cp.mapIndex(index));
                break;
            case LDC:
                index = bytes.readUnsignedByte();
                buffer.putInt(cp.mapIndex(index));
                break;
            case ANEWARRAY:
                index = bytes.readUnsignedShort();
                buffer.putInt(cp.mapIndex(index));
                break;
            case MULTIANEWARRAY:
                index = bytes.readUnsignedShort();
                buffer.putInt(bytes.readUnsignedByte()); // dimensions
                buffer.putInt(cp.mapIndex(index));
                break;
            case IINC:
                if (wide) {
                    bytes.readUnsignedShort();
                    constant = bytes.readShort();
                    wide = false;
                } else {
                    bytes.readUnsignedByte();
                    constant = bytes.readByte();
                }
                buffer.putInt(constant);
                break;
            case NO_ARG:
                break;
            default:
                for (T operand : opcode.operandTypes) {
                    switch (operand) {
                        case BYTE:
                            buffer.put(bytes.readByte());
                            break;
                        case SHORT:
                            buffer.putShort(bytes.readShort());
                            break;
                        case INT:
                            buffer.putInt(bytes.readInt());
                            break;
                        default:
                            throw new IllegalStateException("Unreachable default case reached!");
                    }
                }
        }
        hash.update(buffer);
    }

    private static final class ConstantPoolMapper extends HashMap<Integer, Integer> {

        private final ConstantPool cp;
        private int counter = 0;

        public ConstantPoolMapper(ConstantPool cp) {
            this.cp = cp;
        }

        public int mapIndex(int index) {
            index = cp.dedup(index);
            return computeIfAbsent(index, k -> counter++);
        }
    }
}
