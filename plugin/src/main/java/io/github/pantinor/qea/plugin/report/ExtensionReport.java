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

import io.github.pantinor.qea.plugin.configroot.ConfigRootSource;
import io.github.pantinor.qea.plugin.configroot.RootInheritance;

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
 *                            io.github.pantinor.qea.plugin.configroot.RootInheritance} rather than a
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
        String bytecodeViaTransitiveApi) {
}
