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
package io.github.paoloantinori.qea.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.paoloantinori.qea.isolated.IsolatedAnalyzerRunner;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.MavenWorkspaceReader;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.impl.RemoteRepositoryManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Classifies every dependency directly declared by this project as {@code used-bytecode}, {@code
 * used-config}, {@code used-capability} or {@code suspect}, per docs/DESIGN.md. Report-only: never
 * modifies the POM.
 *
 * <p>TASK-20: this mojo is a THIN SHELL. All model resolution and analysis run inside the shaded
 * runner ({@link IsolatedAnalyzerRunner}, from {@code quarkus-extension-analyzer-shaded}), whose
 * embedded Quarkus bootstrap classes are relocated under {@code io.github.paoloantinori.qea.internal}.
 * That makes the mojo immune to the classloader LinkageError on projects that expose their own
 * Quarkus bootstrap classes into the Maven build realm (the camel-quarkus IT case: they register
 * quarkus-maven-plugin as a build extension, so 3.39 classes sit in the project realm and used to
 * collide with our embedded 3.33 for split packages). The runner boundary carries only JDK and
 * Maven-API types (shared through the parent realm) and returns the report as JSON/text.
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
     * still {@code null} at resolution time, and that ad hoc container cannot see Maven's own beans
     * inside the plugin's classloader realm. All three collaborators must be supplied so none of the
     * three getters ever calls {@code initRepoSystemAndManager()}.
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
     * fragments to the project build directory, covering the {@code used-config}/{@code
     * used-capability} extensions only. Not yet wired through the shaded runner; the fragments
     * are derived from the JSON report on the mojo side.
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
     * TASK-5 bench diagnostics: logs each extension's transitive-API BFS subtree and per-candidate
     * decisions. Not yet wired through the shaded runner (diagnostics only).
     */
    @Parameter(property = "qea.debugAttribution", defaultValue = "false")
    private boolean debugAttribution;

    /**
     * TASK-8 (experimental, OFF by default): enable the deployment-vocabulary fourth signal in the
     * analysis run.
     */
    @Parameter(property = "qea.vocabularySignal", defaultValue = "false")
    private boolean vocabularySignal;

    /** The deserialized report rows, for failOnSuspect and the report file. */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("quarkus-extension-analyzer: skipped");
            return;
        }

        List<Path> classesDirs = List.of(
                Paths.get(project.getBuild().getOutputDirectory()),
                Paths.get(project.getBuild().getTestOutputDirectory()));

        IsolatedAnalyzerRunner.ReportBundle bundle;
        try {
            bundle = IsolatedAnalyzerRunner.run(session, project, repoSystem, remoteRepositoryManager,
                    settingsDecrypter, classesDirs, applicationConfig, vocabularySignal);
        } catch (IOException e) {
            throw new MojoExecutionException("quarkus-extension-analyzer: analysis failed: " + e.getMessage(), e);
        }

        if (reportFile != null) {
            try {
                Path target = reportFile.toPath();
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.writeString(target, bundle.json());
                getLog().info("quarkus-extension-analyzer: JSON report written to " + reportFile);
            } catch (IOException e) {
                throw new MojoExecutionException("quarkus-extension-analyzer: failed to write " + reportFile, e);
            }
        }

        if (textReport) {
            getLog().info("\n" + bundle.text());
        }

        if (failOnSuspect) {
            List<String> suspects = new ArrayList<>();
            try {
                var tree = JSON.readTree(bundle.json());
                tree.withArray("dependencies").forEach(r -> {
                    if ("suspect".equals(r.path("verdict").asText())) {
                        suspects.add(r.path("ga").asText());
                    }
                });
            } catch (IOException e) {
                throw new MojoExecutionException("quarkus-extension-analyzer: failed to parse the report", e);
            }
            if (!suspects.isEmpty()) {
                throw new MojoFailureException(
                        "quarkus-extension-analyzer: " + suspects.size() + " suspect dependencies: " + suspects);
            }
        }
    }
}
