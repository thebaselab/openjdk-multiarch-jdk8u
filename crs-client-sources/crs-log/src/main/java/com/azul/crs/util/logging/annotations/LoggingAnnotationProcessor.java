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
package com.azul.crs.util.logging.annotations;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.Element;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import com.azul.crs.util.logging.LogChannel;
import java.util.Properties;

@SupportedAnnotationTypes("com.azul.crs.util.logging.LogChannel")
public final class LoggingAnnotationProcessor extends AbstractProcessor {

    private static final String REGISTRY = "META-INF/crslog.channels.cfg";
    private static final Properties MAP = new Properties();

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnv) {
        if (roundEnv.errorRaised()) {
            return false;
        }

        if (!roundEnv.processingOver()) {
            for (Element e : roundEnv.getElementsAnnotatedWith(LogChannel.class)) {
                if (e instanceof TypeElement) {
                    TypeElement te = (TypeElement) e;
                    MAP.put(te.getQualifiedName().toString(), te.getAnnotation(LogChannel.class).value() + ":" + te.getAnnotation(LogChannel.class).lowestUpstreamLevel());
                }
            }
            return true;
        }

        try {
            Filer filer = processingEnv.getFiler();
            FileObject f = filer.createResource(StandardLocation.CLASS_OUTPUT, "", REGISTRY);
            try (PrintWriter w = new PrintWriter(f.openWriter())) {
                MAP.store(w, null);
            }
        } catch (IOException ex) {
            Logger.getLogger(LoggingAnnotationProcessor.class.getName()).log(Level.SEVERE, null, ex);
        }

        return true;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_6;
    }
}
