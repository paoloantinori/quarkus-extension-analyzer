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
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for several package-private {@link Analyzer} helpers, kept out of the full {@code
 * analyze} pipeline since that needs a real {@code ApplicationModel} (heavy to construct in a pure-JUnit
 * test; covered instead by the registry-bench validation run, see docs/M2-VALIDATION.md).
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

    /**
     * TASK-5, plan item 4 case: an extension's exclusive transitive jar that the project's compiled
     * classes reference (per {@code jandexReferenced}) is recorded as evidence -- this is the second half
     * of "exclusive jar referenced -&gt; attributed used-bytecode" (the first half, whether a jar counts
     * as exclusive at all, is {@link io.github.pantinor.qea.plugin.bytecode.TransitiveApiAttributionTest}).
     */
    @Test
    void transitiveApiEvidenceByGaRecordsTheJarWhenItsContainedClassIsReferenced() {
        Map<String, Set<String>> exclusiveJarsByExtension =
                Map.of("io.quarkus:quarkus-kubernetes-client", Set.of("io.fabric8:kubernetes-client"));
        Map<String, Analyzer.PlainJarScan> exclusiveJarScans = Map.of("io.fabric8:kubernetes-client",
                Analyzer.PlainJarScan.ok(Set.of("io.fabric8.kubernetes.client.KubernetesClient")));
        Set<String> jandexReferenced = Set.of("io.fabric8.kubernetes.client.KubernetesClient");

        Map<String, String> evidence =
                Analyzer.transitiveApiEvidenceByGa(exclusiveJarsByExtension, exclusiveJarScans, jandexReferenced);

        assertThat(evidence).containsEntry("io.quarkus:quarkus-kubernetes-client", "io.fabric8:kubernetes-client");
    }

    /**
     * TASK-5, plan item 4 case: an extension's exclusive transitive jar that the project's compiled
     * classes do NOT reference contributes no evidence -- the extension's classification stays as it was
     * from the other signals ("stays as-is").
     */
    @Test
    void transitiveApiEvidenceByGaOmitsExtensionWhenExclusiveJarIsNotReferenced() {
        Map<String, Set<String>> exclusiveJarsByExtension =
                Map.of("io.quarkus:quarkus-kubernetes-client", Set.of("io.fabric8:kubernetes-client"));
        Map<String, Analyzer.PlainJarScan> exclusiveJarScans = Map.of("io.fabric8:kubernetes-client",
                Analyzer.PlainJarScan.ok(Set.of("io.fabric8.kubernetes.client.KubernetesClient")));
        Set<String> jandexReferenced = Set.of("some.other.Type");

        Map<String, String> evidence =
                Analyzer.transitiveApiEvidenceByGa(exclusiveJarsByExtension, exclusiveJarScans, jandexReferenced);

        assertThat(evidence).doesNotContainKey("io.quarkus:quarkus-kubernetes-client");
    }

    /**
     * TASK-5 follow-up: an extension referenced BOTH directly (its own runtime artifact) AND via an
     * exclusive transitive jar must end up {@code bytecodeReferenced=true} with {@code
     * bytecodeViaTransitiveApi=null} -- not both signals surfaced, per {@link
     * io.github.pantinor.qea.plugin.report.ExtensionReport#bytecodeViaTransitiveApi()}'s contract ("this
     * extension's own jar was NOT referenced"). {@link Analyzer#transitiveApiCandidates} enforces this by
     * filtering the extension out BEFORE {@link Analyzer#transitiveApiEvidenceByGa} ever sees it, even
     * though the exclusive jar genuinely would have matched if it had been scanned.
     */
    @Test
    void extensionWithBothDirectReferenceAndReferencedExclusiveJarKeepsOnlyItsOwnJarEvidence() {
        String gaKey = "io.quarkus:quarkus-kubernetes-client";
        Map<String, Set<String>> exclusiveJarsByExtension = Map.of(gaKey, Set.of("io.fabric8:kubernetes-client-api"));
        // The extension's own runtime artifact was already found referenced by signal 2 (own-jar check).
        Map<String, Boolean> bytecodeUsedByGa = Map.of(gaKey, true);
        Map<String, Analyzer.PlainJarScan> exclusiveJarScans = Map.of("io.fabric8:kubernetes-client-api",
                Analyzer.PlainJarScan.ok(Set.of("io.fabric8.kubernetes.client.KubernetesClient")));
        Set<String> jandexReferenced = Set.of("io.fabric8.kubernetes.client.KubernetesClient");

        Map<String, Set<String>> candidates = Analyzer.transitiveApiCandidates(exclusiveJarsByExtension,
                bytecodeUsedByGa, null);
        Map<String, String> evidence =
                Analyzer.transitiveApiEvidenceByGa(candidates, exclusiveJarScans, jandexReferenced);

        assertThat(candidates).doesNotContainKey(gaKey);
        assertThat(evidence).doesNotContainKey(gaKey);
        // bytecodeReferenced (fed by bytecodeUsedByGa in Analyzer#analyze) stays true throughout: the skip
        // only suppresses the transitive-API evidence field, never the underlying own-jar signal.
        assertThat(bytecodeUsedByGa.get(gaKey)).isTrue();
    }

    @Test
    void transitiveApiCandidatesKeepsAnExtensionNotYetUsedViaItsOwnJar() {
        String gaKey = "io.quarkus:quarkus-kubernetes-client";
        Map<String, Set<String>> exclusiveJarsByExtension = Map.of(gaKey, Set.of("io.fabric8:kubernetes-client-api"));
        Map<String, Boolean> bytecodeUsedByGa = Map.of(gaKey, false);

        Map<String, Set<String>> candidates = Analyzer.transitiveApiCandidates(exclusiveJarsByExtension,
                bytecodeUsedByGa, null);

        assertThat(candidates).containsEntry(gaKey, Set.of("io.fabric8:kubernetes-client-api"));
    }

    /**
     * TASK-5 follow-up: {@link Analyzer#ga} strips the classifier, so two resolved dependencies sharing a
     * {@code groupId:artifactId} but differing by classifier (a jar and its {@code tests} classifier
     * variant) can collide in {@code allDepsByGa}. The empty-classifier (primary) jar must always win,
     * regardless of which one the underlying collection enumerates first -- verified both ways here.
     */
    @Test
    void allDepsByGaPrefersThePrimaryJarOverAClassifiedDuplicateRegardlessOfEnumerationOrder() {
        ResolvedDependency primary = ResolvedDependencyBuilder.newInstance()
                .setGroupId("io.lib").setArtifactId("example").setVersion("1.0").build();
        ResolvedDependency classified = ResolvedDependencyBuilder.newInstance()
                .setGroupId("io.lib").setArtifactId("example").setClassifier("tests").setVersion("1.0").build();

        Map<String, ResolvedDependency> primaryFirst = Analyzer.allDepsByGa(List.of(primary, classified), null);
        Map<String, ResolvedDependency> classifiedFirst = Analyzer.allDepsByGa(List.of(classified, primary), null);

        assertThat(primaryFirst.get("io.lib:example")).isSameAs(primary);
        assertThat(classifiedFirst.get("io.lib:example")).isSameAs(primary);
    }
}
