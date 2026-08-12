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
package io.github.paoloantinori.qea.plugin.deploymentvocab;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the TASK-8 fourth signal's attribution logic (the exclusive-crediting rule),
 * kept out of the full {@code analyze} pipeline which needs a real {@code ApplicationModel}.
 * Mirrors the structure of {@code TransitiveApiAttributionTest}: the pure decision function is
 * testable in isolation.
 */
class VocabularyAttributionTest {

    /**
     * The core case: the app references {@code jakarta.validation.Validator}, which appears ONLY in
     * hibernate-validator's deployment vocabulary, so hibernate-validator is credited with that type.
     * This is exactly the rest-fights false-positive case TASK-8 resolves (the type lives in the
     * shared jakarta.validation-api jar that TASK-5 declines to attribute).
     */
    @Test
    void creditsExtensionForAppReferencedTypeExclusiveToItsVocabulary() {
        Map<String, Set<String>> vocab = Map.of(
                "io.quarkus:quarkus-hibernate-validator", Set.of("jakarta.validation.Validator"),
                "io.quarkus:quarkus-scheduler", Set.of("io.quarkus.scheduler.Scheduled"));
        Set<String> appReferenced = Set.of("jakarta.validation.Validator", "java.lang.String");

        Map<String, Set<String>> credited = VocabularyAttribution.attribute(vocab, appReferenced);

        assertThat(credited).containsEntry("io.quarkus:quarkus-hibernate-validator",
                Set.of("jakarta.validation.Validator"));
        assertThat(credited).doesNotContainKey("io.quarkus:quarkus-scheduler");
    }

    /**
     * The safety property: a type referenced by TWO declared extensions' vocabularies is ambiguous and
     * is NEVER attributed, mirroring {@code TransitiveApiAttribution}'s exclusivity rule. This is the
     * conservative guarantee that the fourth signal does not manufacture a verdict on ambiguous evidence.
     */
    @Test
    void neverCreditsATypePresentInTwoExtensionsVocabularies() {
        // Both hibernate-validator and smallrye-jwt deployment jars reference jakarta.validation.Validator
        Map<String, Set<String>> vocab = Map.of(
                "io.quarkus:quarkus-hibernate-validator", Set.of("jakarta.validation.Validator"),
                "io.quarkus:quarkus-smallrye-jwt", Set.of("jakarta.validation.Validator", "JsonWebToken"));
        Set<String> appReferenced = Set.of("jakarta.validation.Validator", "JsonWebToken");

        Map<String, Set<String>> credited = VocabularyAttribution.attribute(vocab, appReferenced);

        // Validator is shared -> not attributed to either. JsonWebToken is exclusive to smallrye-jwt -> credited.
        assertThat(credited).doesNotContainKey("io.quarkus:quarkus-hibernate-validator");
        assertThat(credited).containsEntry("io.quarkus:quarkus-smallrye-jwt", Set.of("JsonWebToken"));
    }

    /**
     * Only app-referenced types count: a vocabulary type the app does NOT reference credits nothing
     * (the extension may declare producers, but if the app injects none of them it is not used via
     * this signal).
     */
    @Test
    void creditsNothingWhenAppDoesNotReferenceAnyVocabularyType() {
        Map<String, Set<String>> vocab = Map.of(
                "io.quarkus:quarkus-hibernate-validator", Set.of("jakarta.validation.Validator"));
        Set<String> appReferenced = Set.of("java.lang.String", "java.util.List");

        Map<String, Set<String>> credited = VocabularyAttribution.attribute(vocab, appReferenced);

        assertThat(credited).isEmpty();
    }

    /**
     * An extension absent from the vocabulary map (e.g. no resolvable deployment jar) is simply never
     * credited; it does not error or block attribution to others.
     */
    @Test
    void extensionsWithoutVocabularyAreSkippedSilently() {
        Map<String, Set<String>> vocab = Map.of(
                "io.quarkus:quarkus-hibernate-validator", Set.of("jakarta.validation.Validator"));
        // scheduler has no deployment jar -> absent from vocab map; the app references Scheduled anyway
        Set<String> appReferenced = Set.of("jakarta.validation.Validator", "io.quarkus.scheduler.Scheduled");

        Map<String, Set<String>> credited = VocabularyAttribution.attribute(vocab, appReferenced);

        assertThat(credited).containsEntry("io.quarkus:quarkus-hibernate-validator",
                Set.of("jakarta.validation.Validator"));
    }

    /**
     * Multiple exclusive types from one extension are all credited (the evidence list is complete,
     * not just the first hit), so the report's evidence trail is exhaustive.
     */
    @Test
    void creditsAllExclusiveAppReferencedTypesForOneExtension() {
        Map<String, Set<String>> vocab = Map.of(
                "io.quarkus:quarkus-kubernetes-client",
                Set.of("io.fabric8.kubernetes.client.KubernetesClient", "io.fabric8.kubernetes.api.model.HasMetadata"));
        Set<String> appReferenced = Set.of(
                "io.fabric8.kubernetes.client.KubernetesClient",
                "io.fabric8.kubernetes.api.model.HasMetadata",
                "java.lang.String");

        Map<String, Set<String>> credited = VocabularyAttribution.attribute(vocab, appReferenced);

        assertThat(credited.get("io.quarkus:quarkus-kubernetes-client")).containsExactlyInAnyOrder(
                "io.fabric8.kubernetes.client.KubernetesClient",
                "io.fabric8.kubernetes.api.model.HasMetadata");
    }

    /**
     * Noise filter (bench-driven): JDK types, primitives, arrays, and ubiquitous logging libs
     * (org.slf4j.Logger, org.jboss.logging, org.apache.commons.logging) must never credit an
     * extension. The rest-fights bench credited quarkus-info via java.time.OffsetDateTime and
     * quarkus-rest-jackson via byte before this filter; the Apicurio bench credited config-index via
     * org.slf4j.Logger. All are coincidence, not producer signal.
     */
    @Test
    void neverCreditsJdkPrimitivesOrUbiquitousLoggingTypes() {
        Map<String, Set<String>> vocab = Map.of(
                "io.quarkus:quarkus-info", Set.of("java.time.OffsetDateTime"),
                "io.quarkus:quarkus-rest-jackson", Set.of("byte"),
                "io.apicurio:apicurio-registry-config-index", Set.of("org.slf4j.Logger"));
        Set<String> appReferenced = Set.of("java.time.OffsetDateTime", "byte", "org.slf4j.Logger");

        Map<String, Set<String>> credited = VocabularyAttribution.attribute(vocab, appReferenced);

        assertThat(credited).isEmpty();
    }

    /**
     * The noise filter must NOT exclude jakarta.* producer types: jakarta.validation.Validator is
     * exactly the kind of type this signal targets. If several extensions reference it, exclusivity
     * handles the sharing; but a single-extension jakarta.* reference must still credit.
     */
    @Test
    void doesNotFilterJakartaProducerTypes() {
        Map<String, Set<String>> vocab = Map.of(
                "io.quarkus:quarkus-hibernate-validator", Set.of("jakarta.validation.Validator"));
        Set<String> appReferenced = Set.of("jakarta.validation.Validator");

        Map<String, Set<String>> credited = VocabularyAttribution.attribute(vocab, appReferenced);

        assertThat(credited).containsEntry("io.quarkus:quarkus-hibernate-validator",
                Set.of("jakarta.validation.Validator"));
    }
}
