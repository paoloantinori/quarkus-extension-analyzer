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
import io.github.paoloantinori.qea.plugin.config.AppConfigReader;
import io.github.paoloantinori.qea.plugin.report.AnalysisReport;
import io.github.paoloantinori.qea.plugin.report.ExtensionReport;
import io.github.paoloantinori.qea.plugin.report.Verdict;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.AppModelProviderBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.bootstrap.model.ApplicationModel;
import org.jboss.jandex.IndexView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * Run the analyzer. A {@code @BuildStep} method is discovered by Quarkus via the deployment
     * classloader; its consumed build items are injected, and its produced items register in the
     * build graph. This step produces nothing (the report is a side effect on the build log), so it
     * is a terminal observation step.
     */
    @BuildStep
    public void analyzeExtensions(
            AppModelProviderBuildItem appModelProvider,
            BeanArchiveIndexBuildItem beanArchiveIndex,
            CurateOutcomeBuildItem curateOutcome,
            AnalyzerConfig config) throws IOException {

        ApplicationModel model = appModelProvider.validateAndGet(null);
        if (model == null) {
            return;
        }

        // The app's compiled classes live in the build output directory during augmentation.
        // The Analyzer reads target/classes (and test-classes) for the bytecode + Jandex signals.
        List<Path> classesDirs = new ArrayList<>();
        Path mainClasses = Path.of("target", "classes");
        if (Files.isDirectory(mainClasses)) {
            classesDirs.add(mainClasses);
        }
        Path testClasses = Path.of("target", "test-classes");
        if (Files.isDirectory(testClasses)) {
            classesDirs.add(testClasses);
        }

        // The app config: read from the conventional location, same as the mojo's default.
        AppConfigReader appConfig = readAppConfig();

        // ArC's bean index: the authoritative producer/consumer wiring. The fourth signal
        // (annotation-consumer resolution) derives from it; the mojo cannot compute this.
        // For now, the index is consumed to confirm availability; the full annotation-consumer
        // signal wiring is the next iteration (it needs mapping app annotation usage to the
        // extension whose deployment processes each annotation, which this index enables).
        IndexView beanIndex = beanArchiveIndex.getIndex();

        Analyzer analyzer = new Analyzer(null, null);
        AnalysisReport report = analyzer.analyze(model, classesDirs, appConfig);

        // Emit the report to the build log (text form), mirroring the mojo's textReport=true.
        System.out.println(io.github.paoloantinori.qea.plugin.report.Reporter.toText(report));

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
    }

    private static AppConfigReader readAppConfig() {
        for (String name : List.of("application.properties", "application.yaml", "application.yml")) {
            Path candidate = Path.of("src", "main", "resources", name);
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
