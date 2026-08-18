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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.paoloantinori.qea.plugin.Analyzer;
import io.github.paoloantinori.qea.plugin.config.AppConfigReader;
import io.github.paoloantinori.qea.plugin.report.AnalysisReport;
import io.github.paoloantinori.qea.plugin.report.Reporter;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.resolver.BootstrapAppModelResolver;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalProject;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalWorkspace;
import io.quarkus.maven.dependency.ArtifactCoords;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.MavenWorkspaceReader;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TASK-20: the self-contained runner executed from inside the shaded jar. Everything Quarkus
 * (model resolution, the Analyzer's bootstrap types) is relocated by the shade plugin to
 * {@code io.github.paoloantinori.qea.internal.*}, so a project that exposes its own Quarkus
 * bootstrap classes into the Maven build realm (e.g. camel-quarkus ITs via quarkus-maven-plugin
 * as a build extension) cannot collide with the embedded version.
 *
 * <p>The public API uses ONLY JDK and Maven-API types (both shared through the parent realm), and
 * returns the report as JSON plus the text form, so no relocated class ever crosses the boundary.
 *
 * <p>Moved from {@code AnalyzeMojo} (the TASK-9 machinery: workspace load, chained reader,
 * resolver build) which becomes a thin shell over this runner.
 */
public final class IsolatedAnalyzerRunner {

    private IsolatedAnalyzerRunner() {
    }

    /**
     * Resolves the ApplicationModel and runs the analysis.
     *
     * @param session         the Maven session (shared Maven-API type)
     * @param project         the project being analyzed
     * @param repoSystem      injected Maven collaborator
     * @param remoteRepoManager injected Maven collaborator (see AnalyzeMojo's javadoc for why all three are needed)
     * @param settingsDecrypter injected Maven collaborator
     * @param classesDirs     the compiled-classes directories (target/classes, target/test-classes)
     * @param applicationConfig the application config file, or null for the default discovery
     * @param vocabularySignal TASK-8 opt-in signal flag
     * @param debugAttribution TASK-5 diagnostics: when {@code true}, the analyzer logs the
     *                        transitive-API BFS trace (visible under {@code mvn -X})
     * @param fragmentsDir    TASK-3 opt-in: when non-null, ignore-list XML fragments
     *                        (maven-dependency-plugin + DepClean) are written here
     * @return the serialized report bundle (JSON + text) for the mojo shell to consume
     * @throws IOException on model resolution or analysis failure (message carries the user guidance)
     */
    public static ReportBundle run(MavenSession session, MavenProject project, RepositorySystem repoSystem,
            RemoteRepositoryManager remoteRepoManager, SettingsDecrypter settingsDecrypter,
            List<Path> classesDirs, File applicationConfig, boolean vocabularySignal,
            boolean debugAttribution, Path fragmentsDir, boolean probe) throws IOException {
        ApplicationModel model = resolveModel(session, project, repoSystem, remoteRepoManager, settingsDecrypter);
        AppConfigReader appConfig = readAppConfig(project, applicationConfig);
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()));
        AnalysisReport report;
        try {
            report = new Analyzer(executor, debugAttribution ? System.out::println : null)
                    .analyze(model, classesDirs, appConfig, vocabularySignal);
        } finally {
            executor.shutdown();
        }
        report = applyAnnotationConsumers(model, project, appConfig, report);
        if (fragmentsDir != null) {
            io.github.paoloantinori.qea.plugin.report.IgnoreFragments
                    .writeFragments(report.ignoreRecommendations(), fragmentsDir);
        }
        String text = Reporter.toText(report);
        String verification = io.github.paoloantinori.qea.plugin.report.RuntimeVerificationPlan
                .plan(report.dependencies());
        if (!verification.isEmpty()) {
            text += "\n" + verification;
        }
        if (probe) {
            text += "\n" + probeSuspects(session, project, repoSystem, remoteRepoManager,
                    settingsDecrypter, model, report);
        }
        return new ReportBundle(Reporter.toJson(report), text);
    }

    /**
     * TASK-40 rung 4: the resolution probe (the bench's ablation methodology shipped as a tool
     * mode). For each SUSPECT extension, re-resolve the app model from the SAME direct
     * dependency list minus the suspect ({@code resolveUserDependencies}: the in-memory hook -
     * no pom mutation) and report the bootstrap's verdict. Honest scope: this is the RESOLUTION
     * authority (unsatisfied extension dependencies, capability conflicts); full augmentation
     * authority stays bench methodology.
     */
    private static String probeSuspects(MavenSession session, MavenProject project,
            RepositorySystem repoSystem, RemoteRepositoryManager remoteRepoManager,
            SettingsDecrypter settingsDecrypter, ApplicationModel model, AnalysisReport report)
            throws IOException {
        var suspects = io.github.paoloantinori.qea.plugin.report.RuntimeVerificationPlan
                .extensionSuspects(report.dependencies());
        if (suspects.isEmpty()) {
            return "probe: no extension suspects to probe.\n";
        }
        // One resolver for the whole loop: stateless w.r.t. the dependency list, and building it
        // here (outside the per-suspect try) keeps an infrastructure failure from being reported
        // as "removal BREAKS resolution" for a failure unrelated to any removal.
        BootstrapAppModelResolver resolver = buildResolver(session, project, repoSystem,
                remoteRepoManager, settingsDecrypter);
        var directDeps = model.getDependencies().stream()
                .filter(io.quarkus.maven.dependency.ResolvedDependency::isDirect)
                .toList();
        StringBuilder sb = new StringBuilder(
                "probe: re-resolving the app model without each suspect (resolution authority)\n");
        for (var s : suspects) {
            var deps = directDeps.stream()
                    .filter(d -> !(d.getGroupId() + ":" + d.getArtifactId()).equals(s.ga()))
                    .map(io.quarkus.maven.dependency.Dependency.class::cast)
                    .toList();
            try {
                resolver.resolveUserDependencies(
                        ArtifactCoords.jar(project.getGroupId(), project.getArtifactId(),
                                project.getVersion()),
                        deps);
                sb.append("probe: ").append(s.ga())
                        .append(" -> the model RESOLVES without it (removable at resolution level)\n");
            } catch (Exception e) {
                sb.append("probe: ").append(s.ga())
                        .append(" -> removal BREAKS resolution: ")
                        .append(String.valueOf(e.getMessage()).lines().findFirst().orElse(e.toString()))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    /** The serialized analysis output: JSON for tooling, text for the build log. */
    public record ReportBundle(String json, String text) {
    }

    /**
     * TASK-28: the annotation-consumer pass in the mojo form. The engine is shared with the
     * extension form (core's AnnotationConsumerRules); this derives the mojo-side inputs: the
     * app index built over the module's MAIN classes only (like the extension form's bean index,
     * test-only stubs excluded in both: a test-only @Path stub must not credit a serializer the
     * main code never uses, or the two forms would disagree; note the bytecode signal keeps its
     * wider main+test scope, as it always had), the declared-extension set from the
     * resolved model, the db-kind values from the app config, and the project root from the
     * MavenProject (always the module being analyzed, unlike the process CWD).
     */
    static AnalysisReport applyAnnotationConsumers(ApplicationModel model, MavenProject project,
            AppConfigReader appConfig, AnalysisReport report) throws IOException {
        // TASK-38: mirrors the extension adapter's AnnotationAttribution.collectDeploymentConsumers
        // (bootstrap types keep the derivation out of core); pinned by this module's runner test.
        org.jboss.jandex.Index appIndex = io.github.paoloantinori.qea.plugin.bytecode.BytecodeUsage
                .indexClasses(List.of(Paths.get(project.getBuild().getOutputDirectory())));
        // Mirrors the extension adapter's AnnotationAttribution.collectDeclaredExtensionGas
        // (the derivation cannot live in core: ResolvedDependency is a bootstrap type); both
        // copies are pinned by tests (AnnotationAttributionAdapterTest, this module's runner test).
        java.util.Set<String> declaredExtensionGas = new java.util.TreeSet<>();
        for (io.quarkus.maven.dependency.ResolvedDependency d : model.getDependencies()) {
            if (d.isRuntimeExtensionArtifact() && d.isDirect()) {
                declaredExtensionGas.add(d.getGroupId() + ":" + d.getArtifactId());
            }
        }
        // TASK-38: suspect GA -> consuming extension (its -deployment directly declares the
        // suspect's -deployment). Direct declarations only: getDependencies() is the artifact's own
        // POM list, so transitively pulled -deployment artifacts are not misattributed.
        // Values are FULL evidence lines (the engine is authority-agnostic).
        java.util.Map<String, String> evidenceByGa = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.List<Path>> deploymentJarsByGa = new java.util.LinkedHashMap<>();
        for (io.quarkus.maven.dependency.ResolvedDependency d : model.getDependencies()) {
            if (!d.isDeploymentCp() || !d.getArtifactId().endsWith("-deployment")) {
                continue;
            }
            String consumerGa = d.getGroupId() + ":"
                    + d.getArtifactId().substring(0, d.getArtifactId().length() - "-deployment".length());
            for (io.quarkus.maven.dependency.ArtifactCoords dep : d.getDependencies()) {
                if (dep.getArtifactId().endsWith("-deployment")) {
                    String consumedGa = dep.getGroupId() + ":" + dep.getArtifactId()
                            .substring(0, dep.getArtifactId().length() - "-deployment".length());
                    evidenceByGa.putIfAbsent(consumedGa,
                            "deployment-consumer: required by " + consumerGa
                                    + "'s deployment tree (the extension descriptor enforces the"
                                    + " runtime counterpart's declaration; removal fails the build)");
                }
            }
            // Collect for the build-step graph (TASK-40): runtime GA -> deployment artifact paths.
            var paths = d.getResolvedPaths();
            if (paths != null && !paths.isEmpty()) {
                deploymentJarsByGa.computeIfAbsent(consumerGa, k -> new java.util.ArrayList<>())
                        .addAll(paths.stream().toList());
            }
        }
        // TASK-40: the build-step producer/consumer graph over the same deployment artifacts -
        // the augmentation authority (a required item without its producer fails the build).
        try {
            evidenceByGa.putAll(io.github.paoloantinori.qea.plugin.buildsteps.BuildStepGraph
                    .producerEdges(deploymentJarsByGa));
        } catch (IOException e) {
            throw new IOException("build-step graph mining failed: " + e.getMessage(), e);
        }
        // TASK-39: the module's OWN deployment sibling (the shape no dependency model can see:
        // the -deployment module depends on the runtime module, never vice versa, so analyzing an
        // extension module standalone - keycloak quarkus/runtime - hides the deployment edges that
        // live in its sibling quarkus/deployment). Read the sibling's POM from the reactor via the
        // parent's module list and add its direct -deployment declarations with the analyzed
        // module itself as the consumer. Safe by the same descriptor enforcement TASK-38 proved:
        // the runtime pom MUST declare every -deployment the sibling declares, or the build fails.
        addOwnDeploymentSiblingEdges(evidenceByGa, project);

        return io.github.paoloantinori.qea.plugin.annotation.AnnotationConsumerRules.apply(report,
                appIndex != null ? appIndex : new org.jboss.jandex.Indexer().complete(),
                declaredExtensionGas,
                io.github.paoloantinori.qea.plugin.annotation.AnnotationConsumerRules.dbKindValues(appConfig),
                project.getBasedir().toPath(), evidenceByGa);
    }

    /**
     * TASK-39: credit edges from the analyzed module's own -deployment sibling. Discovers the
     * sibling through the parent POM's module list (no fuzzy directory scanning: only a module
     * whose artifactId is exactly {@code <analyzedArtifactId>-deployment} counts), reads its
     * direct {@code *-deployment} dependencies, and records consumer = the analyzed module's GA.
     */
    private static void addOwnDeploymentSiblingEdges(java.util.Map<String, String> deploymentConsumers,
            MavenProject project) {
        MavenProject parent = project.getParent();
        if (parent == null || parent.getModules() == null || project.getBasedir() == null) {
            return;
        }
        String siblingArtifact = project.getArtifactId() + "-deployment";
        Path parentDir = project.getBasedir().toPath().getParent();
        if (parentDir == null) {
            return;
        }
        for (String module : parent.getModules()) {
            Path siblingPom = parentDir.resolve(module).resolve("pom.xml");
            if (!Files.isRegularFile(siblingPom)) {
                continue;
            }
            try {
                var model = new org.apache.maven.model.io.xpp3.MavenXpp3Reader()
                        .read(java.io.InputStream.class.cast(java.nio.file.Files.newInputStream(siblingPom)));
                if (!siblingArtifact.equals(model.getArtifactId())) {
                    continue;
                }
                String consumerGa = (model.getGroupId() != null ? model.getGroupId()
                        : parent.getGroupId()) + ":" + project.getArtifactId();
                for (var dep : model.getDependencies()) {
                    if (dep.getArtifactId() != null && dep.getArtifactId().endsWith("-deployment")) {
                        String consumedGa = dep.getGroupId() + ":" + dep.getArtifactId()
                                .substring(0, dep.getArtifactId().length() - "-deployment".length());
                        deploymentConsumers.putIfAbsent(consumedGa,
                                "deployment-consumer: required by " + consumerGa
                                        + "'s deployment tree (the extension descriptor enforces the"
                                        + " runtime counterpart's declaration; removal fails the build)");
                    }
                }
                return; // the sibling is unique; stop at the first match
            } catch (Exception ignored) {
                // unreadable sibling pom: skip (the model-based edges still apply)
            }
        }
    }

    private static ApplicationModel resolveModel(MavenSession session, MavenProject project,
            RepositorySystem repoSystem, RemoteRepositoryManager remoteRepoManager,
            SettingsDecrypter settingsDecrypter) throws IOException {
        try {
            return buildResolver(session, project, repoSystem, remoteRepoManager, settingsDecrypter)
                    .resolveModel(
                            ArtifactCoords.jar(project.getGroupId(), project.getArtifactId(),
                                    project.getVersion()));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("failed to resolve the ApplicationModel for "
                    + project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion()
                    + "; build the module first (mvn compile) or install it into the local repository "
                    + "(mvn install)", e);
        }
    }

    /** The bootstrap resolver over the workspace-chained session (shared by analysis and probe). */
    private static BootstrapAppModelResolver buildResolver(MavenSession session, MavenProject project,
            RepositorySystem repoSystem, RemoteRepositoryManager remoteRepoManager,
            SettingsDecrypter settingsDecrypter) throws IOException {
        LocalProject currentProject;
        try {
            currentProject = LocalProject.loadWorkspace(project.getBasedir().toPath());
        } catch (BootstrapMavenException | RuntimeException e) {
            throw new IOException("failed to load the Maven workspace at " + project.getBasedir()
                    + "; check that the POM (and its parent chain, if any) is valid, or, for layouts the "
                    + "workspace loader cannot walk, install this module into the local repository first "
                    + "(mvn install)", e);
        }
        RepositorySystemSession repoSession = session.getRepositorySession();
        LocalWorkspace fallback = currentProject.getWorkspace();
        if (fallback != null) {
            WorkspaceReader reader = new ChainedWorkspaceReader(repoSession.getWorkspaceReader(), fallback);
            repoSession = new DefaultRepositorySystemSession(repoSession).setWorkspaceReader(reader);
        }
        try {
            MavenArtifactResolver mvn = MavenArtifactResolver.builder()
                    .setRepositorySystem(repoSystem)
                    .setRepositorySystemSession(repoSession)
                    .setRemoteRepositories(project.getRemoteProjectRepositories())
                    .setRemoteRepositoryManager(remoteRepoManager)
                    .setSettingsDecrypter(settingsDecrypter)
                    .setCurrentProject(currentProject)
                    .build();
            return new BootstrapAppModelResolver(mvn);
        } catch (BootstrapMavenException | RuntimeException e) {
            throw new IOException("failed to build the bootstrap resolver for "
                    + project.getGroupId() + ":" + project.getArtifactId(), e);
        }
    }

    private static AppConfigReader readAppConfig(MavenProject project, File applicationConfig) {
        Path resolved = applicationConfig != null ? applicationConfig.toPath()
                : defaultApplicationConfigPath(project);
        if (resolved == null || !Files.isRegularFile(resolved)) {
            return AppConfigReader.empty();
        }
        String name = resolved.getFileName().toString();
        try {
            return name.endsWith(".yaml") || name.endsWith(".yml")
                    ? AppConfigReader.readYaml(resolved)
                    : AppConfigReader.readProperties(resolved);
        } catch (IOException | RuntimeException e) {
            return AppConfigReader.empty();
        }
    }

    private static final List<String> APPLICATION_CONFIG_NAMES =
            List.of("application.properties", "application.yaml", "application.yml");

    private static Path defaultApplicationConfigPath(MavenProject project) {
        for (Resource resource : project.getBuild().getResources()) {
            Path found = findApplicationConfig(Paths.get(resource.getDirectory()));
            if (found != null) {
                return found;
            }
        }
        return findApplicationConfig(Paths.get(project.getBuild().getOutputDirectory()));
    }

    private static Path findApplicationConfig(Path dir) {
        for (String candidate : APPLICATION_CONFIG_NAMES) {
            Path p = dir.resolve(candidate);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Chains {@code primary} (Maven's own workspace reader) ahead of {@code fallback} (the Quarkus
     * {@code LocalWorkspace}); same semantics as the AnalyzeMojo's original inner class (TASK-9),
     * moved here so the mojo shell has no Quarkus imports at all.
     */
    private static final class ChainedWorkspaceReader implements MavenWorkspaceReader {

        private final WorkspaceReader primary;
        private final LocalWorkspace fallback;
        private final WorkspaceRepository repository;

        private ChainedWorkspaceReader(WorkspaceReader primary, LocalWorkspace fallback) {
            this.primary = primary;
            this.fallback = fallback;
            Object key = primary == null ? fallback.getRepository().getKey()
                    : List.of(primary.getRepository().getKey(), fallback.getRepository().getKey());
            this.repository = new WorkspaceRepository("reactor+quarkus-workspace", key);
        }

        @Override
        public WorkspaceRepository getRepository() {
            return repository;
        }

        @Override
        public File findArtifact(org.eclipse.aether.artifact.Artifact artifact) {
            File found = primary == null ? null : primary.findArtifact(artifact);
            return found != null ? found : fallback.findArtifact(artifact);
        }

        @Override
        public List<String> findVersions(org.eclipse.aether.artifact.Artifact artifact) {
            if (primary == null) {
                return fallback.findVersions(artifact);
            }
            LinkedHashSet<String> versions = new LinkedHashSet<>(primary.findVersions(artifact));
            versions.addAll(fallback.findVersions(artifact));
            return List.copyOf(versions);
        }

        @Override
        public Model findModel(org.eclipse.aether.artifact.Artifact artifact) {
            if (primary instanceof MavenWorkspaceReader mavenPrimary) {
                Model model = mavenPrimary.findModel(artifact);
                if (model != null) {
                    return model;
                }
            }
            return fallback.resolveEffectiveModel(
                    artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
        }
    }
}
