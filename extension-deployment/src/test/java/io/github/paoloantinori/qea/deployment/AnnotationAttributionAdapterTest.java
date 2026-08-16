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
