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
package io.github.paoloantinori.qea.isolated;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-28's hardest-won lesson, pinned against the artifact: the shade plugin's relocation of
 * {@code io.quarkus} must never touch the annotation engine's DOMAIN string literals
 * ("io.quarkus:quarkus-rest-jackson", "io.quarkus.scheduler.Scheduled", ...). A bare
 * {@code io.quarkus} pattern prefix-matches them and rewrites the constants to
 * {@code io.github.paoloantinori.qea.internal.quarkus.*}, which can never equal the real GAs
 * and FQCNs carried by the report and the app index - every rule silently dead in the mojo
 * form, with every unit test green (the core suite runs on unshaded classes). This IT reads
 * the BUILT shaded jar and asserts the engine's constants survive byte-for-byte; it runs at
 * integration-test time (after shade) via failsafe, so {@code mvn install} and
 * {@code mvn verify} both enforce it.
 */
class ShadedJarRelocationIT {

    private static final String ENGINE_CLASS =
            "io/github/paoloantinori/qea/plugin/annotation/AnnotationConsumerRules.class";

    @Test
    void engineDomainLiteralsSurviveShading() throws IOException {
        byte[] engine = readEngineClass(shadedJar());

        String bytes = new String(engine, java.nio.charset.StandardCharsets.ISO_8859_1);
        // Domain literals intact (constant-pool UTF8 entries appear verbatim in the bytes).
        assertThat(bytes).contains("io.quarkus:quarkus-rest-jackson");
        assertThat(bytes).contains("io.quarkus:quarkus-config-yaml");
        assertThat(bytes).contains("io.quarkus.scheduler.Scheduled");
        assertThat(bytes).contains("io.quarkus.qute.");
        // And not a single mangled internal form anywhere in the engine.
        assertThat(bytes).doesNotContain("qea.internal.quarkus:");
        assertThat(bytes).doesNotContain("internal.quarkus.scheduler");
    }

    @Test
    void bootstrapClassesAreStillRelocated() throws IOException {
        // The TASK-20 goal must survive the fix: the embedded bootstrap classes ARE relocated.
        assertThat(classNames(shadedJar())).noneMatch(n -> n.startsWith("io/quarkus/"));
    }

    private static Path shadedJar() throws IOException {
        // target/quarkus-extension-analyzer-shaded-1.0-SNAPSHOT.jar
        Path target = Path.of("target");
        try (Stream<Path> jars = Files.list(target)) {
            return jars.filter(p -> p.getFileName().toString().startsWith("quarkus-extension-analyzer-shaded")
                            && p.getFileName().toString().endsWith(".jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "shaded jar not found in " + target.toAbsolutePath()));
        }
    }

    private static byte[] readEngineClass(Path jar) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().equals(ENGINE_CLASS)) {
                    return zip.readAllBytes();
                }
            }
        }
        throw new IllegalStateException(ENGINE_CLASS + " not found in " + jar);
    }

    private static java.util.List<String> classNames(Path jar) throws IOException {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().endsWith(".class")) {
                    names.add(e.getName());
                }
            }
        }
        return names;
    }
}
