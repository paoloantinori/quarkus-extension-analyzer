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

import io.github.paoloantinori.qea.plugin.Analyzer;
import io.github.paoloantinori.qea.plugin.annotation.MavenLayout;
import io.github.paoloantinori.qea.plugin.config.AppConfigReader;
import io.github.paoloantinori.qea.plugin.report.AnalysisReport;
import io.github.paoloantinori.qea.plugin.report.ExtensionReport;
import io.github.paoloantinori.qea.plugin.report.Reporter;
import io.github.paoloantinori.qea.plugin.report.Verdict;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.BootstrapConfig;
import io.quarkus.deployment.builditem.AppModelProviderBuildItem;
import io.quarkus.deployment.pkg.builditem.ArtifactResultBuildItem;
import io.quarkus.bootstrap.model.ApplicationModel;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TASK-19: the @BuildStep that runs the analyzer inside Quarkus augmentation.
 *
 * <p>This is the extension-form shell: it consumes {@link AppModelProviderBuildItem} (the resolved
 * {@link ApplicationModel}, provided by augmentation, no {@code ChainedMavenWorkspaceReader} needed)
 * and {@link BeanArchiveIndexBuildItem} (ArC's Jandex index of the app's beans), runs the shared
 * core's {@link Analyzer}, and emits the report to the build log. Optionally fails the build via
 * {@code quarkus.extension-analyzer.fail-on-suspect}.
 *
 * <p>Why this form exists (vs the standalone mojo): inside augmentation, the {@link ApplicationModel}
 * is authoritative and free, eliminating the TASK-9 reactor-resolution machinery; and ArC's bean
 * index carries the producer/consumer wiring that resolves the annotation-consumer false positives
 * (hibernate-validator via @NotNull, scheduler via @Scheduled) the mojo cannot resolve without a
 * curated invariant-weakening table.
 */
public final class AnalyzerBuildStep {

    private static final Logger LOG = Logger.getLogger(AnalyzerBuildStep.class);

    /**
     * Run the analyzer. Produces {@link AnalyzerReportBuildItem} (carrying the {@link AnalysisReport})
     * so integration tests and other build steps can consume and assert on it. Also logs the report
     * to the build log for human visibility.
     *
     * <p>The additional {@code @Produce(ArtifactResultBuildItem.class)} is required for the step to be
     * included in the build graph: a step whose only output ({@link AnalyzerReportBuildItem}) is
     * consumed by nobody would be elided by Quarkus's build-graph dead-code elimination. Marking it
     * as also producing the artifact result (which the packaging pipeline always requires) makes the
     * step "always run", regardless of whether anyone consumes the report item.
     */
    @BuildStep
    @Produce(ArtifactResultBuildItem.class)
    AnalyzerReportBuildItem analyzeExtensions(
            AppModelProviderBuildItem appModelProvider,
            BeanArchiveIndexBuildItem beanArchiveIndex) throws IOException {

        LOG.info("quarkus-extension-analyzer: build step firing");

        // validateAndGet requires a BootstrapConfig (null causes NPE). Provide a no-op that skips
        // platform-import validation (IGNORE) and uses builder/model-resolver defaults.
        BootstrapConfig bootstrapConfig = new BootstrapConfig() {
            @Override public boolean effectiveModelBuilder() { return false; }
            @Override public boolean workspaceDiscovery() { return false; }
            @Override public boolean warnOnFailingWorkspaceModules() { return false; }
            @Override public boolean disableJarCache() { return false; }
            @Override public boolean legacyModelResolver() { return false; }
            @Override public MisalignedPlatformImports misalignedPlatformImports() { return MisalignedPlatformImports.IGNORE; }
        };
        ApplicationModel model = appModelProvider.validateAndGet(bootstrapConfig);
        if (model == null) {
            LOG.warn("quarkus-extension-analyzer: no ApplicationModel, skipping");
            return null;
        }

        // The app's compiled classes live in the build output directory during augmentation. Resolve
        // the project root from the ApplicationModel's app artifact (authoritative), falling back to
        // the process CWD: a build invoked as `mvn -f <module>/pom.xml` from another directory keeps
        // the caller's CWD, and CWD-relative target/classes would then miss (same class of bug as the
        // config lookup: observed as used-config=0 and a 19-suspect report on rest-fights).
        Path projectRoot = firstExistingProjectRoot(model);
        List<Path> classesDirs = new ArrayList<>();
        Path mainClasses = MavenLayout.classesDir(projectRoot);
        if (Files.isDirectory(mainClasses)) {
            classesDirs.add(mainClasses);
        }
        Path testClasses = MavenLayout.testClassesDir(projectRoot);
        if (Files.isDirectory(testClasses)) {
            classesDirs.add(testClasses);
        }

        // The app config: read from the conventional location, same as the mojo's default.
        AppConfigReader appConfig = readAppConfig(projectRoot);

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()));
        Analyzer analyzer = new Analyzer(executor, null);
        // The extension form enables the vocabulary signal by default: inside augmentation the
        // ApplicationModel is authoritative (no TASK-9 fragility), so the deployment-jar vocabulary
        // harvest has complete, reliable data (unlike the mojo where it is opt-in and marginal).
        AnalysisReport report = analyzer.analyze(model, classesDirs, appConfig, true);
        executor.shutdown();

        // --- Annotation-consumer resolution (the extension form's unique value) ---
        // The bean index (ArC's Jandex index of the app) knows which annotations the app uses.
        // A small curated mapping credits the extension that processes each known annotation family,
        // resolving the annotation-consumer false positives the mojo cannot (hibernate-validator
        // via @NotNull, scheduler via @Scheduled, smallrye-jwt via @Inject JsonWebToken). The
        // db-kind values from the app config feed the TASK-23 multi-reactive-client disambiguation.
        Set<String> dbKindValues = new java.util.TreeSet<>();
        for (var e : appConfig.valuesByKey().entrySet()) {
            if (e.getKey().endsWith(".db-kind")) {
                dbKindValues.addAll(e.getValue());
            }
        }
        report = AnnotationAttribution.apply(report, beanArchiveIndex.getIndex(), model, dbKindValues,
                projectRoot);

        // Emit the report via JBoss Logging (the canonical Quarkus build-time log channel,
        // visible in the build output; System.out may be redirected by the build harness).
        LOG.info("\n" + Reporter.toText(report));

        AnalyzerConfig config = new AnalyzerConfig();
        if (config.failOnSuspect) {
            List<String> suspects = new ArrayList<>();
            for (ExtensionReport r : report.dependencies()) {
                if (r.verdict() == Verdict.SUSPECT) {
                    suspects.add(r.ga());
                }
            }
            if (!suspects.isEmpty()) {
                throw new IllegalStateException(
                        "quarkus-extension-analyzer: " + suspects.size()
                                + " suspect dependencies: " + suspects);
            }
        }

        return new AnalyzerReportBuildItem(report);
    }

    /**
     * The project root containing the app being augmented, derived from the ApplicationModel's app
     * artifact resolved paths (a target/classes directory inside the module), falling back to the
     * process CWD ({@code Path.of("")}) when the model carries no app artifact, no resolved
     * paths, or no usable directory.
     */
    private static Path firstExistingProjectRoot(ApplicationModel model) {
        if (model.getAppArtifact() == null || model.getAppArtifact().getResolvedPaths() == null
                || !model.getAppArtifact().getResolvedPaths().iterator().hasNext()) {
            return Path.of("");
        }
        for (var p : model.getAppArtifact().getResolvedPaths()) {
            Path path = p.toAbsolutePath().normalize();
            // resolved paths may be the module dir itself, target/classes, or a jar
            if (Files.isDirectory(path)) {
                if (MavenLayout.isMainClassesDir(path)) {
                    return path.getParent().getParent();
                }
                return path;
            }
        }
        return Path.of("");
    }

    private static AppConfigReader readAppConfig(Path projectRoot) {
        // Resolve the config from the project root, NOT from the process CWD: during augmentation
        // Maven normally runs with CWD = module dir, but a build invoked as `mvn -f
        // <module>/pom.xml` from elsewhere keeps the caller's CWD, and a CWD-relative lookup
        // then silently reads the wrong directory (observed: used-config dropped to 0 when the
        // bench ran with -f from another repo). An empty root resolves to the plain relative
        // form, i.e. the legacy CWD behavior.
        for (String name : List.of("application.properties", "application.yaml", "application.yml")) {
            Path candidate = MavenLayout.resourcesFile(projectRoot, name);
            if (Files.isRegularFile(candidate)) {
                try {
                    return name.endsWith(".properties")
                            ? AppConfigReader.readProperties(candidate)
                            : AppConfigReader.readYaml(candidate);
                } catch (IOException | RuntimeException ignored) {
                    // degrade to empty config, same as the mojo
                }
            }
        }
        return AppConfigReader.empty();
    }
}
