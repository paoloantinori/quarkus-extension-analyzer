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
package io.github.paoloantinori.qea.plugin.report;

import io.github.paoloantinori.qea.plugin.configroot.ConfigRootSource;
import io.github.paoloantinori.qea.plugin.configroot.RootInheritance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Not one of the plan's mandated pure-logic suites, but cheap insurance that Jackson serializes the
 *  record-based {@link AnalysisReport} model without extra configuration, and that the text renderer
 *  does not throw on a representative mix of verdicts. */
class ReporterTest {

    @Test
    void jsonReportRoundTripsExpectedFields(@TempDir Path dir) throws IOException {
        AnalysisReport report = sampleReport();

        Path target = dir.resolve("report.json");
        Reporter.writeJson(report, target);

        String json = Files.readString(target, StandardCharsets.UTF_8);
        assertThat(json).contains("\"io.quarkus:quarkus-agroal\"");
        assertThat(json).contains("\"used-config\"");
        assertThat(json).contains("\"usedConfig\" : 2");
        // Enum fields serialize to their kebab-case label (Verdict's convention), not the Java constant
        // name: "CONFIG_MODEL_JSON" must never appear in the JSON contract.
        assertThat(json).contains("\"config-model-json\"");
        assertThat(json).doesNotContain("CONFIG_MODEL_JSON");
        assertThat(json).contains("\"fromGa\" : \"io.quarkus:quarkus-agroal\"");
        // ignoreRecommendations (TASK-3): the suspect-exclusion guarantee itself is covered by
        // IgnoreRecommendationTest#excludesUsedBytecodeAndSuspectRows; this just checks the field
        // round-trips into the JSON contract.
        assertThat(json).contains("\"ignoreRecommendations\"");
        assertThat(json).contains("\"reason\" : \"used via an application configuration key under this "
                + "extension's own config root (quarkus.datasource.)\"");
        // TASK-5: the transitive-API evidence field round-trips under its own (camelCase, not kebab) name.
        assertThat(json).contains("\"bytecodeViaTransitiveApi\" : \"io.fabric8:kubernetes-client\"");
        // TASK-11: the shared-referenced-jars hint round-trips as {ga, alsoReachableFrom} objects.
        assertThat(json).contains("\"sharedReferencedJars\"");
        assertThat(json).contains("\"ga\" : \"jakarta.validation:jakarta.validation-api\"");
        assertThat(json).contains("\"alsoReachableFrom\" : [ \"io.quarkus:quarkus-apicurio-registry-avro\" ]");
        // TASK-10: extensions{}/plainJars{} split blocks round-trip alongside the combined summary block
        // kept for backward compatibility. sampleReport() has 5 extension rows (2 used-config, 2
        // used-bytecode, 1 suspect) and 1 plain-jar row (suspect).
        assertThat(json).contains("\"extensions\" : {");
        assertThat(json).contains("\"plainJars\" : {");
        assertThat(json).contains("\"summary\" : {");
    }

    @Test
    void summarySplitsExtensionsFromPlainJarsButKeepsCombinedTotal() {
        AnalysisReport report = sampleReport();

        assertThat(report.extensions())
                .isEqualTo(new AnalysisReport.Summary(2, 2, 0, 1, 5));
        assertThat(report.plainJars())
                .isEqualTo(new AnalysisReport.Summary(0, 0, 0, 1, 1));
        // Combined total is unaffected by the split: still every row (6), matching pre-TASK-10 behavior.
        assertThat(report.summary())
                .isEqualTo(new AnalysisReport.Summary(2, 2, 0, 2, 6));
    }

    @Test
    void textReportListsEveryDependencyUnderItsVerdictWithInheritedProvenance() {
        String text = Reporter.toText(sampleReport());

        assertThat(text).contains("used-config:");
        assertThat(text).contains("io.quarkus:quarkus-agroal");
        assertThat(text).contains("suspect:");
        assertThat(text).contains("io.quarkus:quarkus-scheduler");
        assertThat(text).contains("io.quarkus:quarkus-jdbc-h2 (inherited)");
        assertThat(text).contains("inherited from io.quarkus:quarkus-agroal: quarkus.datasource.");
        assertThat(text).doesNotContain("<-");
        // TASK-11: the shared-referenced-jars hint renders under the suspect row, naming the jar and the
        // other declared extension that also reaches it.
        assertThat(text).contains("hint          : project references shared jar "
                + "jakarta.validation:jakarta.validation-api, also reachable from "
                + "io.quarkus:quarkus-apicurio-registry-avro");
        assertThat(text).contains("used-bytecode:");
        assertThat(text).contains("io.quarkus:quarkus-kubernetes-client");
        assertThat(text).contains("referenced via transitive API of io.fabric8:kubernetes-client");
        // TASK-5 follow-up: exactly one bytecode line per row, "referenced from compiled classes" OR
        // "referenced via transitive API of <ga>", never both -- two used-bytecode rows in sampleReport()
        // (quarkus-jackson: own jar; quarkus-kubernetes-client: transitive only) means exactly two lines.
        assertThat(text).contains("io.quarkus:quarkus-jackson");
        assertThat(text).contains("referenced from compiled classes");
        assertThat(countOccurrences(text, "bytecode      :")).isEqualTo(2);
        // TASK-10: the extension-level summary line must appear before the plain-jars line, which must
        // appear before the combined line kept for compatibility.
        int extensionsAt = text.indexOf("extensions : used-bytecode = 2 | used-config = 2 | used-capability = 0 "
                + "| suspect = 1 | total = 5");
        int plainJarsAt = text.indexOf("plain jars : used-bytecode = 0 | used-config = 0 | used-capability = 0 "
                + "| suspect = 1 | total = 1");
        int combinedAt = text.indexOf("combined   : used-bytecode = 2 | used-config = 2 | used-capability = 0 "
                + "| suspect = 2 | total = 6");
        assertThat(extensionsAt).isPositive();
        assertThat(plainJarsAt).isGreaterThan(extensionsAt);
        assertThat(combinedAt).isGreaterThan(plainJarsAt);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static AnalysisReport sampleReport() {
        ExtensionReport used = new ExtensionReport("io.quarkus:quarkus-agroal", true, Verdict.USED_CONFIG, false,
                Set.of("quarkus.datasource."), List.of("quarkus.datasource.db-kind"),
                Set.of(ConfigRootSource.CONFIG_MODEL_JSON), List.of(), false, List.of(), null, null, null, List.of());
        ExtensionReport inherited = new ExtensionReport("io.quarkus:quarkus-jdbc-h2", true, Verdict.USED_CONFIG, true,
                Set.of("quarkus.datasource."), List.of("quarkus.datasource.db-kind"),
                Set.of(ConfigRootSource.INHERITED),
                List.of(new RootInheritance.InheritedRoot("quarkus.datasource.", "io.quarkus:quarkus-agroal")),
                false, List.of(), null, null, null, List.of());
        // TASK-11: a suspect row carrying the shared-referenced-jars hint (hibernate-validator/
        // jakarta.validation-api bench case) -- reachable from this extension's subtree but also from
        // quarkus-apicurio-registry-avro, so exclusive attribution refuses it, yet the project's bytecode
        // does reference it.
        ExtensionReport suspect = new ExtensionReport("io.quarkus:quarkus-scheduler", true, Verdict.SUSPECT, false,
                Set.of("quarkus.scheduler."), List.of(), Set.of(ConfigRootSource.EXTENSION_YAML), List.of(), false,
                List.of(), "config roots known, but no application key falls under them", null, null,
                List.of(new ExtensionReport.SharedReferencedJar("jakarta.validation:jakarta.validation-api",
                        List.of("io.quarkus:quarkus-apicurio-registry-avro"))));
        // TASK-5: an extension whose own jar was never referenced, but whose exclusive transitive
        // non-Quarkus API jar was, becomes used-bytecode with bytecodeViaTransitiveApi carrying the jar's
        // ga as evidence.
        ExtensionReport viaTransitiveApi = new ExtensionReport("io.quarkus:quarkus-kubernetes-client", true,
                Verdict.USED_BYTECODE, false, Set.of(), List.of(), Set.of(), List.of(), true, List.of(), null,
                "io.fabric8:kubernetes-client", null, List.of());
        // TASK-5 follow-up: an extension whose own runtime artifact IS referenced never carries
        // bytecodeViaTransitiveApi (Analyzer skips transitive attribution for it entirely).
        ExtensionReport ownJarReferenced = new ExtensionReport("io.quarkus:quarkus-jackson", true,
                Verdict.USED_BYTECODE, false, Set.of(), List.of(), Set.of(), List.of(), true, List.of(), null, null,
                null, List.of());
        // TASK-10: a plain-jar (quarkusExtension = false) row, so extensions{}/plainJars{} split into
        // distinct, non-trivial counts. classifyPlainJar only ever yields USED_BYTECODE, USED_CONFIG
        // (TASK-7 value rule) or SUSPECT.
        ExtensionReport plainJarSuspect = new ExtensionReport("io.grpc:grpc-services", false, Verdict.SUSPECT, false,
                Set.of(), List.of(), Set.of(), List.of(), false, List.of(),
                "no bytecode reference found in compiled classes", null, null, List.of());
        List<ExtensionReport> rows = List.of(used, inherited, suspect, viaTransitiveApi, ownJarReferenced, plainJarSuspect);
        List<ExtensionReport> extensionRows = List.of(used, inherited, suspect, viaTransitiveApi, ownJarReferenced);
        List<ExtensionReport> plainJarRows = List.of(plainJarSuspect);
        AnalysisReport.Summary extensions = AnalysisReport.Summary.of(extensionRows);
        AnalysisReport.Summary plainJars = AnalysisReport.Summary.of(plainJarRows);
        // Combined via Summary.combine(...), mirroring Analyzer.java's production code path, rather than
        // an independent Summary.of(rows) call -- so this fixture exercises the same code combine() does.
        return new AnalysisReport("io.apicurio:apicurio-registry-app:3.3.2-SNAPSHOT", "2026-08-01T00:00:00Z", rows,
                IgnoreRecommendation.of(rows), extensions, plainJars,
                AnalysisReport.Summary.combine(extensions, plainJars));
    }

    @Test
    void summaryCombineIsFieldWiseSumAndHandlesEmptySummaries() {
        AnalysisReport.Summary a = new AnalysisReport.Summary(1, 2, 3, 4, 10);
        AnalysisReport.Summary b = new AnalysisReport.Summary(5, 6, 7, 8, 26);

        assertThat(AnalysisReport.Summary.combine(a, b))
                .isEqualTo(new AnalysisReport.Summary(6, 8, 10, 12, 36));

        AnalysisReport.Summary empty = AnalysisReport.Summary.of(List.of());
        assertThat(empty).isEqualTo(new AnalysisReport.Summary(0, 0, 0, 0, 0));
        assertThat(AnalysisReport.Summary.combine(a, empty)).isEqualTo(a);
        assertThat(AnalysisReport.Summary.combine(empty, empty)).isEqualTo(empty);
    }
}
