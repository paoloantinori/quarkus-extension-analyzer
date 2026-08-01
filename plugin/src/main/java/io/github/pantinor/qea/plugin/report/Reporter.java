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
package io.github.pantinor.qea.plugin.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.pantinor.qea.plugin.configroot.RootInheritance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Renders an {@link AnalysisReport} as human-readable text or as JSON, for CI consumption. */
public final class Reporter {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Reporter() {
    }

    public static String toText(AnalysisReport report) {
        Map<Verdict, List<ExtensionReport>> byVerdict = report.dependencies().stream()
                .collect(Collectors.groupingBy(ExtensionReport::verdict));

        StringBuilder sb = new StringBuilder();
        sb.append("=".repeat(120)).append('\n');
        sb.append("quarkus-extension-analyzer :: analyze\n");
        sb.append("  application : ").append(report.applicationArtifact()).append('\n');
        sb.append("=".repeat(120)).append('\n');
        for (Verdict v : Verdict.values()) {
            sb.append('\n').append(v.label()).append(":\n");
            for (ExtensionReport r : byVerdict.getOrDefault(v, List.of())) {
                sb.append("  ").append(r.ga());
                if (!r.quarkusExtension()) {
                    sb.append(" (plain jar)");
                }
                if (r.configInherited()) {
                    sb.append(" (inherited)");
                }
                sb.append('\n');
                if (!r.configRoots().isEmpty()) {
                    sb.append("      config roots  : ").append(String.join(", ", r.configRoots())).append('\n');
                }
                for (RootInheritance.InheritedRoot ir : r.inheritedRoots()) {
                    sb.append("      inherited from ").append(ir.fromGa()).append(": ").append(ir.root())
                            .append('\n');
                }
                if (!r.configMatchedKeys().isEmpty()) {
                    sb.append("      config keys   : ").append(String.join(", ", r.configMatchedKeys())).append('\n');
                }
                // Exactly one bytecode line, never both: bytecodeViaTransitiveApi is the more specific
                // reason and takes precedence when present (per its contract, it is only ever set when
                // this extension's own runtime artifact was NOT referenced -- see Analyzer's TASK-5
                // skip-scanning logic and ExtensionReport#bytecodeViaTransitiveApi's javadoc).
                if (r.bytecodeViaTransitiveApi() != null) {
                    sb.append("      bytecode      : referenced via transitive API of ")
                            .append(r.bytecodeViaTransitiveApi()).append('\n');
                } else if (r.bytecodeReferenced()) {
                    sb.append("      bytecode      : referenced from compiled classes\n");
                }
                if (!r.capabilityEvidence().isEmpty()) {
                    sb.append("      capability    : ").append(String.join("; ", r.capabilityEvidence())).append('\n');
                }
                if (r.note() != null && !r.note().isBlank()) {
                    sb.append("      note          : ").append(r.note()).append('\n');
                }
                // TASK-11: one line per shared-referenced-jar hint -- suspect rows only, per
                // ExtensionReport#sharedReferencedJars's contract; never implies a different verdict.
                for (ExtensionReport.SharedReferencedJar hint : r.sharedReferencedJars()) {
                    sb.append("      hint          : project references shared jar ").append(hint.ga())
                            .append(", also reachable from ")
                            .append(String.join(", ", hint.alsoReachableFrom())).append('\n');
                }
            }
        }
        // TASK-10: extension-level counts first -- that is the question this tool exists to answer --
        // then plain jars, then the combined total kept for backward compatibility. Reporting only the
        // combined line here previously let plain-jar suspects inflate the extension-level suspect count
        // (see docs/SECOND-BENCH.md).
        sb.append('\n').append("-".repeat(120)).append('\n');
        appendSummaryLine(sb, "extensions", report.extensions());
        appendSummaryLine(sb, "plain jars", report.plainJars());
        appendSummaryLine(sb, "combined", report.summary());
        return sb.toString();
    }

    private static void appendSummaryLine(StringBuilder sb, String label, AnalysisReport.Summary s) {
        sb.append(String.format(
                "%-10s : used-bytecode = %d | used-config = %d | used-capability = %d | suspect = %d | total = %d%n",
                label, s.usedBytecode(), s.usedConfig(), s.usedCapability(), s.suspect(), s.total()));
    }

    public static void writeJson(AnalysisReport report, Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        JSON.writeValue(target.toFile(), report);
    }
}
