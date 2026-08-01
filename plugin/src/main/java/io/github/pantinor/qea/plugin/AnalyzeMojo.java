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
import io.github.pantinor.qea.plugin.report.IgnoreFragments;
import io.github.pantinor.qea.plugin.report.Reporter;
import io.github.pantinor.qea.plugin.report.Verdict;
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
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.MavenWorkspaceReader;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
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
     * Opt-in (TASK-3): also writes maven-dependency-plugin/DepClean-compatible ignore-list XML
     * fragments to the project build directory ({@link IgnoreFragments#MAVEN_DEPENDENCY_PLUGIN_FILE_NAME}
     * and {@link IgnoreFragments#DEPCLEAN_FILE_NAME}), covering the {@code used-config}/{@code
     * used-capability} extensions only.
     */
    @Parameter(property = "qea.ignoreFragments", defaultValue = "false")
    private boolean ignoreFragments;

    /**
     * {@code application.properties}/{@code .yaml}/{@code .yml} to read. Defaults to the first of
     * those found under one of the project's resource directories (or its output directory).
     */
    @Parameter(property = "qea.applicationConfig")
    private File applicationConfig;

    /**
     * TASK-5 bench diagnostics: logs (at Maven debug level, i.e. visible under {@code -X}) each
     * extension's transitive-API BFS subtree, why each candidate jar was or wasn't attributed
     * (exclusive vs. shared vs. also-directly-declared), and, per attributed candidate, whether the
     * project's compiled classes actually reference it. Off by default: the trace is verbose (one block
     * per declared extension in the whole resolved model, not just the directly-declared ones) and is
     * meant for diagnosing a specific extension's classification, not routine runs.
     */
    @Parameter(property = "qea.debugAttribution", defaultValue = "false")
    private boolean debugAttribution;

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
        Analyzer analyzer = new Analyzer(executor, debugAttribution ? getLog()::debug : null);
        AnalysisReport report;
        try {
            report = analyzer.analyze(model, classesDirs, appConfig);
        } catch (IOException e) {
            throw new MojoExecutionException("quarkus-extension-analyzer: analysis failed", e);
        } finally {
            executor.shutdown();
        }

        // Opt-in, best-effort: a fragments failure must never suppress the JSON report or the
        // failOnSuspect outcome below, so it is isolated the same way readApplicationConfig()
        // degrades a bad application.yaml -- log a warning and continue.
        if (ignoreFragments) {
            Path buildDir = Paths.get(project.getBuild().getDirectory());
            try {
                List<Path> written = IgnoreFragments.writeFragments(report.ignoreRecommendations(), buildDir);
                getLog().info("quarkus-extension-analyzer: ignore-list fragments written to " + written.get(0)
                        + " and " + written.get(1));
            } catch (IOException | RuntimeException e) {
                getLog().warn("quarkus-extension-analyzer: failed to write ignore-list fragments to " + buildDir
                        + " (" + e + "); continuing without them", e);
            }
        }

        if (reportFile != null) {
            try {
                Reporter.writeJson(report, reportFile.toPath());
                getLog().info("quarkus-extension-analyzer: JSON report written to " + reportFile);
            } catch (IOException e) {
                throw new MojoExecutionException("quarkus-extension-analyzer: failed to write " + reportFile, e);
            }
        }

        if (textReport) {
            getLog().info("\n" + Reporter.toText(report));
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
     * available as {@code spike/} for reference, where workspace discovery stays off since it targets a
     * different, non-reactor use case).
     *
     * <p>{@code setCurrentProject} is fed an explicitly loaded {@link LocalProject} (workspace rooted at
     * this project's own basedir, per {@link LocalProject#loadWorkspace(Path)}) rather than relying on
     * {@code BootstrapMavenContextConfig}'s default {@code workspaceDiscovery} flag: that default falls
     * back to discovering the current POM from the {@code basedir} system property or the JVM's working
     * directory (see {@code BootstrapMavenContext#resolveCurrentPom()}), neither of which reliably points
     * at this module when the mojo runs as part of a multi-module reactor build.
     *
     * <p>Loading the workspace alone is not enough to make the resolver use it, for two compounding
     * reasons, both confirmed empirically (instrumented run against {@code /tmp/super-heroes/rest-heroes},
     * a never-installed reactor module): first, {@code BootstrapMavenContext} only wires a {@code
     * WorkspaceReader} into its {@code RepositorySystemSession} when it builds that session itself, which
     * it never does here since a pre-built session (Maven's own, {@code session.getRepositorySession()})
     * is supplied -- required so the mojo shares Maven's real settings/mirrors/credentials instead of a
     * second, independently-built session. Second, and more surprising: Maven's own session already
     * carries its own {@code WorkspaceReader} ({@code org.apache.maven.ReactorReader}) whenever the
     * current module's POM chains up to a multi-module parent; that reader *can* hand back a raw {@code
     * target/classes} directory for a not-yet-packaged module (see {@code ReactorReader.find}), but only
     * when {@code compile} is one of the lifecycle phases bound in the *current* Maven session -- which it
     * isn't here, since the analyze goal is invoked directly rather than bound after {@code compile}, so
     * {@code ReactorReader} finds nothing for a module that was {@code compile}d in an earlier, separate
     * {@code mvn} invocation.
     *
     * <p>The fix is to chain the two readers: {@link ChainedMavenWorkspaceReader} tries Maven's reader
     * first (so an already-packaged or relinked/relocated reactor artifact -- e.g. a shaded jar, or one
     * relinked by another plugin earlier in this same reactor build -- still resolves exactly as vanilla
     * Maven would) and falls back to our {@code LocalWorkspace} only on a miss, which is exactly the
     * compile-only-module gap above. Maven ships an equivalent chaining utility ({@code
     * org.apache.maven.internal.aether.MavenChainedWorkspaceReader}), but it lives in maven-core's {@code
     * internal} package, which is not exported to plugin classloader realms: using it directly fails at
     * runtime with {@code NoClassDefFoundError}, confirmed empirically against the same repro. {@link
     * ChainedMavenWorkspaceReader} reimplements the same chaining semantics (first-match {@code
     * findArtifact}, unioned {@code findVersions}, a composite {@code WorkspaceRepository} key so cache
     * entries never alias between the two readers, and {@code findModel} delegation via the public {@code
     * org.apache.maven.repository.internal.MavenWorkspaceReader} interface -- proven loadable here since
     * {@code ReactorReader} itself already implements it) using only classes already crossing the plugin
     * realm boundary successfully elsewhere in this method.
     */
    private ApplicationModel resolveApplicationModel() throws MojoExecutionException {
        LocalProject currentProject;
        try {
            currentProject = LocalProject.loadWorkspace(project.getBasedir().toPath());
        } catch (BootstrapMavenException | RuntimeException e) {
            // RuntimeException too: a malformed sibling POM elsewhere in the reactor surfaces as an
            // unchecked UncheckedIOException/RuntimeException from the workspace loader's concurrent
            // module-loading tasks, not as a BootstrapMavenException.
            throw new MojoExecutionException("quarkus-extension-analyzer: failed to load the Maven workspace at "
                    + project.getBasedir() + "; check that the POM (and its parent chain, if any) is valid, or, "
                    + "for layouts the workspace loader cannot walk (e.g. no parent POM chain to the reactor "
                    + "root), install this module into the local repository first (mvn install)", e);
        }

        RepositorySystemSession repoSession = session.getRepositorySession();
        LocalWorkspace fallback = currentProject.getWorkspace();
        if (fallback != null) {
            WorkspaceReader reader = new ChainedMavenWorkspaceReader(repoSession.getWorkspaceReader(), fallback);
            repoSession = new DefaultRepositorySystemSession(repoSession).setWorkspaceReader(reader);
        }

        try {
            MavenArtifactResolver mvn = MavenArtifactResolver.builder()
                    .setRepositorySystem(repoSystem)
                    .setRepositorySystemSession(repoSession)
                    .setRemoteRepositories(project.getRemoteProjectRepositories())
                    .setRemoteRepositoryManager(remoteRepositoryManager)
                    .setSettingsDecrypter(settingsDecrypter)
                    .setCurrentProject(currentProject)
                    .build();
            BootstrapAppModelResolver resolver = new BootstrapAppModelResolver(mvn);
            return resolver.resolveModel(
                    ArtifactCoords.jar(project.getGroupId(), project.getArtifactId(), project.getVersion()));
        } catch (Exception e) {
            throw new MojoExecutionException("quarkus-extension-analyzer: failed to resolve the ApplicationModel "
                    + "for " + project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion()
                    + "; build the module first (mvn compile) or, for exotic layouts not resolvable from the "
                    + "reactor workspace, install it into the local repository first (mvn install)", e);
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

    /**
     * Chains {@code primary} (Maven's own {@code WorkspaceReader}, typically {@code
     * org.apache.maven.ReactorReader}, may be {@code null}) ahead of {@code fallback} (the Quarkus {@code
     * LocalWorkspace}, never {@code null}), reimplementing {@code
     * org.eclipse.aether.util.repository.ChainedWorkspaceReader}'s semantics plus {@code
     * org.apache.maven.repository.internal.MavenWorkspaceReader#findModel} delegation. See {@link
     * #resolveApplicationModel()}'s javadoc for why this is hand-rolled instead of reusing Maven's own
     * {@code MavenChainedWorkspaceReader} (an {@code internal}-package class not visible from a plugin
     * realm at runtime).
     */
    private static final class ChainedMavenWorkspaceReader implements MavenWorkspaceReader {

        private final WorkspaceReader primary;
        private final LocalWorkspace fallback;
        private final WorkspaceRepository repository;

        private ChainedMavenWorkspaceReader(WorkspaceReader primary, LocalWorkspace fallback) {
            this.primary = primary;
            this.fallback = fallback;
            // A composite key, sensitive to both readers' contents, so resolver-side caches (which key
            // partly on getRepository()) never alias an entry resolved through one reader's contents with
            // a request meant for the other's.
            Object key = primary == null ? fallback.getRepository().getKey()
                    : List.of(primary.getRepository().getKey(), fallback.getRepository().getKey());
            this.repository = new WorkspaceRepository("reactor+quarkus-workspace", key);
        }

        @Override
        public WorkspaceRepository getRepository() {
            return repository;
        }

        @Override
        public File findArtifact(Artifact artifact) {
            File found = primary == null ? null : primary.findArtifact(artifact);
            return found != null ? found : fallback.findArtifact(artifact);
        }

        @Override
        public List<String> findVersions(Artifact artifact) {
            if (primary == null) {
                return fallback.findVersions(artifact);
            }
            LinkedHashSet<String> versions = new LinkedHashSet<>(primary.findVersions(artifact));
            versions.addAll(fallback.findVersions(artifact));
            return List.copyOf(versions);
        }

        @Override
        public Model findModel(Artifact artifact) {
            if (primary instanceof MavenWorkspaceReader mavenPrimary) {
                Model model = mavenPrimary.findModel(artifact);
                if (model != null) {
                    return model;
                }
            }
            return fallback.resolveEffectiveModel(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
        }
    }
}
