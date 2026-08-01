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
package io.github.pantinor.qea.plugin.report;

import java.util.List;

/**
 * Top-level analysis result for one Maven project: every directly-declared dependency (extension or
 * plain jar) with its verdict and evidence trail, plus a summary count per verdict.
 */
public record AnalysisReport(String applicationArtifact, String generatedAt, List<ExtensionReport> dependencies,
        Summary summary) {

    public record Summary(int usedBytecode, int usedConfig, int usedCapability, int suspect, int total) {

        public static Summary of(List<ExtensionReport> reports) {
            int usedBytecode = 0;
            int usedConfig = 0;
            int usedCapability = 0;
            int suspect = 0;
            for (ExtensionReport r : reports) {
                switch (r.verdict()) {
                    case USED_BYTECODE -> usedBytecode++;
                    case USED_CONFIG -> usedConfig++;
                    case USED_CAPABILITY -> usedCapability++;
                    case SUSPECT -> suspect++;
                }
            }
            return new Summary(usedBytecode, usedConfig, usedCapability, suspect, reports.size());
        }
    }
}
