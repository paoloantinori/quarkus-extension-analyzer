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
package io.github.paoloantinori.qea.deployment;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ExtensionCapabilities;
import io.quarkus.bootstrap.model.ExtensionDevModeConfig;
import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-28: the extension-form adapter. The rules engine lives in core
 * (AnnotationConsumerRules, covered there by its behavioral suite); this pins the only
 * extension-form-specific derivation, the declared-extension GA set the engine is gated on.
 */
class AnnotationAttributionAdapterTest {

    @Test
    void declaredGasIncludesOnlyDirectRuntimeExtensionArtifacts() {
        ApplicationModel model = modelOf(
                dep("io.quarkus", "quarkus-rest", true, true),
                dep("io.quarkus", "quarkus-hibernate-validator", true, true),
                dep("io.smallrye.reactive", "mutiny", false, true),
                dep("io.quarkus", "quarkus-arc", true, false));

        assertThat(AnnotationAttribution.collectDeclaredExtensionGas(model)).containsExactlyInAnyOrder(
                "io.quarkus:quarkus-rest", "io.quarkus:quarkus-hibernate-validator");
    }

    @Test
    void emptyModelYieldsEmptyDeclaredGas() {
        assertThat(AnnotationAttribution.collectDeclaredExtensionGas(modelOf())).isEmpty();
    }

    @Test
    void deploymentConsumersMapsDirectDeploymentEdgesOnly() {
        // TASK-38: keycloak-server-deployment declares rest-jackson-deployment (direct edge ->
        // mapped); carrier-deployment declares mid-deployment but NOT rest-jackson-deployment, so
        // the transitive rest-jackson edge through mid must NOT be misattributed to carrier.
        ApplicationModel model = modelOf(
                deployment("org.keycloak", "keycloak-quarkus-server-deployment",
                        io.quarkus.maven.dependency.ArtifactCoords.fromString(
                                "io.quarkus:quarkus-rest-jackson-deployment:1.0")),
                deployment("io.quarkus", "quarkus-rest-jackson-deployment",
                        io.quarkus.maven.dependency.ArtifactCoords.fromString(
                                "io.quarkus:quarkus-core-deployment:1.0")),
                deployment("com.acme", "carrier-deployment",
                        io.quarkus.maven.dependency.ArtifactCoords.fromString(
                                "io.acme:mid-deployment:1.0")),
                deployment("io.acme", "mid-deployment",
                        io.quarkus.maven.dependency.ArtifactCoords.fromString(
                                "io.quarkus:quarkus-rest-jackson-deployment:1.0")));

        assertThat(AnnotationAttribution.collectDeploymentConsumers(model)).containsEntry(
                "io.quarkus:quarkus-rest-jackson", "org.keycloak:keycloak-quarkus-server");
    }

    @Test
    void runtimeArtifactsAreIgnoredForDeploymentConsumers() {
        // A runtime artifact whose pom lists a -deployment GA (unusual but possible) must not
        // create an edge: only deployment-artifact consumers count.
        ApplicationModel model = modelOf(
                dep("io.quarkus", "quarkus-rest", true, true));
        assertThat(AnnotationAttribution.collectDeploymentConsumers(model)).isEmpty();
    }

    private static ResolvedDependency deployment(String g, String a,
            io.quarkus.maven.dependency.ArtifactCoords... deps) {
        var b = ResolvedDependencyBuilder.newInstance()
                .setGroupId(g).setArtifactId(a).setVersion("1.0").setDeploymentCp();
        for (var d : deps) {
            b.addDependency(d);
        }
        return b.build();
    }

    @Test
    void applyDelegatesEndToEndWithCorrectArgumentOrder(@TempDir java.nio.file.Path withYml)
            throws IOException {
        // End-to-end through the adapter (the delegation passes five positional args, two of them
        // Set<String>: a transposed-argument mutation compiles clean, so only a behavioral pin
        // catches it). The FILE: path needs no compiled fixtures: an empty index plus a yml under
        // the passed root must credit config-yaml; an empty root must not.
        java.nio.file.Files.createDirectories(withYml.resolve(java.nio.file.Path.of("src", "main", "resources")));
        java.nio.file.Files.writeString(
                withYml.resolve(java.nio.file.Path.of("src", "main", "resources", "application.yml")), "q: v\n");
        org.jboss.jandex.IndexView emptyIndex = new org.jboss.jandex.Indexer().complete();
        ApplicationModel model = modelOf(dep("io.quarkus", "quarkus-config-yaml", true, true));

        io.github.paoloantinori.qea.plugin.report.AnalysisReport report =
                new io.github.paoloantinori.qea.plugin.report.AnalysisReport("test:app:1", "now",
                        List.of(new io.github.paoloantinori.qea.plugin.report.ExtensionReport(
                                "io.quarkus:quarkus-config-yaml", true,
                                io.github.paoloantinori.qea.plugin.report.Verdict.SUSPECT, false, Set.of(),
                                List.of(), Set.of(), List.of(), false, List.of(), null, null, null,
                                List.of(), List.of())),
                        List.of(), null, null, null);

        var credited = AnnotationAttribution.apply(report, emptyIndex, model, Set.of("some-db-kind"),
                withYml);
        var notCredited = AnnotationAttribution.apply(report, emptyIndex, model, Set.of(),
                java.nio.file.Path.of("no-such-root"));

        assertThat(credited.dependencies().get(0).verdict())
                .isEqualTo(io.github.paoloantinori.qea.plugin.report.Verdict.USED_BYTECODE);
        assertThat(credited.dependencies().get(0).note()).contains("FILE:application.yml");
        assertThat(notCredited.dependencies().get(0).verdict())
                .isEqualTo(io.github.paoloantinori.qea.plugin.report.Verdict.SUSPECT);
    }

    /** dep(g,a, runtimeExtension, direct). */
    private static ResolvedDependency dep(String g, String a, boolean runtimeExtension, boolean direct) {
        var b = ResolvedDependencyBuilder.newInstance()
                .setGroupId(g).setArtifactId(a).setVersion("1.0");
        if (runtimeExtension) {
            b.setRuntimeExtensionArtifact();
        }
        return b.setDirect(direct).build();
    }

    private static ApplicationModel modelOf(ResolvedDependency... deps) {
        List<ResolvedDependency> list = List.of(deps);
        return new ApplicationModel() {
            @Override public ResolvedDependency getAppArtifact() { return null; }
            @Override public Collection<ResolvedDependency> getDependencies() { return list; }
            @Override public Iterable<ResolvedDependency> getDependencies(int flags) { return list; }
            @Override public Iterable<ResolvedDependency> getDependenciesWithAnyFlag(int flags) { return list; }
            @Override public Collection<ResolvedDependency> getRuntimeDependencies() { return list; }
            @Override public PlatformImports getPlatforms() { return null; }
            @Override public Collection<ExtensionCapabilities> getExtensionCapabilities() { return List.of(); }
            @Override public Set<ArtifactKey> getParentFirst() { return Set.of(); }
            @Override public Set<ArtifactKey> getRunnerParentFirst() { return Set.of(); }
            @Override public Set<ArtifactKey> getLowerPriorityArtifacts() { return Set.of(); }
            @Override public Set<ArtifactKey> getReloadableWorkspaceDependencies() { return Set.of(); }
            @Override public Map<ArtifactKey, Set<String>> getRemovedResources() { return Map.of(); }
            @Override public Collection<ExtensionDevModeConfig> getExtensionDevModeConfig() { return List.of(); }
        };
    }
}
