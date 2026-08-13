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

import io.github.paoloantinori.qea.plugin.report.AnalysisReport;
import io.quarkus.builder.item.SimpleBuildItem;

/**
 * Carries the {@link AnalysisReport} produced by {@link AnalyzerBuildStep} as a build item, so
 * integration tests (and potentially other build steps) can consume and assert on it. Without this,
 * the report is only visible in the build log (not testable programmatically).
 */
public final class AnalyzerReportBuildItem extends SimpleBuildItem {

    private final AnalysisReport report;

    public AnalyzerReportBuildItem(AnalysisReport report) {
        this.report = report;
    }

    public AnalysisReport getReport() {
        return report;
    }
}
