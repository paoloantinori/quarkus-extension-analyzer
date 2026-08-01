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
package io.github.pantinor.qea.plugin;

import io.github.pantinor.qea.plugin.config.AppConfigReader;
import io.github.pantinor.qea.plugin.report.AnalysisReport;
import io.github.pantinor.qea.plugin.report.ExtensionReport;
import io.github.pantinor.qea.plugin.report.Reporter;
import io.github.pantinor.qea.plugin.report.Verdict;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.resolver.BootstrapAppModelResolver;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.maven.dependency.ArtifactCoords;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.impl.RemoteRepositoryManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Classifies every dependency directly declared by this project as {@code used-bytecode}, {@code
 * used-config}, {@code used-capability} or {@code suspect}, per docs/DESIGN.md. Report-only: never
 * modifies the POM.
 */
@Mojo(name = "analyze", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public class AnalyzeMojo extends AbstractMojo {

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Component
    private RepositorySystem repoSystem;

    /**
     * Both injected alongside {@link #repoSystem}: {@code BootstrapMavenContext}'s
     * {@code initRepoSystemAndManager()} unconditionally builds its own throwaway Sisu container to
     * satisfy whichever of {@code repoSystem}/{@code remoteRepoManager}/{@code settingsDecrypter} is
     * still {@code null} at resolution time (triggered via {@code MavenArtifactResolver}'s constructor,
     * which always calls {@code context.getRemoteRepositoryManager()}), and that ad hoc container
     * cannot see Maven's own beans inside the plugin's classloader realm. All three collaborators must
     * be supplied so none of the three getters ever calls {@code initRepoSystemAndManager()}.
     */
    @Component
    private RemoteRepositoryManager remoteRepositoryManager;

    @Component
    private SettingsDecrypter settingsDecrypter;

    /** Skips the goal entirely. */
    @Parameter(property = "qea.skip", defaultValue = "false")
    private boolean skip;

    /** Prints the human-readable report to the build log. */
    @Parameter(property = "qea.textReport", defaultValue = "true")
    private boolean textReport;

    /** If set, also writes the JSON report to this file, for CI consumption. */
    @Parameter(property = "qea.reportFile")
    private File reportFile;

    /** Fails the build if any directly-declared dependency is classified {@code suspect}. */
    @Parameter(property = "qea.failOnSuspect", defaultValue = "false")
    private boolean failOnSuspect;

    /**
     * {@code application.properties}/{@code .yaml}/{@code .yml} to read. Defaults to the first of
     * those found under one of the project's resource directories (or its output directory).
     */
    @Parameter(property = "qea.applicationConfig")
    private File applicationConfig;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("quarkus-extension-analyzer: skipped");
            return;
        }

        ApplicationModel model = resolveApplicationModel();
        List<Path> classesDirs = List.of(
                Paths.get(project.getBuild().getOutputDirectory()),
                Paths.get(project.getBuild().getTestOutputDirectory()));
        AppConfigReader appConfig = readApplicationConfig();

        ExecutorService executor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        AnalysisReport report;
        try {
            report = new Analyzer(executor).analyze(model, classesDirs, appConfig);
        } catch (IOException e) {
            throw new MojoExecutionException("quarkus-extension-analyzer: analysis failed", e);
        } finally {
            executor.shutdown();
        }

        if (textReport) {
            getLog().info("\n" + Reporter.toText(report));
        }
        if (reportFile != null) {
            try {
                Reporter.writeJson(report, reportFile.toPath());
                getLog().info("quarkus-extension-analyzer: JSON report written to " + reportFile);
            } catch (IOException e) {
                throw new MojoExecutionException("quarkus-extension-analyzer: failed to write " + reportFile, e);
            }
        }

        if (failOnSuspect) {
            List<String> suspects = report.dependencies().stream()
                    .filter(r -> r.verdict() == Verdict.SUSPECT)
                    .map(ExtensionReport::ga)
                    .toList();
            if (!suspects.isEmpty()) {
                throw new MojoFailureException(
                        "quarkus-extension-analyzer: " + suspects.size() + " suspect dependencies: " + suspects);
            }
        }
    }

    /**
     * Resolves the {@link ApplicationModel} for the current project, per plan item 2: the resolver is
     * fed by the Maven session's own {@link RepositorySystem}/repository session and the project's
     * remote repositories, instead of building a standalone resolver (the M1 spike's approach, kept
     * available as {@code spike/} for reference).
     */
    private ApplicationModel resolveApplicationModel() throws MojoExecutionException {
        try {
            MavenArtifactResolver mvn = MavenArtifactResolver.builder()
                    .setRepositorySystem(repoSystem)
                    .setRepositorySystemSession(session.getRepositorySession())
                    .setRemoteRepositories(project.getRemoteProjectRepositories())
                    .setRemoteRepositoryManager(remoteRepositoryManager)
                    .setSettingsDecrypter(settingsDecrypter)
                    .setWorkspaceDiscovery(false)
                    .build();
            BootstrapAppModelResolver resolver = new BootstrapAppModelResolver(mvn);
            return resolver.resolveModel(
                    ArtifactCoords.jar(project.getGroupId(), project.getArtifactId(), project.getVersion()));
        } catch (Exception e) {
            throw new MojoExecutionException("quarkus-extension-analyzer: failed to resolve the ApplicationModel", e);
        }
    }

    /**
     * Never fails the goal over a bad config file: an unparseable {@code application.yaml} (or any
     * other read failure) degrades to an empty config signal, with a warning, exactly like the "no
     * config file found" case. {@code RuntimeException} is caught alongside {@code IOException}
     * because SnakeYAML parsing can surface as either (e.g. a scanner error on malformed YAML).
     */
    private AppConfigReader readApplicationConfig() {
        Path resolved = applicationConfig != null ? applicationConfig.toPath() : defaultApplicationConfigPath();
        if (resolved == null || !Files.isRegularFile(resolved)) {
            getLog().warn("quarkus-extension-analyzer: no application.properties/.yaml found"
                    + (resolved == null ? "" : " at " + resolved) + "; the config signal will find nothing");
            return AppConfigReader.empty();
        }
        try {
            String name = resolved.getFileName().toString();
            return name.endsWith(".yaml") || name.endsWith(".yml")
                    ? AppConfigReader.readYaml(resolved)
                    : AppConfigReader.readProperties(resolved);
        } catch (IOException | RuntimeException e) {
            getLog().warn("quarkus-extension-analyzer: failed to read " + resolved + " (" + e + "); "
                    + "the config signal will find nothing", e);
            return AppConfigReader.empty();
        }
    }

    private static final List<String> APPLICATION_CONFIG_NAMES =
            List.of("application.properties", "application.yaml", "application.yml");

    /**
     * Searches every configured resource directory (not just the conventional {@code
     * src/main/resources}, since projects can and do point {@code <resources>} elsewhere), falling
     * back to the build output directory in case the file only exists post-resource-filtering.
     */
    private Path defaultApplicationConfigPath() {
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
}
