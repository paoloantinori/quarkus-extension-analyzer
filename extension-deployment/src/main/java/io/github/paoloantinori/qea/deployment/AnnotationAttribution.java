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

import io.github.paoloantinori.qea.plugin.annotation.AnnotationConsumerRules;
import io.github.paoloantinori.qea.plugin.report.AnalysisReport;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.maven.dependency.ResolvedDependency;
import org.jboss.jandex.IndexView;

import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * The extension-form adapter for the annotation-consumer signal (TASK-28): the rules engine lives
 * in core ({@link AnnotationConsumerRules}, shared with the mojo form); this class only derives
 * the extension-form-specific inputs from the augmentation model (the directly-declared
 * runtime-extension GAs) and delegates.
 */
public final class AnnotationAttribution {

    private AnnotationAttribution() {
    }

    /**
     * Post-processes the report with the shared annotation-consumer engine.
     *
     * @param report the core Analyzer's report (may contain suspect annotation-consumer extensions)
     * @param beanIndex ArC's Jandex index of the app (authoritative: knows which annotations are used)
     * @param model the resolved ApplicationModel (to check the target GA is a declared extension)
     * @param dbKindValues the {@code quarkus.datasource[.<name>].db-kind} values present in the app
     *                    config (any profile), used by the TASK-23 disambiguation when multiple
     *                    reactive clients are declared. Empty set = no explicit db-kind.
     * @param projectRoot the root of the module being augmented (TASK-24): the FILE: rules probe
     *                   {@code src/main/resources} and {@code target/classes} under THIS root, not
     *                   the process CWD. {@code Path.of("")} preserves the legacy CWD-relative
     *                   behavior for callers that cannot derive a root.
     */
    public static AnalysisReport apply(AnalysisReport report, IndexView beanIndex, ApplicationModel model,
            Set<String> dbKindValues, Path projectRoot) {
        return AnnotationConsumerRules.apply(report, beanIndex, collectDeclaredExtensionGas(model),
                dbKindValues, projectRoot, collectDeploymentConsumers(model));
    }

    /**
     * The directly-declared runtime-extension GAs ({@code groupId:artifactId}): a rule fires only
     * for a declared target, so it never manufactures a verdict for an undeclared extension.
     */
    static Set<String> collectDeclaredExtensionGas(ApplicationModel model) {
        Set<String> gas = new TreeSet<>();
        for (ResolvedDependency d : model.getDependencies()) {
            if (d.isRuntimeExtensionArtifact() && d.isDirect()) {
                gas.add(d.getGroupId() + ":" + d.getArtifactId());
            }
        }
        return gas;
    }

    /**
     * TASK-38: suspect GA -> the extension whose DEPLOYMENT artifact directly declares the
     * suspect's -deployment artifact (the Keycloak shape: the runtime pom declares extensions
     * consumed by the server extension's deployment tree). Direct declarations only: each
     * deployment artifact's {@code getDependencies()} is its own POM's list, so a transitively
     * pulled -deployment would be misattributed to its intermediate carrier. Mirrors the mojo
     * shell's copy in IsolatedAnalyzerRunner (the derivation needs bootstrap types core must not
     * depend on); both copies are pinned by tests.
     */
    static java.util.Map<String, String> collectDeploymentConsumers(ApplicationModel model) {
        java.util.Map<String, String> consumers = new java.util.LinkedHashMap<>();
        for (ResolvedDependency d : model.getDependencies()) {
            if (!d.isDeploymentCp() || !d.getArtifactId().endsWith("-deployment")) {
                continue;
            }
            String consumerGa = d.getGroupId() + ":"
                    + d.getArtifactId().substring(0, d.getArtifactId().length() - "-deployment".length());
            for (io.quarkus.maven.dependency.ArtifactCoords dep : d.getDependencies()) {
                if (dep.getArtifactId().endsWith("-deployment")) {
                    String consumedGa = dep.getGroupId() + ":" + dep.getArtifactId()
                            .substring(0, dep.getArtifactId().length() - "-deployment".length());
                    consumers.putIfAbsent(consumedGa, consumerGa);
                }
            }
        }
        return consumers;
    }
}
