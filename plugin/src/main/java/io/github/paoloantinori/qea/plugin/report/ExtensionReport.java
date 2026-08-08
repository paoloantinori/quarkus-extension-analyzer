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

import java.util.List;
import java.util.Set;

/**
 * One row of the classification report.
 *
 * <p>Schema convention: enum-typed fields ({@link #verdict}, {@link #configSource}) serialize to their
 * kebab-case label (e.g. {@code used-config}, {@code extension-yaml}) via {@code @JsonValue} on {@link
 * Verdict} / {@link ConfigRootSource}, not the Java constant name. This is the stable JSON contract.
 *
 * @param ga                  {@code groupId:artifactId}
 * @param quarkusExtension    whether this dependency is a Quarkus extension (three-signal
 *                            classification) or a plain jar (bytecode-only, per DESIGN.md)
 * @param verdict             the strongest signal that matched, or {@link Verdict#SUSPECT}
 * @param configInherited     whether the config-root match came from {@link
 *                            io.github.paoloantinori.qea.plugin.configroot.RootInheritance} rather than a
 *                            root the extension claims itself
 * @param configRoots         bare config-root prefixes credited to this extension (own or inherited);
 *                            provenance of an inherited root lives in {@link #inheritedRoots}, not here
 * @param configMatchedKeys   application-config keys attributed to this extension
 * @param configSource        which sources contributed the credited roots
 * @param inheritedRoots      when {@link #configInherited}, which extension each inherited root came
 *                            from; empty otherwise
 * @param bytecodeReferenced  whether the project's own compiled classes reference this dependency
 * @param capabilityEvidence  human-readable capability/extension-dependency join edges, if any
 * @param note                free-form extra evidence (e.g. "no config metadata found")
 * @param bytecodeViaTransitiveApi TASK-5: the {@code groupId:artifactId} of this extension's exclusive
 *                            transitive plain jar (reachable from no other declared extension and not
 *                            directly declared by the project) that the project's compiled classes
 *                            reference, or {@code null} when no such attribution fired. When set, it is
 *                            the reason {@link #bytecodeReferenced} is {@code true} for an extension whose
 *                            own runtime artifact was not itself referenced.
 * @param valueRuleEvidence   TASK-7: human-readable evidence ({@code "selected by <key>=<value>"}) when a
 *                            {@link io.github.paoloantinori.qea.plugin.configroot.ValueRules} entry matched
 *                            this dependency (extension OR plain jar), or {@code null} otherwise. Stronger
 *                            than blanket {@link io.github.paoloantinori.qea.plugin.configroot.RootInheritance}
 *                            evidence: when set, {@link #configSource} is {@code {value-rule}}.
 * @param sharedReferencedJars TASK-11: evidence hints for a {@link Verdict#SUSPECT} row only, never for
 *                            any other verdict -- always empty otherwise, mirroring the {@link
 *                            #capabilityEvidence}/{@link #configMatchedKeys} "empty list, not null"
 *                            convention. Each entry names a jar reachable from this extension's subtree
 *                            that {@link #bytecodeViaTransitiveApi} could not attribute (it is reachable
 *                            from 2+ declared extensions, so exclusive attribution would be a guess) but
 *                            that the project's compiled classes do reference. The hint informs human
 *                            triage of the suspect row; it never upgrades the verdict itself.
 */
public record ExtensionReport(
        String ga,
        boolean quarkusExtension,
        Verdict verdict,
        boolean configInherited,
        Set<String> configRoots,
        List<String> configMatchedKeys,
        Set<ConfigRootSource> configSource,
        List<RootInheritance.InheritedRoot> inheritedRoots,
        boolean bytecodeReferenced,
        List<String> capabilityEvidence,
        String note,
        String bytecodeViaTransitiveApi,
        String valueRuleEvidence,
        List<SharedReferencedJar> sharedReferencedJars) {

    /**
     * TASK-11: one shared jar this extension's subtree reaches that the project's compiled classes
     * reference, plus which OTHER declared extensions also reach it (the reason it was excluded from
     * {@link #bytecodeViaTransitiveApi}'s exclusive attribution in the first place).
     *
     * @param ga               {@code groupId:artifactId} of the shared jar
     * @param alsoReachableFrom the other declared extensions' GAs that also reach {@link #ga}, sorted;
     *                         never includes this row's own {@link ExtensionReport#ga}
     */
    public record SharedReferencedJar(String ga, List<String> alsoReachableFrom) {
    }
}
