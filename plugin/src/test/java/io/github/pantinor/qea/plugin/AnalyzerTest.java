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
package io.github.pantinor.qea.plugin;

import io.github.pantinor.qea.plugin.configroot.RootInheritance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for two package-private {@link Analyzer} helpers, kept out of the full {@code analyze}
 * pipeline since that needs a real {@code ApplicationModel} (heavy to construct in a pure-JUnit test;
 * covered instead by the registry-bench validation run, see docs/M2-VALIDATION.md).
 *
 * <p>{@link Analyzer#containedClassesConcurrently}'s "one bad jar doesn't abort the others" property is
 * not re-tested here beyond {@link #scanPlainJarIsolatesUnreadableJarFailureInsteadOfThrowing}: once
 * {@link Analyzer#scanPlainJar} is proven to never throw, the concurrent wiring around it (a plain loop
 * submitting one future per dependency, {@code CompletableFuture::join} on each) cannot let one
 * dependency's failure affect another's future by construction, not merely by the tests below.
 */
class AnalyzerTest {

    /**
     * B1 regression: an inheriting extension's owner can claim two roots where one prefixes the other
     * (e.g. {@code quarkus.datasource.} and {@code quarkus.datasource.h2.}, both owned by
     * quarkus-agroal). A key under the narrower root matches both {@link RootInheritance.InheritedRoot}
     * entries and must not be counted twice in the credited key list.
     */
    @Test
    void inheritedKeysByGaDedupesWhenOwnerHasOverlappingRoots() {
        RootInheritance.Result inheritance = new RootInheritance.Result(
                Map.of("io.quarkus:quarkus-jdbc-h2", Set.of(
                        new RootInheritance.InheritedRoot("quarkus.datasource.", "io.quarkus:quarkus-agroal"),
                        new RootInheritance.InheritedRoot("quarkus.datasource.h2.", "io.quarkus:quarkus-agroal"))),
                Set.of());
        Map<String, List<String>> keysWonByOwner =
                Map.of("io.quarkus:quarkus-agroal", List.of("quarkus.datasource.h2.jdbc.url"));

        Map<String, List<String>> result = Analyzer.inheritedKeysByGa(inheritance, keysWonByOwner);

        assertThat(result.get("io.quarkus:quarkus-jdbc-h2")).containsExactly("quarkus.datasource.h2.jdbc.url");
    }

    @Test
    void inheritedKeysByGaOmitsExtensionsWithNoMatch() {
        RootInheritance.Result inheritance = new RootInheritance.Result(
                Map.of("ext:leaf", Set.of(new RootInheritance.InheritedRoot("quarkus.a.", "ext:owner"))), Set.of());
        Map<String, List<String>> keysWonByOwner = Map.of();

        Map<String, List<String>> result = Analyzer.inheritedKeysByGa(inheritance, keysWonByOwner);

        assertThat(result).doesNotContainKey("ext:leaf");
    }

    /**
     * B3: a jar that cannot be read must degrade to a recorded error, never throw out of the scan.
     *
     * <p>The fixture here is a path that cannot be opened (an {@link java.io.IOException} from {@code
     * URL#openStream()}), not byte-level ZIP corruption: {@code maven-dependency-analyzer}'s {@code
     * DefaultClassAnalyzer} reads jars via {@code java.util.jar.JarInputStream}, a sequential reader
     * that was found, empirically, to silently treat many malformed byte patterns (garbage content, a
     * truncated local-file-header) as "zero entries" rather than throwing -- so a byte-garbage {@code
     * .jar} file does not reliably reproduce the failure this test needs. An unreadable path reaches the
     * exact same {@code IOException}-from-{@code BytecodeUsage.containedClasses} path that a genuinely
     * corrupt ZIP structure would, which is what {@link Analyzer#scanPlainJar} must isolate.
     */
    @Test
    void scanPlainJarIsolatesUnreadableJarFailureInsteadOfThrowing(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.jar");

        Analyzer.PlainJarScan scan = Analyzer.scanPlainJar(missing);

        assertThat(scan.error()).isNotNull();
        assertThat(scan.containedClasses()).isEmpty();
    }

    @Test
    void scanPlainJarReturnsNullWhenThereIsNoJarToScan() {
        assertThat(Analyzer.scanPlainJar(null)).isNull();
    }
}
