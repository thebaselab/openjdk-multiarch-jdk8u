/*
 * Copyright 2022 Azul Systems,
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

import java.util.function.Predicate;

/**
 * The most simplistic regular expressions. In fact, only the following is supported: <ul>
 * <li> starts from (syntax: "myPrefix*") </li>
 * <li> ends with (syntax: "*myPostfix") </li>
 * <li> contains (syntax: "*subString*") </li>
 * <li> any string (syntax: "*") </li>
 * </ul>
 */
public final class SimpleRegEx implements Predicate<String> {

    /**
     * initial pattern
     */
    private final String pattern;
    /**
     * text to compare with
     */
    private final String text;
    /**
     * function that performs matching
     */
    private final Predicate<String> matcher;

    public SimpleRegEx(String pattern) throws IllegalArgumentException {
        this.pattern = pattern;
        if (pattern.equals("*")) {
            text = "";
            matcher = this::matchAny;
        } else if (pattern.startsWith("*")) {
            if (pattern.endsWith("*")) {
                matcher = this::matchContains;
                text = pattern.substring(1, pattern.length() - 1);
            } else {
                matcher = this::matchEndsWith;
                text = pattern.substring(1);
            }
        } else if (pattern.endsWith("*")) {
            matcher = this::matchStartsWith;
            text = pattern.substring(0, pattern.length() - 1);
        } else {
            matcher = this::matchExact;
            text = pattern;
        }
        if (text.contains("*")) {
            throw new IllegalArgumentException(errorMessage(pattern));
        }
    }

    private static String errorMessage(String pattern) {
        return "Invalid regular expression: " + pattern;
    }

    @Override
    public boolean test(String text) {
        return (text != null) && matcher.test(text);
    }

    public String getPattern() {
        return pattern;
    }

    public boolean matchesAll() {
        return "*".equals(pattern);
    }

    private boolean matchExact(String text) {
        return this.text.equals(text);
    }

    private boolean matchAny(String text) {
        return true;
    }

    private boolean matchStartsWith(String text) {
        return text.startsWith(this.text);
    }

    private boolean matchEndsWith(String text) {
        return text.endsWith(this.text);
    }

    private boolean matchContains(String text) {
        return text.contains(this.text);
    }
}
