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
package io.github.paoloantinori.qea.plugin.report;

import java.util.List;

/**
 * Top-level analysis result for one Maven project: every directly-declared dependency (extension or
 * plain jar) with its verdict and evidence trail, plus a summary count per verdict.
 *
 * <p>TASK-10: the aggregate {@link #summary()} block mixes counts for declared Quarkus extensions
 * (what the tool exists to answer: "which of my extensions are actually used") with plain-jar
 * dependency rows, which misled early triage into reading a plain-jar-inflated suspect count as the
 * extension-level verdict (see {@code docs/SECOND-BENCH.md}). {@link #extensions()} and {@link
 * #plainJars()} split those two populations ({@link ExtensionReport#quarkusExtension()}); {@link
 * #summary()} is kept, covering both categories combined, for backward compatibility with existing
 * JSON consumers.
 *
 * @param ignoreRecommendations the {@code used-config}/{@code used-capability} subset of {@link
 *                               #dependencies()}, pre-digested into ignore-list recommendations (see
 *                               {@link IgnoreRecommendation#of}) so CI consumers can build their own
 *                               ignore-list format without parsing the full report or any XML
 * @param extensions             verdict counts over the declared-Quarkus-extension rows only
 * @param plainJars              verdict counts over the non-extension (plain jar) rows only
 * @param summary                verdict counts over all rows combined (extensions and plain jars)
 */
public record AnalysisReport(String applicationArtifact, String generatedAt, List<ExtensionReport> dependencies,
        List<IgnoreRecommendation> ignoreRecommendations, Summary extensions, Summary plainJars, Summary summary) {

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

        /**
         * Field-wise sum of two disjoint {@link Summary}s (e.g. {@link #extensions()} and {@link
         * #plainJars()}), which is what {@link #summary()} always equals since every row is classified
         * into exactly one of those two partitions. Computing it this way, instead of a third {@link
         * #of} pass over the full row list, avoids a redundant scan.
         */
        public static Summary combine(Summary a, Summary b) {
            return new Summary(a.usedBytecode() + b.usedBytecode(), a.usedConfig() + b.usedConfig(),
                    a.usedCapability() + b.usedCapability(), a.suspect() + b.suspect(), a.total() + b.total());
        }
    }
}
