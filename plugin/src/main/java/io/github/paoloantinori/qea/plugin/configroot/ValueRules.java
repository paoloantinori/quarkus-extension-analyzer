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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * TASK-7: a small curated table of {@code (config key pattern, value) -> target groupId:artifactId}
 * rules, generalizing DESIGN.md's "known hard case" (shared config roots discriminated only by a
 * value, e.g. the four {@code quarkus-jdbc-*} drivers all listening under {@code quarkus.datasource.*},
 * discriminated only by {@code db-kind}) beyond JDBC. Three confirmed cases ship in {@code
 * value-rules.txt}: JDBC {@code db-kind}, {@code quarkus.container-image.builder}, and the Stork {@code
 * service-discovery.type=static} alias -- see that resource for the per-entry existence verification.
 *
 * <p>A rule's target can be a Quarkus extension OR a plain jar (the Stork case): {@link #matches} does
 * not care which, it is up to the caller ({@code Analyzer}) to route the result to whichever
 * classification path (extension vs. plain jar) the target GA turns out to be.
 */
public final class ValueRules {

    private static final String NAME_PLACEHOLDER = "{name}";

    private static final ValueRules DEFAULT = load();

    private final List<Rule> rules;

    private ValueRules(List<Rule> rules) {
        this.rules = rules;
    }

    /** One curated table entry. */
    public record Rule(String keyPattern, String value, String targetGa) {

        /**
         * @param key an {@link io.github.paoloantinori.qea.plugin.config.AppConfigReader#allKeys()}-style
         *            bare key (profile prefix already stripped)
         */
        boolean matchesKey(String key) {
            return ValueRules.matchesPattern(keyPattern, key);
        }
    }

    /** A rule that fired: {@code targetGa} is used-config, with {@code key}/{@code value} as evidence. */
    public record Match(String targetGa, String key, String value, String keyPattern) {
    }

    /**
     * A rules-table family ({@code selectorKeyPattern}, e.g. {@code quarkus.datasource.{name}.db-kind})
     * whose selector key(s) exist in the application config, but no value selected {@code targetGa} --
     * per plan item 3, the blanket {@link RootInheritance} evidence for {@code targetGa} must be
     * suppressed rather than trusted, since this family's whole point is that the shared root alone
     * cannot tell its siblings apart.
     */
    public record Suppression(String targetGa, String selectorKeyPattern, Set<String> selectorKeys,
            Set<String> valuesSeen) {
    }

    /** The bundled table (loaded once from {@code value-rules.txt} on the plugin's own classpath). */
    public static ValueRules loadDefault() {
        return DEFAULT;
    }

    /** Test-only: builds a table from an explicit rule list instead of the bundled resource. */
    public static ValueRules of(List<Rule> rules) {
        return new ValueRules(List.copyOf(rules));
    }

    /**
     * @param valuesByKey {@link io.github.paoloantinori.qea.plugin.config.AppConfigReader#valuesByKey()}
     * @return target GA -&gt; the (first, deterministically by table order) rule that matched it; a GA
     *         absent here had no rule select it, which does not by itself mean "suspect" -- other
     *         signals may still apply, and {@link #suppressions} governs whether the blanket inherited
     *         signal may still be trusted for it
     */
    public Map<String, Match> matches(Map<String, Set<String>> valuesByKey) {
        Map<String, Match> result = new LinkedHashMap<>();
        for (Rule rule : rules) {
            if (result.containsKey(rule.targetGa())) {
                continue;
            }
            for (Map.Entry<String, Set<String>> entry : valuesByKey.entrySet()) {
                if (!rule.matchesKey(entry.getKey())) {
                    continue;
                }
                String matchedValue = firstEqualToIgnoreCase(entry.getValue(), rule.value());
                if (matchedValue != null) {
                    result.put(rule.targetGa(), new Match(rule.targetGa(), entry.getKey(), matchedValue,
                            rule.keyPattern()));
                    break;
                }
            }
        }
        return result;
    }

    /**
     * @param valuesByKey {@link io.github.paoloantinori.qea.plugin.config.AppConfigReader#valuesByKey()},
     *                    same input {@link #matches} was called with
     * @param matches     this table's {@link #matches} result for the same {@code valuesByKey}
     * @return target GA -&gt; {@link Suppression}, for every rule-table target whose family's selector
     *         key(s) are present in the config but that was not itself selected. A target whose family's
     *         selector key never appears in the config at all is absent (plan item 3: suppression only
     *         applies when the family's selector key "exist[s] in the config"), and so is a target that
     *         WAS matched (nothing to suppress for it).
     */
    public Map<String, Suppression> suppressions(Map<String, Set<String>> valuesByKey, Map<String, Match> matches) {
        Map<String, List<Rule>> family = new LinkedHashMap<>();
        for (Rule rule : rules) {
            family.computeIfAbsent(rule.keyPattern(), p -> new ArrayList<>()).add(rule);
        }

        Map<String, Suppression> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Rule>> entry : family.entrySet()) {
            String pattern = entry.getKey();
            Set<String> selectorKeys = new TreeSet<>();
            Set<String> valuesSeen = new TreeSet<>();
            for (Map.Entry<String, Set<String>> kv : valuesByKey.entrySet()) {
                if (matchesPattern(pattern, kv.getKey())) {
                    selectorKeys.add(kv.getKey());
                    valuesSeen.addAll(kv.getValue());
                }
            }
            if (selectorKeys.isEmpty()) {
                continue;
            }
            for (Rule rule : entry.getValue()) {
                if (!matches.containsKey(rule.targetGa())) {
                    result.put(rule.targetGa(),
                            new Suppression(rule.targetGa(), pattern, selectorKeys, valuesSeen));
                }
            }
        }
        return result;
    }

    private static String firstEqualToIgnoreCase(Set<String> values, String expected) {
        for (String value : values) {
            if (value != null && value.strip().equalsIgnoreCase(expected)) {
                return value;
            }
        }
        return null;
    }

    /**
     * A pattern either has no placeholder (plain literal equality, e.g. {@code
     * quarkus.container-image.builder}) or contains exactly one {@value #NAME_PLACEHOLDER}, standing for
     * a single, dot-free, optional path segment: {@code quarkus.datasource.{name}.db-kind} matches both
     * the unnamed {@code quarkus.datasource.db-kind} and any single-segment-named {@code
     * quarkus.datasource.<name>.db-kind}, but not a key with a further dot in the name position (that is
     * a different, unrelated key, not a "more specific" match).
     *
     * <p>By construction of {@code value-rules.txt}, the text immediately before the placeholder always
     * ends with {@code '.'} and the text immediately after always starts with {@code '.'}; this is not
     * re-validated here since the table is a small, curated, plugin-maintained resource, not
     * user-supplied input.
     */
    static boolean matchesPattern(String pattern, String key) {
        int idx = pattern.indexOf(NAME_PLACEHOLDER);
        if (idx < 0) {
            return key.equals(pattern);
        }
        String before = pattern.substring(0, idx);
        String after = pattern.substring(idx + NAME_PLACEHOLDER.length());

        String unnamed = before.substring(0, before.length() - 1) + after;
        if (key.equals(unnamed)) {
            return true;
        }
        if (!key.startsWith(before) || !key.endsWith(after)) {
            return false;
        }
        String name = key.substring(before.length(), key.length() - after.length());
        return !name.isEmpty() && name.indexOf('.') < 0;
    }

    private static ValueRules load() {
        try (InputStream in = ValueRules.class.getResourceAsStream("value-rules.txt")) {
            if (in == null) {
                throw new IllegalStateException("qea: bundled value-rules.txt resource is missing from the "
                        + "plugin jar (expected alongside " + ValueRules.class.getName() + ")");
            }
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("qea: failed to load the bundled value-rules.txt resource", e);
        }
    }

    /** Package-visible for {@code ValueRulesTest} to exercise the parser against a fixture stream. */
    static ValueRules parse(InputStream in) throws IOException {
        List<Rule> rules = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\|", -1);
                if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                    throw new IllegalStateException(
                            "qea: malformed value-rules.txt line " + lineNo + " (expected "
                                    + "'key-pattern|value|target-ga'): " + line);
                }
                rules.add(new Rule(parts[0].strip(), parts[1].strip(), parts[2].strip()));
            }
        }
        return new ValueRules(List.copyOf(rules));
    }
}
