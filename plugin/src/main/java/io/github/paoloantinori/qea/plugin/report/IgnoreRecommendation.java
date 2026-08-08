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

import java.util.ArrayList;
import java.util.List;

/**
 * One extension worth adding to an adopter's existing ignore-list, per TASK-3: the {@link
 * AnalysisReport#ignoreRecommendations()} entry backing both the JSON contract (for CI consumers
 * that want to build their own fragment format) and the generated {@link IgnoreFragments} XML.
 *
 * @param ga      {@code groupId:artifactId}
 * @param verdict always {@link Verdict#USED_CONFIG} or {@link Verdict#USED_CAPABILITY}, per {@link
 *                #of}'s scoping rule
 * @param reason  a human-readable sentence explaining why, derived from the same evidence as the
 *                {@link ExtensionReport} row
 */
public record IgnoreRecommendation(String ga, Verdict verdict, String reason) {

    /**
     * Derives the ignore-list recommendations from a completed analysis (mirrors {@link
     * AnalysisReport.Summary#of}: a static factory folding many rows into the derived shape).
     *
     * <p>Scoped to {@link Verdict#USED_CONFIG} and {@link Verdict#USED_CAPABILITY} only:
     * {@code used-bytecode} extensions are already visible to bytecode-based analyzers (that is
     * exactly the signal those tools use), so recommending them adds no information. {@code suspect}
     * entries are deliberately excluded -- recommending to ignore an extension this tool could not
     * itself justify as used would defeat its purpose.
     */
    public static List<IgnoreRecommendation> of(List<ExtensionReport> rows) {
        List<IgnoreRecommendation> out = new ArrayList<>();
        for (ExtensionReport r : rows) {
            if (r.verdict() == Verdict.USED_CONFIG || r.verdict() == Verdict.USED_CAPABILITY) {
                out.add(new IgnoreRecommendation(r.ga(), r.verdict(), reasonOf(r)));
            }
        }
        return out;
    }

    private static String reasonOf(ExtensionReport r) {
        if (r.verdict() == Verdict.USED_CAPABILITY) {
            // Analyzer#classifyExtension only assigns USED_CAPABILITY when capabilityEdge != null,
            // which always yields a one-element capabilityEvidence list: no empty-evidence case can
            // reach this branch in production.
            return r.capabilityEvidence().get(0);
        }
        String roots = r.configRoots().isEmpty() ? "" : " (" + String.join(", ", r.configRoots()) + ")";
        return r.configInherited()
                ? "used via an application configuration key inherited under a related extension's config root"
                        + roots
                : "used via an application configuration key under this extension's own config root" + roots;
    }
}
