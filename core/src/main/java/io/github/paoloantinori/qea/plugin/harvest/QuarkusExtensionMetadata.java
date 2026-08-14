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
package io.github.paoloantinori.qea.plugin.harvest;

import io.github.paoloantinori.qea.plugin.configroot.ConfigRootProbe;
import io.github.paoloantinori.qea.plugin.configroot.ConfigRootSource;
import io.github.paoloantinori.qea.plugin.deploymentvocab.DeploymentVocabulary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * TASK-18: the public harvest facade over the internal probes. Gives third-party tools (IDE
 * plugins, CI checks, other analyzers) a single, stable entry point to the extension metadata this
 * project can extract from a Quarkus extension jar WITHOUT running augmentation:
 *
 * <ul>
 *   <li>config roots (from the yaml descriptor, the config-doc model, and {@code @ConfigMapping}/
 *       {@code @ConfigRoot} annotations, with per-source provenance)
 *   <li>the {@code -deployment} artifact GAV declared by the runtime jar
 *   <li>the extension dependencies declared in the yaml descriptor
 *   <li>the referenced-type vocabulary of a deployment jar (TASK-8)
 *   <li>the classes physically contained in the jar
 * </ul>
 *
 * <p>This is the "Quarkus-aware extension-metadata harvest library" from TASK-18, living inside
 * the core module rather than a separate repository: the API is young and has no external
 * consumers yet, so a separate release train would freeze it prematurely. Extracting to its own
 * project is the natural next step once real consumers exist.
 *
 * <p>All probes degrade gracefully: a non-jar or unreadable path yields empty sets and a recorded
 * error rather than throwing (mirroring {@link ConfigRootProbe}'s isolation discipline).
 */
public final class QuarkusExtensionMetadata {

    private QuarkusExtensionMetadata() {
    }

    /** The harvested metadata of one extension runtime jar. */
    public record Result(
            /** Config-root prefixes the extension claims, union of all sources (each ends with '.'). */
            Set<String> configRoots,
            /** Which source(s) contributed each root: yaml descriptor, config-doc json, annotations. */
            Set<ConfigRootSource> configRootSources,
            /** {@code groupId:artifactId:version} of the {@code -deployment} artifact, or null. */
            String deploymentArtifactGav,
            /** Extension GAs this extension declares as dependencies (yaml metadata). */
            Set<String> extensionDependencies,
            /** Classes physically contained in the probed jar(s). */
            Set<String> containedClasses,
            /** Per-source errors encountered, empty when everything parsed. */
            List<String> errors) {
    }

    /**
     * Harvests the metadata of one extension runtime jar (and its {@code -deployment} jar when the
     * runtime jar declares one and {@code deploymentJar} is given).
     *
     * @param runtimeJar     the extension runtime jar ({@code quarkus-xyz-<v>.jar})
     * @param deploymentJar  the matching {@code -deployment} jar, or {@code null} to probe the
     *                       runtime jar only (no config-root annotations from the deployment module)
     */
    public static Result harvest(Path runtimeJar, Path deploymentJar) {
        Set<Path> runtimePaths = Files.isRegularFile(runtimeJar) ? Set.of(runtimeJar) : Set.of();
        ConfigRootProbe.Probe probe = ConfigRootProbe.probe(runtimePaths,
                deploymentJar == null ? null : gav -> Files.isRegularFile(deploymentJar)
                        ? Set.of(deploymentJar)
                        : Set.of());
        return new Result(
                probe.roots(),
                probe.sourcesOf(probe.roots()),
                probe.deploymentArtifactGav,
                Set.copyOf(probe.extensionDependencies),
                Set.copyOf(probe.containedClasses),
                probe.error == null ? List.of() : List.of(probe.error));
    }

    /**
     * The referenced-type vocabulary of a deployment jar (TASK-8): every type its build-step
     * classes reference. Empty for a null/unreadable path, never throws.
     */
    public static Set<String> deploymentVocabulary(Path deploymentJar) {
        return DeploymentVocabulary.vocabularyOf(deploymentJar);
    }
}
