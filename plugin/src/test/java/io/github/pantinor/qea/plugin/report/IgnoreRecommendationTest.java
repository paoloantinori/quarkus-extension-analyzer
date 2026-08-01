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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IgnoreRecommendationTest {

    @Test
    void includesOwnConfigCapabilityAndInheritedConfigRows() {
        ExtensionReport ownConfig = new ExtensionReport("io.quarkus:quarkus-agroal", true, Verdict.USED_CONFIG,
                false, Set.of("quarkus.datasource."), List.of("quarkus.datasource.db-kind"), Set.of(), List.of(),
                false, List.of(), null, null, List.of());
        ExtensionReport inheritedConfig = new ExtensionReport("io.quarkus:quarkus-jdbc-h2", true, Verdict.USED_CONFIG,
                true, Set.of("quarkus.datasource."), List.of("quarkus.datasource.h2.db-kind"), Set.of(), List.of(),
                false, List.of(), null, null, List.of());
        ExtensionReport capability = new ExtensionReport("io.quarkus:quarkus-vertx", true, Verdict.USED_CAPABILITY,
                false, Set.of(), List.of(), Set.of(), List.of(), false,
                List.of("used because io.quarkus:quarkus-undertow depends on it (direct extension dependency)"),
                null, null, List.of());

        List<IgnoreRecommendation> recs = IgnoreRecommendation.of(List.of(ownConfig, inheritedConfig, capability));

        assertThat(recs).extracting(IgnoreRecommendation::ga).containsExactlyInAnyOrder(
                "io.quarkus:quarkus-agroal", "io.quarkus:quarkus-jdbc-h2", "io.quarkus:quarkus-vertx");
    }

    @Test
    void excludesUsedBytecodeAndSuspectRows() {
        ExtensionReport bytecode = new ExtensionReport("io.quarkus:quarkus-jackson", true, Verdict.USED_BYTECODE,
                false, Set.of(), List.of(), Set.of(), List.of(), true, List.of(), null, null, List.of());
        ExtensionReport suspect = new ExtensionReport("io.quarkus:quarkus-scheduler", true, Verdict.SUSPECT, false,
                Set.of(), List.of(), Set.of(), List.of(), false, List.of(), "no signal fired", null, List.of());

        List<IgnoreRecommendation> recs = IgnoreRecommendation.of(List.of(bytecode, suspect));

        assertThat(recs).isEmpty();
    }

    @Test
    void reasonUsesTheCapabilityEvidenceSentenceVerbatim() {
        String evidence = "used because it provides capability io.quarkus.vertx.http required by "
                + "io.quarkus:quarkus-resteasy-reactive";
        ExtensionReport capability = new ExtensionReport("io.quarkus:quarkus-vertx-http", true,
                Verdict.USED_CAPABILITY, false, Set.of(), List.of(), Set.of(), List.of(), false, List.of(evidence),
                null, null, List.of());

        List<IgnoreRecommendation> recs = IgnoreRecommendation.of(List.of(capability));

        assertThat(recs).singleElement().satisfies(r -> {
            assertThat(r.verdict()).isEqualTo(Verdict.USED_CAPABILITY);
            assertThat(r.reason()).isEqualTo(evidence);
        });
    }

    @Test
    void reasonDistinguishesOwnFromInheritedConfigRoots() {
        ExtensionReport own = new ExtensionReport("io.quarkus:quarkus-agroal", true, Verdict.USED_CONFIG, false,
                Set.of("quarkus.datasource."), List.of("quarkus.datasource.db-kind"), Set.of(), List.of(), false,
                List.of(), null, null, List.of());
        ExtensionReport inherited = new ExtensionReport("io.quarkus:quarkus-jdbc-h2", true, Verdict.USED_CONFIG, true,
                Set.of("quarkus.datasource."), List.of("quarkus.datasource.h2.db-kind"), Set.of(), List.of(), false,
                List.of(), null, null, List.of());

        List<IgnoreRecommendation> recs = IgnoreRecommendation.of(List.of(own, inherited));

        assertThat(recs.get(0).reason()).contains("this extension's own config root");
        assertThat(recs.get(1).reason()).contains("inherited under a related extension's config root");
    }
}
