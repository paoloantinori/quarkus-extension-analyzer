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
package io.github.paoloantinori.qea.plugin.buildsteps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-40 rung 3 pins: the build-step producer/consumer graph over compiled fixtures. The
 * BuildStep annotation stub carries the REAL FQCN ({@code io.quarkus.deployment.annotations.
 * BuildStep}) because the graph probes that exact name (the session's phantom-name lesson).
 */
class BuildStepGraphTest {

    private static final String BUILD_STEP_ANNOTATION = """
            package io.quarkus.deployment.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface BuildStep {}
            """;

    private static final String ITEM = """
            package com.acme.items;
            public class SerializerBuildItem {}
            """;

    private static final String PRODUCER = """
            package com.acme.producer;
            import io.quarkus.deployment.annotations.BuildStep;
            import com.acme.items.SerializerBuildItem;
            public class ProducerSteps {
                @BuildStep
                public SerializerBuildItem produceSerializer() { return new SerializerBuildItem(); }
            }
            """;

    private static final String CONSUMER = """
            package com.acme.consumer;
            import io.quarkus.deployment.annotations.BuildStep;
            import com.acme.items.SerializerBuildItem;
            public class ConsumerSteps {
                @BuildStep
                void registerSerializer(SerializerBuildItem item) { }
            }
            """;

    @Test
    void directRequiredConsumptionCreatesAnEdge(@TempDir Path tmp) throws IOException {
        Path prodOut = compile(tmp, "prod", BUILD_STEP_ANNOTATION, ITEM, PRODUCER);
        Path consOut = compile(tmp, "cons", BUILD_STEP_ANNOTATION, CONSUMER);

        Map<String, String> edges = BuildStepGraph.producerEdges(Map.of(
                "io.quarkus:quarkus-rest-jackson", List.of(prodOut),
                "io.quarkus:quarkus-rest", List.of(consOut)));

        assertThat(edges).containsKey("io.quarkus:quarkus-rest-jackson");
        assertThat(edges.get("io.quarkus:quarkus-rest-jackson"))
                .contains("com.acme.items.SerializerBuildItem")
                .contains("io.quarkus:quarkus-rest")
                .contains("build-step");
    }

    @Test
    void selfConsumptionIsNotAnEdge(@TempDir Path tmp) throws IOException {
        // A single extension whose steps produce AND consume the same item: not an edge.
        Path out = compile(tmp, "self", BUILD_STEP_ANNOTATION, ITEM, PRODUCER, """
                package com.acme.producer;
                import io.quarkus.deployment.annotations.BuildStep;
                import com.acme.items.SerializerBuildItem;
                public class SelfSteps {
                    @BuildStep
                    void useOwn(SerializerBuildItem item) { }
                }
                """);

        Map<String, String> edges = BuildStepGraph.producerEdges(Map.of(
                "io.quarkus:quarkus-rest-jackson", List.of(out)));

        assertThat(edges).isEmpty();
    }

    @Test
    void unrelatedBuildItemsCreateNoEdge(@TempDir Path tmp) throws IOException {
        Path prodOut = compile(tmp, "p", BUILD_STEP_ANNOTATION, ITEM, PRODUCER);
        Path consOut = compile(tmp, "c", BUILD_STEP_ANNOTATION, """
                package com.acme.items;
                public class UnrelatedItem {}
                """, """
                package com.acme.consumer;
                import io.quarkus.deployment.annotations.BuildStep;
                import com.acme.items.UnrelatedItem;
                public class OtherSteps {
                    @BuildStep
                    void other(UnrelatedItem i) { }
                }
                """);

        Map<String, String> edges = BuildStepGraph.producerEdges(Map.of(
                "io.quarkus:quarkus-rest-jackson", List.of(prodOut),
                "io.quarkus:quarkus-rest", List.of(consOut)));

        assertThat(edges).isEmpty();
    }

    private static Path compile(Path tmp, String label, String... sources) throws IOException {
        // Compile in TWO rounds: round 1 builds the shared foundation (annotation + items) into
        // its own out dir; round 2 compiles the steps against it on the classpath. This mirrors
        // real deployment artifacts: each sees the shared types, not each other.
        Path src = Files.createDirectories(tmp.resolve(label + "-src"));
        Path out = Files.createDirectories(tmp.resolve(label + "-out"));
        Path foundation = compileFoundation(tmp);
        var files = new java.util.ArrayList<Path>();
        for (String s : sources) {
            String pkg = s.substring("package ".length(), s.indexOf(';'));
            String cls = classNameOf(s);
            Path f = src.resolve(pkg.replace('.', '/') + "/" + cls + ".java");
            Files.createDirectories(f.getParent());
            Files.writeString(f, s);
            files.add(f);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            boolean ok = compiler.getTask(null, fm, diagnostics,
                    List.of("-d", out.toString(), "-cp", foundation.toString()), null,
                    fm.getJavaFileObjectsFromFiles(files.stream().map(Path::toFile).toList())).call();
            assertThat(ok).as(diagnostics.getDiagnostics().toString()).isTrue();
        }
        return out;
    }

    private static Path foundationDir(Path tmp) {
        return tmp.resolve("foundation-out");
    }

    private static Path compileFoundation(Path tmp) throws IOException {
        Path cached = foundationDir(tmp);
        if (Files.isDirectory(cached) && Files.exists(cached.resolve("com/acme/items/SerializerBuildItem.class"))) {
            return cached;
        }
        Path src = Files.createDirectories(tmp.resolve("foundation-src"));
        Path out = Files.createDirectories(cached);
        var files = new java.util.ArrayList<Path>();
        for (String s : List.of(BUILD_STEP_ANNOTATION, ITEM,
                "package com.acme.items;\npublic class UnrelatedItem {}\n")) {
            String pkg = s.substring("package ".length(), s.indexOf(';'));
            String cls = classNameOf(s);
            Path f = src.resolve(pkg.replace('.', '/') + "/" + cls + ".java");
            Files.createDirectories(f.getParent());
            Files.writeString(f, s);
            files.add(f);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            boolean ok = compiler.getTask(null, fm, diagnostics, List.of("-d", out.toString()), null,
                    fm.getJavaFileObjectsFromFiles(files.stream().map(Path::toFile).toList())).call();
            assertThat(ok).as(diagnostics.getDiagnostics().toString()).isTrue();
        }
        return out;
    }

    private static String classNameOf(String source) {
        var m = java.util.regex.Pattern.compile("public (?:class|interface|@interface) (\\w+)")
                .matcher(source);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }
}
