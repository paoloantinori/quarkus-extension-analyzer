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
package io.github.pantinor.qea.plugin.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads {@code application.properties} / {@code application.yaml} and flattens every profile into a
 * single key space.
 *
 * <p>Deliberately hand-rolled rather than delegating to SmallRye Config resolution (kept from the M1
 * spike, see docs/SPIKE-RESULTS.md "Why the M2 config reader still cannot delegate to SmallRye
 * Config"): the analyzer must see keys whose values reference unresolvable {@code ${...}} expressions
 * or environment variables, which a real config resolution would fail or silently drop. Only keys
 * matter for the config-root signal, so values are never inspected. Properties key extraction is
 * delegated to {@link Properties#load(Reader)} (comment skipping, backslash continuation and
 * separator parsing come for free); only the {@code %profile.} prefix splitting on top of the parsed
 * key set is hand-rolled, since that is a Quarkus convention {@link Properties} knows nothing about.
 *
 * <p>YAML support is best effort: nested maps are flattened into dotted keys, and a top-level key
 * starting with {@code %} (e.g. {@code %dev:}) is treated as a profile section, mirroring the
 * properties file's {@code %profile.} convention. List values are not descended into; the list's own
 * dotted path is recorded as a key.
 *
 * <p>TASK-7: raw scalar values are also captured per bare (profile prefix stripped) key, merged across
 * every profile a key appears under, feeding {@link io.github.pantinor.qea.plugin.configroot.ValueRules}
 * (the config-root signal itself, {@link #allKeys()}, only ever needs key presence). Values are recorded
 * verbatim, unresolved {@code ${...}} expressions included -- exactly like the key-only path above, this
 * class never tries to resolve them.
 */
public final class AppConfigReader {

    private final Map<String, Set<String>> keysByProfile = new LinkedHashMap<>();
    private final Set<String> allKeys = new TreeSet<>();
    private final Map<String, Set<String>> valuesByKey = new LinkedHashMap<>();

    private AppConfigReader() {
    }

    /** No config file found: every extension will be config-signal "suspect", with an empty key space. */
    public static AppConfigReader empty() {
        return new AppConfigReader();
    }

    public static AppConfigReader readProperties(Path propertiesFile) throws IOException {
        AppConfigReader cfg = new AppConfigReader();

        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(propertiesFile, StandardCharsets.UTF_8)) {
            props.load(in);
        }
        for (String key : props.stringPropertyNames()) {
            cfg.acceptPropertiesKey(key, props.getProperty(key));
        }
        return cfg;
    }

    @SuppressWarnings("unchecked")
    public static AppConfigReader readYaml(Path yamlFile) throws IOException {
        AppConfigReader cfg = new AppConfigReader();

        Object loaded;
        try (InputStream in = Files.newInputStream(yamlFile)) {
            loaded = new Yaml().load(in);
        }
        if (loaded instanceof Map) {
            // Keyed as Object, not String: SnakeYAML auto-types unquoted scalar keys (8080:, true:,
            // 2026-01-01: become Integer/Boolean/LocalDate), so casting the map to Map<String, Object>
            // and reading a key as String would throw ClassCastException on any such key.
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) loaded).entrySet()) {
                String rawKey = String.valueOf(entry.getKey());
                if (rawKey.startsWith("%")) {
                    cfg.acceptYamlNode(rawKey.substring(1), "", entry.getValue());
                } else {
                    cfg.acceptYamlNode("<none>", rawKey, entry.getValue());
                }
            }
        }
        return cfg;
    }

    private void acceptPropertiesKey(String key, String value) {
        // A key may carry a comma-separated profile list: "%dev,%test.quarkus.foo=bar".
        List<String> profiles = new ArrayList<>();
        String bare = key;
        while (bare.startsWith("%")) {
            int dot = bare.indexOf('.');
            if (dot < 0) {
                break;
            }
            String head = bare.substring(1, dot);
            for (String prof : head.split(",")) {
                String p = prof.strip();
                profiles.add(p.startsWith("%") ? p.substring(1) : p);
            }
            bare = bare.substring(dot + 1);
        }
        if (profiles.isEmpty()) {
            profiles.add("<none>");
        }
        for (String profile : profiles) {
            keysByProfile.computeIfAbsent(profile, k -> new LinkedHashSet<>()).add(bare);
        }
        allKeys.add(bare);
        if (value != null) {
            valuesByKey.computeIfAbsent(bare, k -> new LinkedHashSet<>()).add(value);
        }
    }

    @SuppressWarnings("unchecked")
    private void acceptYamlNode(String profile, String pathSoFar, Object value) {
        if (value instanceof Map) {
            // Same non-String-key hazard as readYaml: cast to Map<Object, Object>, stringify explicitly.
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) value).entrySet()) {
                String childKey = String.valueOf(entry.getKey());
                String childPath = pathSoFar.isEmpty() ? childKey : pathSoFar + "." + childKey;
                acceptYamlNode(profile, childPath, entry.getValue());
            }
            return;
        }
        if (pathSoFar.isEmpty()) {
            return;
        }
        keysByProfile.computeIfAbsent(profile, k -> new LinkedHashSet<>()).add(pathSoFar);
        allKeys.add(pathSoFar);
        valuesByKey.computeIfAbsent(pathSoFar, k -> new LinkedHashSet<>()).add(String.valueOf(value));
    }

    /** Keys of every profile, profile prefix stripped. */
    public Set<String> allKeys() {
        return allKeys;
    }

    /** Per-profile key sets, kept for profile surfacing in the report (M3). */
    public Map<String, Set<String>> keysByProfile() {
        return keysByProfile;
    }

    /**
     * TASK-7: bare (profile prefix stripped) key -&gt; every raw scalar value it was set to, merged
     * across every profile it appears under -- {@link io.github.pantinor.qea.plugin.configroot.ValueRules}
     * needs "was this key ever set to this value in any profile", the same "any profile" scope {@link
     * #allKeys()} already uses for key presence.
     */
    public Map<String, Set<String>> valuesByKey() {
        return valuesByKey;
    }
}
