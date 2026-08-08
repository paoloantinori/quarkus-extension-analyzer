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
package io.github.paoloantinori.qea.plugin.configroot;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TASK-7: {@link ValueRules} rule parsing, key-pattern matching, positive selection and suppression. */
class ValueRulesTest {

    private static final ValueRules.Rule DB_KIND_H2 =
            new ValueRules.Rule("quarkus.datasource.{name}.db-kind", "h2", "io.quarkus:quarkus-jdbc-h2");
    private static final ValueRules.Rule DB_KIND_POSTGRESQL =
            new ValueRules.Rule("quarkus.datasource.{name}.db-kind", "postgresql", "io.quarkus:quarkus-jdbc-postgresql");
    private static final ValueRules.Rule DB_KIND_MYSQL =
            new ValueRules.Rule("quarkus.datasource.{name}.db-kind", "mysql", "io.quarkus:quarkus-jdbc-mysql");
    private static final ValueRules.Rule BUILDER_DOCKER =
            new ValueRules.Rule("quarkus.container-image.builder", "docker", "io.quarkus:quarkus-container-image-docker");

    // --- key-pattern matching -------------------------------------------------------------------

    @Test
    void namePlaceholderMatchesBothTheUnnamedAndASingleSegmentNamedKey() {
        assertThat(ValueRules.matchesPattern("quarkus.datasource.{name}.db-kind", "quarkus.datasource.db-kind"))
                .isTrue();
        assertThat(ValueRules.matchesPattern("quarkus.datasource.{name}.db-kind", "quarkus.datasource.h2.db-kind"))
                .isTrue();
    }

    @Test
    void namePlaceholderDoesNotMatchAMultiSegmentName() {
        // A name with a further dot is a structurally different key, not "more specific".
        assertThat(ValueRules.matchesPattern("quarkus.datasource.{name}.db-kind", "quarkus.datasource.a.b.db-kind"))
                .isFalse();
    }

    @Test
    void namePlaceholderDoesNotMatchAnUnrelatedKey() {
        assertThat(ValueRules.matchesPattern("quarkus.datasource.{name}.db-kind", "quarkus.datasource.jdbc.url"))
                .isFalse();
    }

    @Test
    void literalPatternWithNoPlaceholderMatchesOnlyExactly() {
        assertThat(ValueRules.matchesPattern("quarkus.container-image.builder", "quarkus.container-image.builder"))
                .isTrue();
        assertThat(ValueRules.matchesPattern("quarkus.container-image.builder", "quarkus.container-image.builder.x"))
                .isFalse();
    }

    // --- positive selection (Match) ---------------------------------------------------------------

    /** M1 known-hard-case, per-name db-kind: only the driver whose value is actually set is selected. */
    @Test
    void matchesSelectsOnlyTheDriverWhoseDbKindValueIsPresent() {
        ValueRules rules = ValueRules.of(List.of(DB_KIND_H2, DB_KIND_POSTGRESQL, DB_KIND_MYSQL));
        Map<String, Set<String>> valuesByKey = Map.of(
                "quarkus.datasource.h2.db-kind", Set.of("h2"),
                "quarkus.datasource.postgresql.db-kind", Set.of("postgresql"));

        Map<String, ValueRules.Match> matches = rules.matches(valuesByKey);

        assertThat(matches).containsOnlyKeys("io.quarkus:quarkus-jdbc-h2", "io.quarkus:quarkus-jdbc-postgresql");
        assertThat(matches.get("io.quarkus:quarkus-jdbc-h2"))
                .isEqualTo(new ValueRules.Match("io.quarkus:quarkus-jdbc-h2", "quarkus.datasource.h2.db-kind", "h2",
                        "quarkus.datasource.{name}.db-kind"));
    }

    @Test
    void matchesIsCaseInsensitiveOnTheValue() {
        ValueRules rules = ValueRules.of(List.of(BUILDER_DOCKER));
        Map<String, Set<String>> valuesByKey = Map.of("quarkus.container-image.builder", Set.of("DOCKER"));

        Map<String, ValueRules.Match> matches = rules.matches(valuesByKey);

        assertThat(matches.get("io.quarkus:quarkus-container-image-docker").value()).isEqualTo("DOCKER");
    }

    @Test
    void matchesFindsAValueUnderAnyProfileMergedIntoTheSameBareKey() {
        // AppConfigReader merges "%openshift.quarkus.container-image.builder" and the unprefixed key
        // into the same bare key with two values; the rules table has no profile concept of its own.
        ValueRules rules = ValueRules.of(List.of(BUILDER_DOCKER));
        Map<String, Set<String>> valuesByKey =
                Map.of("quarkus.container-image.builder", Set.of("openshift", "docker"));

        Map<String, ValueRules.Match> matches = rules.matches(valuesByKey);

        assertThat(matches).containsKey("io.quarkus:quarkus-container-image-docker");
    }

    @Test
    void matchesOmitsATargetWhenNoValueSelectsIt() {
        ValueRules rules = ValueRules.of(List.of(DB_KIND_H2, DB_KIND_POSTGRESQL));
        Map<String, Set<String>> valuesByKey = Map.of("quarkus.datasource.h2.db-kind", Set.of("h2"));

        Map<String, ValueRules.Match> matches = rules.matches(valuesByKey);

        assertThat(matches).doesNotContainKey("io.quarkus:quarkus-jdbc-postgresql");
    }

    // --- suppression (plan item 3) ------------------------------------------------------------------

    /**
     * The db-kind discrimination case itself: three drivers share the family, only h2's value is set,
     * so the other two must be suppressed (their blanket RootInheritance evidence cannot be trusted) --
     * not silently dropped, but reported with which selector key(s) and values were seen.
     */
    @Test
    void suppressesSiblingsOfTheFamilyWhenTheSelectorKeyExistsButPicksADifferentSibling() {
        ValueRules rules = ValueRules.of(List.of(DB_KIND_H2, DB_KIND_POSTGRESQL, DB_KIND_MYSQL));
        Map<String, Set<String>> valuesByKey = Map.of("quarkus.datasource.h2.db-kind", Set.of("h2"));
        Map<String, ValueRules.Match> matches = rules.matches(valuesByKey);

        Map<String, ValueRules.Suppression> suppressions = rules.suppressions(valuesByKey, matches);

        assertThat(suppressions).doesNotContainKey("io.quarkus:quarkus-jdbc-h2");
        assertThat(suppressions).containsOnlyKeys("io.quarkus:quarkus-jdbc-postgresql", "io.quarkus:quarkus-jdbc-mysql");
        ValueRules.Suppression postgresql = suppressions.get("io.quarkus:quarkus-jdbc-postgresql");
        assertThat(postgresql.selectorKeyPattern()).isEqualTo("quarkus.datasource.{name}.db-kind");
        assertThat(postgresql.selectorKeys()).containsExactly("quarkus.datasource.h2.db-kind");
        assertThat(postgresql.valuesSeen()).containsExactly("h2");
    }

    /**
     * "An app with a genuinely dead jdbc driver" (task validation case): a project declares two drivers,
     * only one is ever selected by a db-kind value -- the dead one must be suppressed, the live one must
     * not be.
     */
    @Test
    void deadJdbcDriverIsSuppressedWhileTheSelectedOneIsNot() {
        ValueRules rules = ValueRules.of(List.of(DB_KIND_H2, DB_KIND_POSTGRESQL));
        Map<String, Set<String>> valuesByKey = Map.of("quarkus.datasource.db-kind", Set.of("postgresql"));
        Map<String, ValueRules.Match> matches = rules.matches(valuesByKey);

        Map<String, ValueRules.Suppression> suppressions = rules.suppressions(valuesByKey, matches);

        assertThat(matches).containsOnlyKeys("io.quarkus:quarkus-jdbc-postgresql");
        assertThat(suppressions).containsOnlyKeys("io.quarkus:quarkus-jdbc-h2");
    }

    /** Plan item 3, last sentence: the family's selector key never appearing at all suppresses nothing. */
    @Test
    void noSuppressionWhenTheFamilySelectorKeyNeverAppearsInTheConfigAtAll() {
        ValueRules rules = ValueRules.of(List.of(DB_KIND_H2, DB_KIND_POSTGRESQL));
        Map<String, Set<String>> valuesByKey = Map.of("quarkus.datasource.jdbc.url", Set.of("jdbc:h2:mem:test"));

        Map<String, ValueRules.Suppression> suppressions = rules.suppressions(valuesByKey, rules.matches(valuesByKey));

        assertThat(suppressions).isEmpty();
    }

    /** Plan item 3, last sentence: an artifact outside the rules table entirely is never suppressed. */
    @Test
    void suppressionNeverAppliesToAnArtifactWithNoRulesCoverage() {
        ValueRules rules = ValueRules.of(List.of(DB_KIND_H2));
        Map<String, Set<String>> valuesByKey = Map.of("quarkus.datasource.db-kind", Set.of("mysql"));

        Map<String, ValueRules.Suppression> suppressions = rules.suppressions(valuesByKey, rules.matches(valuesByKey));

        assertThat(suppressions).doesNotContainKey("io.quarkus:quarkus-jdbc-mysql");
    }

    // --- alias mapping (Stork static-list case) -----------------------------------------------------

    /** Explicit alias entry: type value "static" differs from the artifact suffix "static-list". */
    @Test
    void aliasEntryMapsAConfigValueToAnArtifactSuffixItDoesNotLexicallyMatch() {
        ValueRules.Rule storkStatic = new ValueRules.Rule("quarkus.stork.{name}.service-discovery.type", "static",
                "io.smallrye.stork:stork-service-discovery-static-list");
        ValueRules rules = ValueRules.of(List.of(storkStatic));
        Map<String, Set<String>> valuesByKey = Map.of(
                "quarkus.stork.hero-service.service-discovery.type", Set.of("static"));

        Map<String, ValueRules.Match> matches = rules.matches(valuesByKey);

        assertThat(matches).containsKey("io.smallrye.stork:stork-service-discovery-static-list");
    }

    // --- text/JSON evidence rendering is exercised end-to-end in AnalyzerTest/ReporterTest ----------

    // --- bundled resource ------------------------------------------------------------------------

    @Test
    void loadDefaultLoadsTheBundledCuratedTableWithoutThrowing() {
        ValueRules rules = ValueRules.loadDefault();

        // All three confirmed cases from the task, spot-checked.
        Map<String, Set<String>> valuesByKey = Map.of(
                "quarkus.datasource.db-kind", Set.of("postgresql"),
                "quarkus.container-image.builder", Set.of("podman"),
                "quarkus.stork.hero-service.service-discovery.type", Set.of("static"));

        Map<String, ValueRules.Match> matches = rules.matches(valuesByKey);

        assertThat(matches).containsKeys("io.quarkus:quarkus-jdbc-postgresql",
                "io.quarkus:quarkus-container-image-podman", "io.smallrye.stork:stork-service-discovery-static-list");
    }

    @Test
    void loadDefaultDoesNotContainADerbyRuleSinceNoSuchQuarkusExtensionExists() {
        ValueRules rules = ValueRules.loadDefault();

        Map<String, ValueRules.Match> matches = rules.matches(Map.of("quarkus.datasource.db-kind", Set.of("derby")));

        assertThat(matches).isEmpty();
    }

    // --- parser -------------------------------------------------------------------------------------

    @Test
    void parseSkipsBlankLinesAndComments() throws IOException {
        String table = """
                # a comment
                quarkus.container-image.builder|docker|io.quarkus:quarkus-container-image-docker

                # another comment
                """;

        ValueRules rules = ValueRules.parse(new ByteArrayInputStream(table.getBytes(StandardCharsets.UTF_8)));

        Map<String, ValueRules.Match> matches = rules.matches(Map.of("quarkus.container-image.builder", Set.of("docker")));
        assertThat(matches).containsKey("io.quarkus:quarkus-container-image-docker");
    }

    @Test
    void parseRejectsAMalformedLine() {
        String table = "quarkus.container-image.builder|docker\n"; // missing the third column

        assertThatThrownBy(() -> ValueRules.parse(new ByteArrayInputStream(table.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed value-rules.txt line 1");
    }
}
