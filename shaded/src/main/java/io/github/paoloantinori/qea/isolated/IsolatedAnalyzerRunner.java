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
            boolean debugAttribution, Path fragmentsDir) throws IOException {
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
        if (fragmentsDir != null) {
            io.github.paoloantinori.qea.plugin.report.IgnoreFragments
                    .writeFragments(report.ignoreRecommendations(), fragmentsDir);
        }
        return new ReportBundle(Reporter.toJson(report), Reporter.toText(report));
    }

    /** The serialized analysis output: JSON for tooling, text for the build log. */
    public record ReportBundle(String json, String text) {
    }

    private static ApplicationModel resolveModel(MavenSession session, MavenProject project,
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
            BootstrapAppModelResolver resolver = new BootstrapAppModelResolver(mvn);
            return resolver.resolveModel(
                    ArtifactCoords.jar(project.getGroupId(), project.getArtifactId(), project.getVersion()));
        } catch (Exception e) {
            throw new IOException("failed to resolve the ApplicationModel for "
                    + project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion()
                    + "; build the module first (mvn compile) or install it into the local repository "
                    + "(mvn install)", e);
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
