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
package io.github.paoloantinori.qea.plugin;

import io.github.paoloantinori.qea.plugin.capability.CapabilityJoin;
import io.github.paoloantinori.qea.plugin.configroot.ConfigRootProbe;
import io.github.paoloantinori.qea.plugin.configroot.ConfigRootSource;
import io.github.paoloantinori.qea.plugin.configroot.RootInheritance;
import io.github.paoloantinori.qea.plugin.configroot.ValueRules;
import io.github.paoloantinori.qea.plugin.report.ExtensionReport;
import io.github.paoloantinori.qea.plugin.report.Verdict;
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
     * as exclusive at all, is {@link io.github.paoloantinori.qea.plugin.bytecode.TransitiveApiAttributionTest}).
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
     * io.github.paoloantinori.qea.plugin.report.ExtensionReport#bytecodeViaTransitiveApi()}'s contract ("this
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

    /**
     * TASK-11 (hibernate-validator/jakarta.validation-api bench case, mirror of the kubernetes-client
     * fix): a jar reached by only one extension's subtree is exclusive, not a shared-referenced-jars hint
     * candidate at all -- {@link Analyzer#sharedCandidateJars} must filter it out just as {@link
     * io.github.paoloantinori.qea.plugin.bytecode.TransitiveApiAttribution} keeps it out of {@code
     * exclusiveByExtension}'s complement. {@code totalDeclaredExtensions} is 10 here, well above the 2
     * owners {@code jakarta.validation-api} has, so this case is unaffected by the ubiquity cutoff (that
     * is {@link #sharedCandidateJarsExcludesAJarReachedByAMajorityOfDeclaredExtensionsAsUbiquitousNoise}).
     */
    @Test
    void sharedCandidateJarsKeepsOnlyJarsReachedByTwoOrMoreDeclaredExtensions() {
        Map<String, Set<String>> reachableByExtension = Map.of(
                "io.quarkus:quarkus-hibernate-validator", Set.of("jakarta.validation:jakarta.validation-api"),
                "io.quarkus:quarkus-apicurio-registry-avro", Set.of("jakarta.validation:jakarta.validation-api"),
                "io.quarkus:quarkus-agroal", Set.of("io.lib:agroal-api"));
        Map<String, Set<String>> extensionsReachingJar = Map.of(
                "jakarta.validation:jakarta.validation-api",
                Set.of("io.quarkus:quarkus-hibernate-validator", "io.quarkus:quarkus-apicurio-registry-avro"),
                "io.lib:agroal-api", Set.of("io.quarkus:quarkus-agroal"));

        Map<String, Set<String>> result =
                Analyzer.sharedCandidateJars(reachableByExtension, extensionsReachingJar, 10);

        assertThat(result.get("io.quarkus:quarkus-hibernate-validator"))
                .containsExactly("jakarta.validation:jakarta.validation-api");
        assertThat(result.get("io.quarkus:quarkus-apicurio-registry-avro"))
                .containsExactly("jakarta.validation:jakarta.validation-api");
        assertThat(result).doesNotContainKey("io.quarkus:quarkus-agroal");
    }

    /**
     * TASK-11 bench follow-up: the first cut of this signal put {@code jackson-databind}/{@code
     * cdi-api}/{@code smallrye-config-core}-style ubiquitous jars on nearly every suspect row in the
     * rest-fights bench, which discriminates nothing and is noise, not a hint. A jar reached by MORE THAN
     * half of the declared extensions (the same {@link RootInheritance#UBIQUITY_THRESHOLD} cutoff {@link
     * RootInheritance#inherit} already applies) must never be a candidate, even though it is still,
     * technically, "shared" by the plain size-&gt;=2 rule.
     */
    @Test
    void sharedCandidateJarsExcludesAJarReachedByAMajorityOfDeclaredExtensionsAsUbiquitousNoise() {
        Map<String, Set<String>> reachableByExtension = Map.of(
                "io.quarkus:quarkus-hibernate-validator", Set.of("com.fasterxml.jackson.core:jackson-databind"));
        // 6 declared extensions total, 4 of them (> 50%) reach this jar: ubiquitous, must be excluded.
        Map<String, Set<String>> extensionsReachingJar = Map.of("com.fasterxml.jackson.core:jackson-databind",
                Set.of("io.quarkus:quarkus-hibernate-validator", "io.quarkus:quarkus-grpc",
                        "io.quarkus:quarkus-info", "io.quarkus:quarkus-kubernetes"));

        Map<String, Set<String>> result = Analyzer.sharedCandidateJars(reachableByExtension, extensionsReachingJar, 6);

        assertThat(result).doesNotContainKey("io.quarkus:quarkus-hibernate-validator");
    }

    /**
     * TASK-11 bench follow-up, full-chain version of the case above: an ubiquitous jar produces no hint
     * EVEN WHEN the project's compiled classes do reference it -- {@link Analyzer#sharedCandidateJars}
     * excludes it before {@link Analyzer#sharedReferencedJarsHint} ever sees it as a candidate, so
     * referencedness cannot resurrect it.
     */
    @Test
    void sharedReferencedJarsHintNeverFiresForAnUbiquitousJarEvenWhenReferenced() {
        Map<String, Set<String>> reachableByExtension = Map.of(
                "io.quarkus:quarkus-hibernate-validator", Set.of("com.fasterxml.jackson.core:jackson-databind"));
        Map<String, Set<String>> extensionsReachingJar = Map.of("com.fasterxml.jackson.core:jackson-databind",
                Set.of("io.quarkus:quarkus-hibernate-validator", "io.quarkus:quarkus-grpc",
                        "io.quarkus:quarkus-info", "io.quarkus:quarkus-kubernetes"));
        Set<String> referencedJarGas = Set.of("com.fasterxml.jackson.core:jackson-databind");

        Map<String, Set<String>> candidates = Analyzer.sharedCandidateJars(reachableByExtension, extensionsReachingJar, 6);
        Map<String, List<ExtensionReport.SharedReferencedJar>> hints =
                Analyzer.sharedReferencedJarsHint(candidates, extensionsReachingJar, referencedJarGas);

        assertThat(hints).doesNotContainKey("io.quarkus:quarkus-hibernate-validator");
    }

    /**
     * TASK-11, plan item "(b) present in the project's referenced-type set": a scanned jar counts as
     * referenced only when its contained classes actually intersect {@code jandexReferenced}; a jar whose
     * scan failed is conservatively treated as not referenced, never guessed either way.
     */
    @Test
    void referencedJarGasKeepsOnlyScannedJarsWhoseContainedClassIsReferenced() {
        Map<String, Analyzer.PlainJarScan> scans = Map.of(
                "jakarta.validation:jakarta.validation-api",
                Analyzer.PlainJarScan.ok(Set.of("jakarta.validation.constraints.NotNull")),
                "io.lib:not-referenced", Analyzer.PlainJarScan.ok(Set.of("io.lib.Unused")),
                "io.lib:scan-failed", Analyzer.PlainJarScan.failed("IOException: corrupt"));
        Set<String> jandexReferenced = Set.of("jakarta.validation.constraints.NotNull");

        Set<String> result = Analyzer.referencedJarGas(scans, jandexReferenced);

        assertThat(result).containsExactly("jakarta.validation:jakarta.validation-api");
    }

    /**
     * TASK-11, plan case 1 ("suspect with shared referenced jar -&gt; hint present"): a shared candidate
     * jar that IS referenced produces a hint naming the OTHER declared extension(s) that also reach it
     * (never itself).
     */
    @Test
    void sharedReferencedJarsHintProducesAHintForAReferencedSharedJarNamingTheOtherOwners() {
        Map<String, Set<String>> sharedCandidateJarsByExtension = Map.of(
                "io.quarkus:quarkus-hibernate-validator", Set.of("jakarta.validation:jakarta.validation-api"));
        Map<String, Set<String>> extensionsReachingJar = Map.of("jakarta.validation:jakarta.validation-api",
                Set.of("io.quarkus:quarkus-hibernate-validator", "io.quarkus:quarkus-apicurio-registry-avro"));
        Set<String> referencedJarGas = Set.of("jakarta.validation:jakarta.validation-api");

        Map<String, List<ExtensionReport.SharedReferencedJar>> hints = Analyzer.sharedReferencedJarsHint(
                sharedCandidateJarsByExtension, extensionsReachingJar, referencedJarGas);

        assertThat(hints.get("io.quarkus:quarkus-hibernate-validator")).containsExactly(
                new ExtensionReport.SharedReferencedJar("jakarta.validation:jakarta.validation-api",
                        List.of("io.quarkus:quarkus-apicurio-registry-avro")));
    }

    /**
     * TASK-11, plan case 3 ("shared but unreferenced -&gt; no hint"): a shared candidate jar the project's
     * bytecode does NOT reference contributes no hint at all -- the extension is absent from the result,
     * not mapped to an empty list (same convention as {@link Analyzer#transitiveApiEvidenceByGa}).
     */
    @Test
    void sharedReferencedJarsHintOmitsAnExtensionWhoseSharedJarIsNotReferenced() {
        Map<String, Set<String>> sharedCandidateJarsByExtension = Map.of(
                "io.quarkus:quarkus-hibernate-validator", Set.of("jakarta.validation:jakarta.validation-api"));
        Map<String, Set<String>> extensionsReachingJar = Map.of("jakarta.validation:jakarta.validation-api",
                Set.of("io.quarkus:quarkus-hibernate-validator", "io.quarkus:quarkus-apicurio-registry-avro"));
        Set<String> referencedJarGas = Set.of();

        Map<String, List<ExtensionReport.SharedReferencedJar>> hints = Analyzer.sharedReferencedJarsHint(
                sharedCandidateJarsByExtension, extensionsReachingJar, referencedJarGas);

        assertThat(hints).doesNotContainKey("io.quarkus:quarkus-hibernate-validator");
    }

    /**
     * TASK-11, plan case 1 (integration half): {@link Analyzer#classifyExtension} attaches a precomputed
     * hint to the row it produces when, and only when, the row lands on one of the two SUSPECT-producing
     * branches.
     */
    @Test
    void classifyExtensionAttachesTheSharedReferencedJarsHintOnASuspectRow() {
        ResolvedDependency dep = ResolvedDependencyBuilder.newInstance().setGroupId("io.quarkus")
                .setArtifactId("quarkus-hibernate-validator").setVersion("1.0").setRuntimeExtensionArtifact()
                .setDirect(true).build();
        List<ExtensionReport.SharedReferencedJar> hint = List.of(new ExtensionReport.SharedReferencedJar(
                "jakarta.validation:jakarta.validation-api", List.of("io.quarkus:quarkus-apicurio-registry-avro")));

        ExtensionReport row = Analyzer.classifyExtension(dep, new ConfigRootProbe.Probe(), Map.of(), Map.of(),
                new RootInheritance.Result(Map.of(), Set.of()), false, Map.of(), null, hint, null, null);

        assertThat(row.verdict()).isEqualTo(Verdict.SUSPECT);
        assertThat(row.sharedReferencedJars()).isEqualTo(hint);
    }

    /**
     * TASK-11, plan case 2 ("used extension -&gt; no hint computed"): conservative semantics are
     * non-negotiable, so even when a hint WAS computed for this extension (passed in here exactly as case
     * 1 does), {@link Analyzer#classifyExtension} must never attach it to a non-SUSPECT row -- the hint
     * must never look like it changed, or contributed to, the verdict.
     */
    @Test
    void classifyExtensionNeverAttachesTheHintWhenTheExtensionIsAlreadyUsed() {
        ResolvedDependency dep = ResolvedDependencyBuilder.newInstance().setGroupId("io.quarkus")
                .setArtifactId("quarkus-hibernate-validator").setVersion("1.0").setRuntimeExtensionArtifact()
                .setDirect(true).build();
        List<ExtensionReport.SharedReferencedJar> hint = List.of(new ExtensionReport.SharedReferencedJar(
                "jakarta.validation:jakarta.validation-api", List.of("io.quarkus:quarkus-apicurio-registry-avro")));

        ExtensionReport row = Analyzer.classifyExtension(dep, new ConfigRootProbe.Probe(), Map.of(), Map.of(),
                new RootInheritance.Result(Map.of(), Set.of()), true, Map.of(), null, hint, null, null);

        assertThat(row.verdict()).isEqualTo(Verdict.USED_BYTECODE);
        assertThat(row.sharedReferencedJars()).isEmpty();
    }

    // --- TASK-7: value-based activation rules, integration through classifyExtension/classifyPlainJar -

    private static ResolvedDependency extensionDep(String groupId, String artifactId) {
        return ResolvedDependencyBuilder.newInstance().setGroupId(groupId).setArtifactId(artifactId)
                .setVersion("1.0").setRuntimeExtensionArtifact().setDirect(true).build();
    }

    private static ResolvedDependency plainJarDep(String groupId, String artifactId) {
        return ResolvedDependencyBuilder.newInstance().setGroupId(groupId).setArtifactId(artifactId)
                .setVersion("1.0").setDirect(true).build();
    }

    /**
     * TASK-7, plan item 2: a value-rule match marks the target extension used-config even though it has
     * no own root and no inherited root at all (e.g. an app that only sets a named db-kind, with no
     * other {@code quarkus.datasource.*} key present for {@link io.github.paoloantinori.qea.plugin.configroot.
     * RootInheritance} to have inherited in the first place).
     */
    @Test
    void classifyExtensionUsesValueRuleMatchWhenNoOwnOrInheritedKeyMatched() {
        ResolvedDependency dep = extensionDep("io.quarkus", "quarkus-jdbc-postgresql");
        ValueRules.Match match = new ValueRules.Match("io.quarkus:quarkus-jdbc-postgresql",
                "quarkus.datasource.postgresql.db-kind", "postgresql", "quarkus.datasource.{name}.db-kind");

        ExtensionReport row = Analyzer.classifyExtension(dep, new ConfigRootProbe.Probe(), Map.of(), Map.of(),
                new RootInheritance.Result(Map.of(), Set.of()), false, Map.of(), null, List.of(), match, null);

        assertThat(row.verdict()).isEqualTo(Verdict.USED_CONFIG);
        assertThat(row.configInherited()).isFalse();
        assertThat(row.configSource()).containsExactly(ConfigRootSource.VALUE_RULE);
        assertThat(row.configMatchedKeys()).containsExactly("quarkus.datasource.postgresql.db-kind");
        assertThat(row.valueRuleEvidence())
                .isEqualTo("selected by quarkus.datasource.postgresql.db-kind=postgresql");
    }

    /**
     * TASK-7, plan item 2: "stronger than family inheritance" -- when BOTH a value-rule match and a
     * blanket inherited root are present for the same extension, the value-rule evidence wins (the
     * report must not claim the vaguer "inherited" provenance when the precise one is available).
     */
    @Test
    void classifyExtensionPrefersValueRuleMatchOverBlanketInheritance() {
        ResolvedDependency dep = extensionDep("io.quarkus", "quarkus-jdbc-h2");
        Map<String, List<String>> inheritedKeysByGa =
                Map.of("io.quarkus:quarkus-jdbc-h2", List.of("quarkus.datasource.h2.jdbc.url"));
        RootInheritance.Result inheritance = new RootInheritance.Result(
                Map.of("io.quarkus:quarkus-jdbc-h2",
                        Set.of(new RootInheritance.InheritedRoot("quarkus.datasource.", "io.quarkus:quarkus-agroal"))),
                Set.of());
        ValueRules.Match match = new ValueRules.Match("io.quarkus:quarkus-jdbc-h2", "quarkus.datasource.h2.db-kind",
                "h2", "quarkus.datasource.{name}.db-kind");

        ExtensionReport row = Analyzer.classifyExtension(dep, new ConfigRootProbe.Probe(), Map.of(), inheritedKeysByGa,
                inheritance, false, Map.of(), null, List.of(), match, null);

        assertThat(row.verdict()).isEqualTo(Verdict.USED_CONFIG);
        assertThat(row.configInherited()).isFalse();
        assertThat(row.configSource()).containsExactly(ConfigRootSource.VALUE_RULE);
    }

    /**
     * TASK-7, plan item 3 (the db-kind discrimination case): a suppression on this extension means its
     * family's selector key IS present in the config but picked a sibling, not this one -- the blanket
     * inherited signal must not be trusted, and the row falls through to SUSPECT (no other signal fires
     * here) with a note naming the selector and the values actually seen.
     */
    @Test
    void classifyExtensionSuppressesInheritedEvidenceAndFallsBackToSuspect() {
        ResolvedDependency dep = extensionDep("io.quarkus", "quarkus-jdbc-oracle");
        Map<String, List<String>> inheritedKeysByGa =
                Map.of("io.quarkus:quarkus-jdbc-oracle", List.of("quarkus.datasource.jdbc.metrics.enabled"));
        RootInheritance.Result inheritance = new RootInheritance.Result(
                Map.of("io.quarkus:quarkus-jdbc-oracle",
                        Set.of(new RootInheritance.InheritedRoot("quarkus.datasource.", "io.quarkus:quarkus-agroal"))),
                Set.of());
        ValueRules.Suppression suppression = new ValueRules.Suppression("io.quarkus:quarkus-jdbc-oracle",
                "quarkus.datasource.{name}.db-kind", Set.of("quarkus.datasource.h2.db-kind"), Set.of("h2"));

        ExtensionReport row = Analyzer.classifyExtension(dep, new ConfigRootProbe.Probe(), Map.of(), inheritedKeysByGa,
                inheritance, false, Map.of(), null, List.of(), null, suppression);

        assertThat(row.verdict()).isEqualTo(Verdict.SUSPECT);
        assertThat(row.configInherited()).isFalse();
        assertThat(row.note()).isEqualTo("family keys present but no selecting value matches "
                + "(selector quarkus.datasource.{name}.db-kind = [h2])");
    }

    /**
     * TASK-7, plan item 3: suppression only removes the INHERITED path -- a capability edge is a
     * different, independent signal and must still fire normally underneath a suppressed extension.
     */
    @Test
    void classifyExtensionSuppressionFallsBackToCapabilityWhenOneFires() {
        ResolvedDependency dep = extensionDep("io.quarkus", "quarkus-jdbc-oracle");
        Map<String, List<String>> inheritedKeysByGa =
                Map.of("io.quarkus:quarkus-jdbc-oracle", List.of("quarkus.datasource.jdbc.metrics.enabled"));
        RootInheritance.Result inheritance = new RootInheritance.Result(
                Map.of("io.quarkus:quarkus-jdbc-oracle",
                        Set.of(new RootInheritance.InheritedRoot("quarkus.datasource.", "io.quarkus:quarkus-agroal"))),
                Set.of());
        ValueRules.Suppression suppression = new ValueRules.Suppression("io.quarkus:quarkus-jdbc-oracle",
                "quarkus.datasource.{name}.db-kind", Set.of("quarkus.datasource.h2.db-kind"), Set.of("h2"));
        CapabilityJoin.Edge edge =
                new CapabilityJoin.Edge("io.quarkus:quarkus-agroal", "cap.example", "io.quarkus:quarkus-jdbc-oracle");
        Map<String, CapabilityJoin.Edge> capabilityNewlyUsed = Map.of("io.quarkus:quarkus-jdbc-oracle", edge);

        ExtensionReport row = Analyzer.classifyExtension(dep, new ConfigRootProbe.Probe(), Map.of(), inheritedKeysByGa,
                inheritance, false, capabilityNewlyUsed, null, List.of(), null, suppression);

        assertThat(row.verdict()).isEqualTo(Verdict.USED_CAPABILITY);
    }

    /** TASK-7: a plain jar (Stork static-list case) can be used-config via a value rule alone. */
    @Test
    void classifyPlainJarUsesValueRuleMatchWhenNotReferencedByBytecode() {
        ResolvedDependency dep = plainJarDep("io.smallrye.stork", "stork-service-discovery-static-list");
        ValueRules.Match match = new ValueRules.Match("io.smallrye.stork:stork-service-discovery-static-list",
                "quarkus.stork.hero-service.service-discovery.type", "static",
                "quarkus.stork.{name}.service-discovery.type");

        ExtensionReport row = Analyzer.classifyPlainJar(dep, Set.of(), Map.of(), match);

        assertThat(row.verdict()).isEqualTo(Verdict.USED_CONFIG);
        assertThat(row.quarkusExtension()).isFalse();
        assertThat(row.configSource()).containsExactly(ConfigRootSource.VALUE_RULE);
        assertThat(row.valueRuleEvidence()).isEqualTo(
                "selected by quarkus.stork.hero-service.service-discovery.type=static");
    }

    /** A direct bytecode reference is still the strongest signal, even when a value rule also matches. */
    @Test
    void classifyPlainJarBytecodeReferenceTakesPriorityOverValueRuleMatch() {
        ResolvedDependency dep = plainJarDep("io.smallrye.stork", "stork-service-discovery-static-list");
        Map<String, Analyzer.PlainJarScan> scans = Map.of("io.smallrye.stork:stork-service-discovery-static-list",
                Analyzer.PlainJarScan.ok(Set.of("io.smallrye.stork.servicediscovery.staticlist.Provider")));
        Set<String> asmReferenced = Set.of("io.smallrye.stork.servicediscovery.staticlist.Provider");
        ValueRules.Match match = new ValueRules.Match("io.smallrye.stork:stork-service-discovery-static-list",
                "quarkus.stork.hero-service.service-discovery.type", "static",
                "quarkus.stork.{name}.service-discovery.type");

        ExtensionReport row = Analyzer.classifyPlainJar(dep, asmReferenced, scans, match);

        assertThat(row.verdict()).isEqualTo(Verdict.USED_BYTECODE);
        assertThat(row.valueRuleEvidence()).isNull();
    }

    /** No bytecode reference and no value-rule match: stays suspect, unchanged pre-TASK-7 behavior. */
    @Test
    void classifyPlainJarStaysSuspectWithoutBytecodeOrValueRuleMatch() {
        ResolvedDependency dep = plainJarDep("io.grpc", "grpc-services");

        ExtensionReport row = Analyzer.classifyPlainJar(dep, Set.of(), Map.of(), null);

        assertThat(row.verdict()).isEqualTo(Verdict.SUSPECT);
        assertThat(row.valueRuleEvidence()).isNull();
    }
}
