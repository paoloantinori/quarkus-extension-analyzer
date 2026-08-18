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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-41 pins: the runtime verification plan turns the build-time-invisible residual into
 * concrete steps per suspect - curated commands for the families with known runtime surface,
 * the generic ablation protocol for the rest, nothing for reports without extension suspects.
 */
class RuntimeVerificationPlanTest {

    private static ExtensionReport suspect(String ga) {
        return new ExtensionReport(ga, true, Verdict.SUSPECT, false, Set.of(), List.of(), Set.of(),
                List.of(), false, List.of(), null, null, null, List.of(), List.of());
    }

    @Test
    void curatedFamilyGetsConcreteCommands() {
        String plan = RuntimeVerificationPlan.plan(List.of(suspect("io.quarkus:quarkus-rest-jackson")));
        assertThat(plan).contains("runtime verification")
                .contains("io.quarkus:quarkus-rest-jackson")
                .contains("curl -s -H 'Accept: application/json'")
                .doesNotContain("mvn verify - the app's own tests");
    }

    @Test
    void unknownFamilyGetsTheGenericAblationProtocol() {
        String plan = RuntimeVerificationPlan.plan(List.of(suspect("io.acme:acme-mystery")));
        assertThat(plan).contains("io.acme:acme-mystery")
                .contains("1. remove the dependency")
                .contains("4. anything broken");
    }

    @Test
    void schedulerPinPointsTheObservableBehavior() {
        String plan = RuntimeVerificationPlan.plan(List.of(suspect("io.quarkus:quarkus-scheduler")));
        assertThat(plan).contains("watch the log for the @Scheduled method firing");
    }

    @Test
    void noExtensionSuspectsMeansEmptyPlan() {
        assertThat(RuntimeVerificationPlan.plan(List.of())).isEmpty();
        assertThat(RuntimeVerificationPlan.plan(List.of(
                new ExtensionReport("io.quarkus:quarkus-rest", true, Verdict.USED_BYTECODE, false,
                        Set.of(), List.of(), Set.of(), List.of(), true, List.of(), null, null, null,
                        List.of(), List.of())))).isEmpty();
    }

    @Test
    void theAnalyzerItselfIsNeverPlanned() {
        // The analyzer extension is always a self-inflicted suspect; planning steps for it
        // would be noise.
        String plan = RuntimeVerificationPlan.plan(List.of(
                suspect("io.github.paoloantinori:quarkus-extension-analyzer")));
        assertThat(plan).isEmpty();
    }

    @Test
    void plainJarSuspectsAreNotPlanned() {
        // Runtime steps are curated per EXTENSION family; plain jars have no known surface.
        String plan = RuntimeVerificationPlan.plan(List.of(new ExtensionReport(
                "org.acme:plain-lib", false, Verdict.SUSPECT, false, Set.of(), List.of(), Set.of(),
                List.of(), false, List.of(), null, null, null, List.of(), List.of())));
        assertThat(plan).isEmpty();
    }
}
