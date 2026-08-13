/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.paoloantinori.qea.plugin.bytecode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test for {@link BytecodeUsage#referencedTypesViaJandex} that compiles a small in-memory
 * source set to a {@code @TempDir} with the JDK's {@code javac} and asserts the Jandex extraction sees
 * member-level (field, record-component, parameter) annotations.
 *
 * <p>Regression coverage for TASK-12: the method previously iterated {@code ClassInfo.declaredAnnotations()}
 * (class-level only), so an annotation applied to a field or a record component never entered the
 * referenced set. That hid {@code jakarta.validation-api} from signal 2 in the super-heroes rest-fights
 * bench, where {@code @NotNull} sits on a {@code FightRequest} record component and propagates to the
 * synthesized record field. {@code FightRequest} below mirrors that propagation: {@code @RecordMarker}
 * targets {@code FIELD}, so {@code javac} places it on the record's field, where {@code annotations()}
 * (the broad Jandex view used after the fix) sees it but {@code declaredAnnotations()} does not.
 */
class BytecodeUsageTest {

    private static final String FIELD_MARKER =
            "package com.example;\n" +
            "import java.lang.annotation.ElementType;\n" +
            "import java.lang.annotation.Retention;\n" +
            "import java.lang.annotation.RetentionPolicy;\n" +
            "import java.lang.annotation.Target;\n" +
            "@Target(ElementType.FIELD)\n" +
            "@Retention(RetentionPolicy.RUNTIME)\n" +
            "public @interface FieldMarker {}\n";

    private static final String RECORD_MARKER =
            "package com.example;\n" +
            "import java.lang.annotation.ElementType;\n" +
            "import java.lang.annotation.Retention;\n" +
            "import java.lang.annotation.RetentionPolicy;\n" +
            "import java.lang.annotation.Target;\n" +
            "@Target(ElementType.FIELD)\n" +
            "@Retention(RetentionPolicy.RUNTIME)\n" +
            "public @interface RecordMarker {}\n";

    private static final String PARAM_MARKER =
            "package com.example;\n" +
            "import java.lang.annotation.ElementType;\n" +
            "import java.lang.annotation.Retention;\n" +
            "import java.lang.annotation.RetentionPolicy;\n" +
            "import java.lang.annotation.Target;\n" +
            "@Target(ElementType.PARAMETER)\n" +
            "@Retention(RetentionPolicy.RUNTIME)\n" +
            "public @interface ParamMarker {}\n";

    private static final String PAYLOAD =
            "package com.example;\n" +
            "public class Payload {}\n";

    private static final String SAMPLE =
            "package com.example;\n" +
            "public class Sample {\n" +
            "    @FieldMarker\n" +
            "    private final Payload payload;\n" +
            "    public Sample(@ParamMarker Payload payload) { this.payload = payload; }\n" +
            "    public Payload getPayload() { return payload; }\n" +
            "}\n";

    private static final String FIGHT_REQUEST =
            "package com.example;\n" +
            "public record FightRequest(@RecordMarker String name) {}\n";

    @Test
    void memberAnnotationsAndFieldTypesAreCaptured(@TempDir Path tempDir) throws IOException {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Path outDir = Files.createDirectories(tempDir.resolve("out"));
        List<File> sources = List.of(
                writeSource(srcDir, "com.example.FieldMarker", FIELD_MARKER),
                writeSource(srcDir, "com.example.RecordMarker", RECORD_MARKER),
                writeSource(srcDir, "com.example.ParamMarker", PARAM_MARKER),
                writeSource(srcDir, "com.example.Payload", PAYLOAD),
                writeSource(srcDir, "com.example.Sample", SAMPLE),
                writeSource(srcDir, "com.example.FightRequest", FIGHT_REQUEST)).stream()
                .map(Path::toFile).toList();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK (not a JRE) is required for in-source compilation").isNotNull();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            Iterable<String> options = List.of("-d", outDir.toString());
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fm, diagnostics, options, null,
                    fm.getJavaFileObjectsFromFiles(sources));
            boolean compiled = task.call();
            assertThat(compiled).as(diagnostics.getDiagnostics().toString()).isTrue();
        }

        Set<String> referenced = BytecodeUsage.referencedTypesViaJandex(List.of(outDir));

        // (1) Field-level annotation on a plain class: missed by declaredAnnotations(), seen by annotations().
        assertThat(referenced).contains("com.example.FieldMarker");
        // (2) Record-component annotation on a record (the rest-fights FightRequest case): propagated to the
        //     synthesized record field, so a class-level-only view misses it and the broad view catches it.
        assertThat(referenced).contains("com.example.RecordMarker");
        // (3) Parameter-level annotation on a constructor parameter: jakarta.validation constraints
        //     commonly sit on parameters too, another member position declaredAnnotations() missed.
        assertThat(referenced).contains("com.example.ParamMarker");
        // (4) Sanity: a type referenced only as a field TYPE (not an annotation) is still captured by the
        //     existing ci.fields() extraction loop, which is unchanged by TASK-12.
        assertThat(referenced).contains("com.example.Payload");
    }

    private static Path writeSource(Path srcRoot, String qualifiedName, String source) throws IOException {
        Path f = srcRoot.resolve(qualifiedName.replace('.', '/') + ".java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, source);
        return f;
    }
}
