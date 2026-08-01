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
package io.github.pantinor.qea.plugin.configroot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RootAttributorTest {

    /**
     * Regression case for the false positive M1 documented in docs/SPIKE-RESULTS.md
     * ("False positive observed, not just theorized"): on the Apicurio Registry validation bench,
     * {@code quarkus-logging-json}'s own sources disagree on granularity, contributing both the narrow
     * root it really owns ({@code quarkus.log.console.json.}) and a broad root ({@code quarkus.log.})
     * that really belongs to whichever extension owns general Quarkus core logging config. The spike's
     * per-extension containment matching credited {@code quarkus-logging-json} with keys like {@code
     * quarkus.log.category."io.apicurio".level} via the broad root alone, even though the application
     * never sets any {@code quarkus.log.console.json.*} key.
     *
     * <p>The exact class/root that legitimately owns {@code quarkus.log.category.} in quarkus-core was
     * not captured by the M1 spike run (signal 2/3 were out of scope for M1), so the competing claim
     * here is a synthetic stand-in ({@code quarkus-core} claiming {@code quarkus.log.category.}) rather
     * than a value pulled from the spike's actual output; the two {@code quarkus-logging-json} roots and
     * the specific matched key are taken verbatim from the spike's classification table, though.
     */
    @Test
    void narrowestClaimantWinsOverQuarkusLoggingJsonBroadRoot() {
        Map<String, Set<String>> claims = Map.of(
                "io.quarkus:quarkus-logging-json", Set.of("quarkus.log.", "quarkus.log.console.json."),
                "io.quarkus:quarkus-core", Set.of("quarkus.log.category."));
        Set<String> keys = Set.of(
                "quarkus.log.category.\"io.apicurio\".level",
                "quarkus.log.console.json.enabled");

        List<RootAttributor.Attribution> attributions = RootAttributor.attribute(claims, keys);
        Map<String, List<String>> byOwner = RootAttributor.byOwner(attributions);

        assertThat(byOwner.get("io.quarkus:quarkus-core"))
                .containsExactly("quarkus.log.category.\"io.apicurio\".level");
        assertThat(byOwner.get("io.quarkus:quarkus-logging-json"))
                .containsExactly("quarkus.log.console.json.enabled");
    }

    @Test
    void unclaimedKeyIsNotAttributedToAnyone() {
        Map<String, Set<String>> claims = Map.of("ext:a", Set.of("quarkus.a."));
        Set<String> keys = Set.of("quarkus.b.enabled");

        List<RootAttributor.Attribution> attributions = RootAttributor.attribute(claims, keys);

        assertThat(attributions).isEmpty();
    }

    @Test
    void tiedNarrowestClaimsCreditBothOwners() {
        Map<String, Set<String>> claims = Map.of(
                "ext:a", Set.of("quarkus.foo."),
                "ext:b", Set.of("quarkus.foo."));
        Set<String> keys = Set.of("quarkus.foo.enabled");

        List<RootAttributor.Attribution> attributions = RootAttributor.attribute(claims, keys);

        assertThat(attributions).hasSize(1);
        assertThat(attributions.get(0).owners()).containsExactlyInAnyOrder("ext:a", "ext:b");
    }

    @Test
    void exactRootWithoutTrailingSegmentMatches() {
        Map<String, Set<String>> claims = Map.of("ext:a", Set.of("quarkus.foo."));
        Set<String> keys = Set.of("quarkus.foo");

        List<RootAttributor.Attribution> attributions = RootAttributor.attribute(claims, keys);

        assertThat(attributions).hasSize(1);
        assertThat(attributions.get(0).owners()).containsExactly("ext:a");
    }
}
