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
package com.azul.crs.json;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DummyJSONSerializer implements JSONSerializer {

    private final int tabs = 2;
    private boolean doFormat = Boolean.getBoolean("com.azul.crs.client.enablePrettyPrint");
    private int level = 0;

    public DummyJSONSerializer() {}

    public DummyJSONSerializer(boolean prettyPrint) {
        doFormat |= prettyPrint;
    }

    void formatEnter(PrintStream output) {
        if (!doFormat) return;
        ++level;
    }

    void formatMid(PrintStream output) throws IOException {
        if (!doFormat) return;
        output.append("\n");
        for (int i = 0; i < (tabs * level); ++i) output.append(' ');
    }

    void formatLeave(PrintStream output) {
        if (!doFormat) return;
        --level;
    }

    private static boolean fieldFilter(Field field) {
        if (Modifier.isTransient(field.getModifiers())) return false;
        if (Modifier.isStatic(field.getModifiers())) return false;

        if ("hash".equals(field.getName())) {
            // do not print String.hash
            if ("java.lang.String".equals(field.getDeclaringClass().getName())) return false;
        }
        return true;
    }

    void serializeEnum(PrintStream output, Enum e) throws IOException {
        output.append('"').append(e.name()).append('"');
    }

    private static boolean isNumberOrBoolean(Object o) {
        return o instanceof Number ||
                o instanceof Boolean;
    }

    void serializeNumberOrBoolean(PrintStream output, Object o) throws IOException {
        output.append(o.toString());
    }

    private static final String escape3Text = "\"\\/\b\f\n\r\t";
    private static final Pattern escape3Pattern = Pattern.compile("[\"\\\\/\b\f\n\r\t\u0000-\u001f]");
    private static final String[] escape3Replacement = {
            "\\\\\"", "\\\\\\\\", "\\\\/", "\\\\b", "\\\\f", "\\\\n", "\\\\r", "\\\\t"
    };

    void serializeString(PrintStream output, String o) throws IOException {
        Matcher m = escape3Pattern.matcher(o);
        StringBuffer sb = new StringBuffer();
        output.append('"');
        while (m.find()) {
            char c = m.group().charAt(0);
            int index = escape3Text.indexOf(c);
            sb.setLength(0);
            m.appendReplacement(sb, index >= 0 ? escape3Replacement[index]
                                               : String.format("\\\\u%04x", (int)c));
            output.append(sb);
        }
        sb.setLength(0);
        m.appendTail(sb);
        output.append(sb);
        output.append('"');
    }

    void serializeMap(PrintStream output, Map m) throws IOException {
        output.append('{');
        formatEnter(output);

        int count = 0;
        for (Object key : m.keySet()) {
            Object value = m.get(key);
            if (value == null) continue;

            if (count > 0) {
                output.append(',');
            }

            formatMid(output);
            ++count;
            serialize(output, key);
            output.append(':');
            serialize(output, value);
        }

        formatLeave(output);
        if (count > 0) formatMid(output);
        output.append('}');
    }

    void serializeCollection(PrintStream output, Collection c) throws IOException {
        output.append('[');
        formatEnter(output);

        int count = 0;
        for (Object o : c) {
            if (count > 0) {
                output.append(',');
            }

            formatMid(output);
            ++count;
            serialize(output, o);
        }

        formatLeave(output);
        if (count > 0) formatMid(output);
        output.append(']');
    }

    private void printFieldName(PrintStream output, Field field) throws IOException {
        serializeString(output, field.getName());
    }

    void serializeObject(PrintStream output, Object o) throws IOException {
        if (null == o) {
            output.append("null");
            return;
        }

        output.append('{');

        formatEnter(output);

        Class<?> oc = o.getClass();

        int count = 0;
        for (Field field : oc.getDeclaredFields()) {
            // Check which fields to go over here
            if (!fieldFilter(field)) continue;

            field.setAccessible(true);
            Object value = null;

            try {
                value = field.get(o);

            } catch (IllegalAccessException e) {
                e.printStackTrace(System.err);
                if (true) throw new RuntimeException("Tykwa: ", e);
                // do not print that field?
                continue;
            }

            if (value == null)
                continue;

            if (count > 0) {
                output.append(',');
            }

            formatMid(output);
            printFieldName(output, field);
            output.append(':');
            serialize(output, value);
            ++count;
        }

        formatLeave(output);
        if (count > 0) formatMid(output);
        output.append('}');
    }

    @Override
    public void serialize(PrintStream output, Object obj) throws IOException {
        if (null == obj) {
            return;
        }

        if (obj instanceof String) {
            serializeString(output, (String) obj);
        } else if (obj instanceof Enum) {
            serializeEnum(output, (Enum) obj);
        } else if (isNumberOrBoolean(obj)) {
            serializeNumberOrBoolean(output, obj);
        } else if (obj instanceof Map) {
            serializeMap(output, (Map) obj);
        } else if (obj instanceof Collection) {
            serializeCollection(output, (Collection) obj);
        } else {
            serializeObject(output, obj);
        }
    }
}
