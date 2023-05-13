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
package com.azul.crs.json.buildtime;

import com.azul.crs.json.CompileJsonSerializer;
import com.azul.crs.json.JsonProperty;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import java.io.*;
import java.util.*;

public class JSONAnnotaitonProcessor extends AbstractProcessor {
    JavaFileObject out;
    Elements elementUtils;
    Types typeUtils;
    Map<String, Map<String, String[]>> fieldMapping;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        try {
            out = env.getFiler().createSourceFile("JSONStaticSerializer");
        } catch (IOException e) {
            e.printStackTrace();
        }
        typeUtils = env.getTypeUtils();
        elementUtils = env.getElementUtils();
    }

    private void putFieldRenaming(String klass, String field, String renameTo, String getterName) {
        if (fieldMapping == null) fieldMapping = new HashMap<>();
        if (fieldMapping.get(klass) == null) fieldMapping.put(klass, new HashMap<>());
        String[] pair = new String[2];
        pair[0] = "".equals(renameTo) ? null : renameTo;
        pair[1] = "".equals(getterName) ? null : getterName;
        fieldMapping.get(klass).put(field, pair);
    }

    private String[] getFieldRenaming(String klass, String field) {
        if (fieldMapping == null) return null;
        if (fieldMapping.get(klass) == null) return null;
        return fieldMapping.get(klass).get(field);
    }

    private String getFieldName(String klass, String field) {
        String[] name = getFieldRenaming(klass, field);
        if (null == name || null == name[0]) return field;
        return name[0];
    }

    private String getGetterName(String klass, String field) {
        String[] name = getFieldRenaming(klass, field);
        if (null == name || null == name[1]) return getDefaultAccessorName(field);
        return name[1];
    }

    private void addFieldsClasses(Map<String, Element> whereToPut, Element klass) {
        if (whereToPut.containsKey(getClassName(klass))) return;

        whereToPut.put(getClassName(klass), klass);

        klass.getEnclosedElements().forEach(ee -> {
            ElementKind kind = ee.getKind();
            if (kind == ElementKind.CLASS) {
                addFieldsClasses(whereToPut, ee);
            }
        });
    }

    private String getDefaultAccessorName(String fieldName) {
        return "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1) + "()";
    }

    private static String getNameAsString(Name name) {
        return String.valueOf(name);
    }
    private static String typeElementGetName(TypeElement te) {
        return getNameAsString(te.getQualifiedName());
    }
    private static String getElementName(Element e) {
        return getNameAsString(e.getSimpleName());
    }
    private String getName(Element e) {
        if (e instanceof TypeElement) return getClassName(e);
        return getElementName(e);
    }


    private String getClassNameByTypeMirror(TypeMirror t) {
        return t.toString().replaceAll("\\<.*$", "");
    }

    private Class getClassByTypeMirror(TypeMirror t) {
        // remove diamond<T> suffix from typename
        String typeName = getClassNameByTypeMirror(t);
        try {
            return Class.forName(typeName);
        } catch (ClassNotFoundException classNotFoundException) {
            // non-visible class -- probably not system one (java.lang.*)
            // do nothing here
            return null;
        }
    }

    private boolean isElementHavingNumberOrBooleanType(Element e) {
        if (e.asType().getKind().isPrimitive())
            return true;
        return isElementExtending(e, Number.class) || isElementExtending(e, Boolean.class);
    }

    private boolean isPresentingSameType(TypeMirror t, Class c) {
        // doing forName to workaround template types
        Class c2 = getClassByTypeMirror(t);

        if (c != null && c2 != null && c.isAssignableFrom(c2) && c2.isAssignableFrom(c))
            return true;

        // check non bootstrap classes
        return c != null && c.getCanonicalName().equals(getClassNameByTypeMirror(t));
    }

    private boolean isElementExtending(Element e, Class s) {
        if (isPresentingSameType(e.asType(), s)) return true;

        for (TypeMirror directSupertype : typeUtils.directSupertypes(e.asType())) {
            if (directSupertype instanceof DeclaredType) {
                return isElementExtending(((DeclaredType) directSupertype).asElement(), s);
            } else {
                if (isPresentingSameType(e.asType(), s)) return true;
            }
        }

        return false;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations,
                           RoundEnvironment roundEnv) {
        if (annotations.size() == 0)
            return false;

        Map<String, Element> classesToProcess = new HashMap<>();

        annotations.forEach( annotation -> {
            if (CompileJsonSerializer.class.getName().equals(getClassName(annotation))) {
                roundEnv.getElementsAnnotatedWith(annotation).forEach(e -> {
                    classesToProcess.put(getClassName(e), e);
                });
            }

            if (JsonProperty.class.getName().equals(annotation.toString())) {
                roundEnv.getElementsAnnotatedWith(annotation).forEach(e -> {
                    String klassName = getClassName(e.getEnclosingElement());
                    String fieldName = getName(e);
                    String renameName = e.getAnnotation(JsonProperty.class).value();
                    String getterName = e.getAnnotation(JsonProperty.class).getterName() + "()";
                    boolean noGetterPresented = e.getAnnotation(JsonProperty.class).noGetter();

                    if (noGetterPresented) {
                        getterName = fieldName;
                    }

                    Element ee = e.getEnclosingElement();
                    classesToProcess.put(getClassName(ee), ee);

                    putFieldRenaming(klassName, fieldName, renameName, getterName);
                });
            }
        });

        Map<String, Element> innerClassesToProccess = new HashMap<>();
        classesToProcess.forEach((s, e) -> {
            addFieldsClasses(innerClassesToProccess, e);
        });

        classesToProcess.putAll(innerClassesToProccess);

        try (PrintWriter w = new PrintWriter(out.openWriter())) {
            writeLicense(w);
            w.println("package com.azul.crs.json;\n");

            w.println("import java.io.PrintStream;");
            w.println("import java.io.IOException;");
            w.println("import com.azul.crs.json.*;");

            w.println("\npublic class JSONStaticSerializer extends DummyJSONSerializer {");
            w.println("  public JSONStaticSerializer() {} ");
            w.println("  public JSONStaticSerializer(boolean prettyPrint) { super(prettyPrint); }\n");
            w.println("  @Override");
            w.println("  public void serialize(PrintStream output, Object o) throws IOException {");

            classesToProcess.forEach((s, e) -> {
                String klassName = getClassName(e);

                w.println("\n    if (o instanceof " + getClassName(e) + " ) {");
                w.println("      boolean needComma = false;");
                w.println("      output.append('{');");
                w.println("      formatEnter(output);");
                w.println("      " + klassName + " i = (" + klassName + ")o;");

                final boolean[] needCommaCheck = {false};

                e.getEnclosedElements().forEach(ee -> {
                    if (ee.getKind() != ElementKind.FIELD) return;
                    if (ee.getModifiers().contains(Modifier.TRANSIENT)) return;

                    String fieldName = getName(ee);
                    String valueType = getClassNameByTypeMirror(ee.asType());

                    w.println("      {");
                    w.println("        // " + valueType);
                    w.println("        Object value=i." + getGetterName(klassName, fieldName) + ";");
                    w.println("        if (null != value) {");
                    if (needCommaCheck[0])
                    w.println("          if (needComma) output.append(',');");
                    w.println("          formatMid(output);");
                    w.println("          output.append(\"\\\"" + getFieldName(klassName, fieldName) + "\\\":\");");

                    if (isElementHavingNumberOrBooleanType(ee)) {
                        w.println("          output.append(value.toString());");
                    } else if (isElementExtending(ee, Enum.class)) {
                        w.println("          output.append(\"\\\"\").append(value.toString()).append(\"\\\"\");");
                    } else if (isElementExtending(ee, String.class)) {
                        w.println("          serializeString(output, (String)value);");
                    } else {
                        w.println("          super.serialize(output, value);");
                    }

                    w.println("          needComma = true;");
                    w.println("        }");
                    w.println("      }\n");

                    needCommaCheck[0] = true;
                });

                w.println("      formatLeave(output);");
                w.println("      if (needComma) formatMid(output);");
                w.println("      output.append('}');");
                w.println("      return;");
                w.println("    }\n");
            });
            w.println("    super.serialize(output, o);");

            w.println("  }");
            w.println("}");

        } catch(IOException e) {
            e.printStackTrace(System.err);
            throw new RuntimeException("Tykwa: ", e);
        }

        return true;
    }

    private String getClassName(Element e) {
        if (!(e instanceof TypeElement)) throw new RuntimeException("Expecting to be a TypeElement: " + e.getClass().getName());

        return String.valueOf(elementUtils.getBinaryName((TypeElement)e));
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> a = new HashSet<>();
        a.add(CompileJsonSerializer.class.getName());
        a.add(JsonProperty.class.getName());
        return a;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_6;
    }

    private void writeLicense(PrintWriter w) {
        w.println("/*");
        w.println(" * Copyright " + (Calendar.getInstance().get(Calendar.YEAR)) + " Azul Systems,");
        w.println(" *\n"
                + " * Redistribution and use in source and binary forms, with or without modification,\n"
                + " * are permitted provided that the following conditions are met:\n"
                + " *\n"
                + " * 1. Redistributions of source code must retain the above copyright notice, this\n"
                + " *    list of conditions and the following disclaimer.\n"
                + " *\n"
                + " * 2. Redistributions in binary form must reproduce the above copyright notice,\n"
                + " *    this list of conditions and the following disclaimer in the documentation\n"
                + " *    and/or other materials provided with the distribution.\n"
                + " *\n"
                + " * 3. Neither the name of the copyright holder nor the names of its contributors\n"
                + " *    may be used to endorse or promote products derived from this software without\n"
                + " *    specific prior written permission.\n"
                + " *\n"
                + " * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS \"AS IS\"\n"
                + " * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE\n"
                + " * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE\n"
                + " * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE\n"
                + " * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL\n"
                + " * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR\n"
                + " * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER\n"
                + " * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,\n"
                + " * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE\n"
                + " * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.\n"
                + " */");
    }
}
