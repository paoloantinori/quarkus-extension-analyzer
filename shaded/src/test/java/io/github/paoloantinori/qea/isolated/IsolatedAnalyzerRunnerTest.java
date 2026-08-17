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
package io.github.paoloantinori.qea.isolated;

import io.github.paoloantinori.qea.plugin.config.AppConfigReader;
import io.github.paoloantinori.qea.plugin.report.AnalysisReport;
import io.github.paoloantinori.qea.plugin.report.ExtensionReport;
import io.github.paoloantinori.qea.plugin.report.Verdict;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ExtensionCapabilities;
import io.quarkus.bootstrap.model.ExtensionDevModeConfig;
import io.quarkus.bootstrap.model.PlatformImports;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-28: unit pins for the mojo-side derivation feeding the shared annotation-consumer
 * engine - the declared-GA extraction (a copy of the extension adapter's logic; nothing else
 * watches it), the null-index fallback when the module has no compiled classes yet, and the
 * project-basedir forwarding the FILE: rules depend on.
 */
class IsolatedAnalyzerRunnerTest {

    @Test
    void reactiveJoinFiresViaTheNullIndexFallback(@TempDir Path moduleDir) throws IOException {
        // outputDirectory does not exist -> indexClasses returns null -> empty-index fallback;
        // the join (report-only evidence) must still credit the single declared client.
        MavenProject project = project(moduleDir, "no-such-classes");
        AnalysisReport report = report(
                row("io.quarkus:quarkus-hibernate-reactive-panache", Verdict.USED_BYTECODE),
                suspect("io.quarkus:quarkus-reactive-pg-client"));

        AnalysisReport out = IsolatedAnalyzerRunner.applyAnnotationConsumers(
                modelOf(dep("io.quarkus", "quarkus-reactive-pg-client", true, true)),
                project, AppConfigReader.empty(), report);

        assertThat(rowOf(out, "io.quarkus:quarkus-reactive-pg-client").verdict())
                .isEqualTo(Verdict.USED_BYTECODE);
    }

    @Test
    void nonDirectDependenciesAreNotCredited(@TempDir Path moduleDir) throws IOException {
        MavenProject project = project(moduleDir, "no-such-classes");
        AnalysisReport report = report(suspect("io.quarkus:quarkus-reactive-pg-client"));

        AnalysisReport out = IsolatedAnalyzerRunner.applyAnnotationConsumers(
                modelOf(dep("io.quarkus", "quarkus-reactive-pg-client", true, false)),
                project, AppConfigReader.empty(), report);

        assertThat(rowOf(out, "io.quarkus:quarkus-reactive-pg-client").verdict()).isEqualTo(Verdict.SUSPECT);
    }

    @Test
    void fileRuleResolvesUnderTheProjectBasedir(@TempDir Path withYml, @TempDir Path without)
            throws IOException {
        Files.createDirectories(withYml.resolve(Path.of("src", "main", "resources")));
        Files.writeString(withYml.resolve(Path.of("src", "main", "resources", "application.yml")), "quarkus: {}\n");
        AnalysisReport report = report(suspect("io.quarkus:quarkus-config-yaml"));
        ApplicationModel model = modelOf(dep("io.quarkus", "quarkus-config-yaml", true, true));

        AnalysisReport credited = IsolatedAnalyzerRunner.applyAnnotationConsumers(
                model, project(withYml, "no-such-classes"), AppConfigReader.empty(), report);
        AnalysisReport notCredited = IsolatedAnalyzerRunner.applyAnnotationConsumers(
                model, project(without, "no-such-classes"), AppConfigReader.empty(), report);

        assertThat(rowOf(credited, "io.quarkus:quarkus-config-yaml").verdict()).isEqualTo(Verdict.USED_BYTECODE);
        assertThat(rowOf(notCredited, "io.quarkus:quarkus-config-yaml").verdict()).isEqualTo(Verdict.SUSPECT);
    }

    @Test
    void deploymentConsumerCreditsTheSuspect(@TempDir Path moduleDir) throws IOException {
        // TASK-38, mojo-shell copy of the derivation: keycloak-server-deployment declares
        // rest-jackson-deployment, so the rest-jackson suspect row CREDITS (the extension
        // descriptor enforces the runtime counterpart's declaration; ablation-verified).
        ApplicationModel model = modelOf(
                deploymentDep("org.keycloak", "keycloak-quarkus-server-deployment",
                        "io.quarkus:quarkus-rest-jackson-deployment"),
                dep("io.quarkus", "quarkus-rest-jackson", true, true));
        AnalysisReport report = report(suspect("io.quarkus:quarkus-rest-jackson"));

        AnalysisReport out = IsolatedAnalyzerRunner.applyAnnotationConsumers(
                model, project(moduleDir, "no-such-classes"), AppConfigReader.empty(), report);

        assertThat(rowOf(out, "io.quarkus:quarkus-rest-jackson").verdict()).isEqualTo(Verdict.USED_BYTECODE);
        assertThat(rowOf(out, "io.quarkus:quarkus-rest-jackson").note()).contains("deployment-consumer")
                .contains("org.keycloak:keycloak-quarkus-server");
    }

    @Test
    void ownDeploymentSiblingEdgesCreditFromTheExtensionModule(@TempDir Path reactorDir)
            throws IOException {
        // TASK-39: the shape no dependency model can see. The workspace is
        // reactor/{app,app-deployment}; app-deployment's POM declares
        // quarkus-rest-jackson-deployment, so analyzing "app" (whose model contains NO deployment
        // artifacts at all) must still credit the rest-jackson suspect via the sibling scan.
        Path appDir = Files.createDirectories(reactorDir.resolve("app"));
        Path depDir = Files.createDirectories(reactorDir.resolve("app-deployment"));
        Files.writeString(appDir.resolve("pom.xml"),
                "<project><groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version></project>");
        Files.writeString(depDir.resolve("pom.xml"), """
                <project>
                  <groupId>com.acme</groupId>
                  <artifactId>app-deployment</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>io.quarkus</groupId>
                      <artifactId>quarkus-rest-jackson-deployment</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        MavenProject project = project(appDir, "no-such-classes");
        org.apache.maven.model.Model parentModel = new org.apache.maven.model.Model();
        parentModel.addModule("app");
        parentModel.addModule("app-deployment");
        MavenProject parent = new MavenProject(parentModel);
        parent.setGroupId("com.acme");
        project.setParent(parent);

        ApplicationModel model = modelOf(dep("io.quarkus", "quarkus-rest-jackson", true, true));
        AnalysisReport report = report(suspect("io.quarkus:quarkus-rest-jackson"));

        AnalysisReport out = IsolatedAnalyzerRunner.applyAnnotationConsumers(
                model, project, AppConfigReader.empty(), report);

        assertThat(rowOf(out, "io.quarkus:quarkus-rest-jackson").verdict()).isEqualTo(Verdict.USED_BYTECODE);
        assertThat(rowOf(out, "io.quarkus:quarkus-rest-jackson").note()).contains("deployment-consumer")
                .contains("com.acme:app");
    }

    @Test
    void noDeploymentSiblingChangesNothing(@TempDir Path reactorDir) throws IOException {
        // A workspace with no *-deployment sibling: no sibling edges, the plain model-based
        // path still applies (here: nothing credits).
        Path appDir = Files.createDirectories(reactorDir.resolve("app"));
        Files.writeString(appDir.resolve("pom.xml"),
                "<project><groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version></project>");
        Path otherDir = Files.createDirectories(reactorDir.resolve("unrelated"));
        Files.writeString(otherDir.resolve("pom.xml"),
                "<project><groupId>com.acme</groupId><artifactId>unrelated</artifactId><version>1.0</version></project>");
        MavenProject project = project(appDir, "no-such-classes");
        org.apache.maven.model.Model parentModel = new org.apache.maven.model.Model();
        parentModel.addModule("app");
        parentModel.addModule("unrelated");
        project.setParent(new MavenProject(parentModel));

        ApplicationModel model = modelOf(dep("io.quarkus", "quarkus-rest-jackson", true, true));
        AnalysisReport report = report(suspect("io.quarkus:quarkus-rest-jackson"));

        AnalysisReport out = IsolatedAnalyzerRunner.applyAnnotationConsumers(
                model, project, AppConfigReader.empty(), report);

        assertThat(rowOf(out, "io.quarkus:quarkus-rest-jackson").verdict()).isEqualTo(Verdict.SUSPECT);
    }

    private static ResolvedDependency deploymentDep(String g, String a, String depCoords) {
        return ResolvedDependencyBuilder.newInstance()
                .setGroupId(g).setArtifactId(a).setVersion("1.0").setDeploymentCp()
                .addDependency(io.quarkus.maven.dependency.ArtifactCoords.fromString(depCoords + ":1.0"))
                .build();
    }

    // --- fixtures -----------------------------------------------------------------------------------

    private static MavenProject project(Path basedir, String outputDirName) throws IOException {
        Model model = new Model();
        model.setGroupId("com.acme");
        model.setArtifactId("app");
        model.setVersion("1.0");
        MavenProject project = new MavenProject(model);
        Path pom = basedir.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        project.setFile(pom.toFile());
        Build build = new Build();
        build.setOutputDirectory(basedir.resolve(outputDirName).toString());
        project.setBuild(build);
        return project;
    }

    /** dep(g, a, runtimeExtension, direct). */
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

    private static ExtensionReport suspect(String ga) {
        return row(ga, Verdict.SUSPECT);
    }

    private static ExtensionReport row(String ga, Verdict verdict) {
        return new ExtensionReport(ga, true, verdict, false, Set.of(), List.of(), Set.of(), List.of(),
                false, List.of(), null, null, null, List.of(), List.of());
    }

    private static AnalysisReport report(ExtensionReport... rows) {
        AnalysisReport.Summary ext = AnalysisReport.Summary.of(List.of(rows));
        return new AnalysisReport("test:app:1", "now", List.of(rows), List.of(), ext,
                AnalysisReport.Summary.of(List.of()), AnalysisReport.Summary.combine(ext,
                        AnalysisReport.Summary.of(List.of())));
    }

    private static ExtensionReport rowOf(AnalysisReport report, String ga) {
        return report.dependencies().stream().filter(r -> r.ga().equals(ga)).findFirst().orElseThrow();
    }
}
