package io.github.paoloantinori.qea.spike;

import java.io.IOException;
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
 * Reads an {@code application.properties} and flattens every profile into a single key space.
 *
 * <p>Deliberately hand-rolled rather than delegating to SmallRye Config resolution: the analyzer
 * must see keys that reference unresolvable {@code ${...}} expressions or environment variables,
 * which a real config resolution would fail on. Values are irrelevant to the config-root signal
 * (except for the db-kind discrimination noted as future work), so only keys are retained. Key
 * extraction itself is delegated to {@link Properties#load(Reader)} (comment skipping, backslash
 * continuation and separator handling all come for free); only the {@code %profile.} prefix
 * splitting on top of the parsed key set is hand-rolled, since that is a Quarkus convention
 * {@link Properties} knows nothing about.
 */
public final class AppConfig {

    private final Map<String, Set<String>> keysByProfile = new LinkedHashMap<>();
    private final Set<String> allKeys = new TreeSet<>();
    private int rawLines;

    public static AppConfig read(Path propertiesFile) throws IOException {
        AppConfig cfg = new AppConfig();
        cfg.rawLines = Files.readAllLines(propertiesFile, StandardCharsets.UTF_8).size();

        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(propertiesFile, StandardCharsets.UTF_8)) {
            props.load(in);
        }
        for (String key : props.stringPropertyNames()) {
            cfg.accept(key);
        }
        return cfg;
    }

    private void accept(String key) {
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
    }

    /** Keys of every profile, profile prefix stripped. */
    public Set<String> allKeys() {
        return allKeys;
    }

    public Map<String, Set<String>> keysByProfile() {
        return keysByProfile;
    }

    public int rawLines() {
        return rawLines;
    }

    public Set<String> quarkusKeys() {
        Set<String> q = new TreeSet<>();
        for (String k : allKeys) {
            if (k.startsWith("quarkus.")) {
                q.add(k);
            }
        }
        return q;
    }

    /**
     * Keys falling under any of the given config-root prefixes, grouped by the (single) root each
     * key matched. Roots are tried in the order given by the caller's {@link Set} and a key is
     * assigned to the first root whose prefix it matches, so a key covered by two overlapping roots
     * (e.g. {@code quarkus.log.} and {@code quarkus.log.console.json.}) is not double counted.
     */
    public Map<String, List<String>> match(Set<String> roots) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String key : allKeys) {
            for (String root : roots) {
                String exact = root.substring(0, root.length() - 1);
                if (key.startsWith(root) || key.equals(exact)) {
                    grouped.computeIfAbsent(root, r -> new ArrayList<>()).add(key);
                    break;
                }
            }
        }
        return grouped;
    }

    /** Flat, deduplicated view of every key in a {@link #match(Set)} result. */
    public static List<String> flatten(Map<String, List<String>> matched) {
        List<String> out = new ArrayList<>();
        for (List<String> keys : matched.values()) {
            out.addAll(keys);
        }
        return out;
    }
}
