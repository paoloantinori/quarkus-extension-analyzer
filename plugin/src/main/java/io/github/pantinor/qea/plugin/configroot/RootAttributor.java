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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Credits every application-config key to the <em>narrowest</em> matching config-root claim across
 * all extensions ("narrowest-claimant-wins"), instead of crediting it to every extension whose root
 * happens to be a prefix.
 *
 * <p>This exists to kill the false-positive class M1 documented for {@code quarkus-logging-json}
 * (see docs/SPIKE-RESULTS.md): that extension's own sources disagree on granularity, contributing
 * both the narrow root it really owns ({@code quarkus.log.console.json.}) and a broad root
 * ({@code quarkus.log.}) that really belongs to whichever extension owns general Quarkus logging
 * config. Per-extension containment matching (M1's approach) credits the broad root's owner for
 * every key under it, including subtrees a different extension claims more specifically. Attributing
 * globally, by longest matching prefix across every extension's claims, routes a key to the most
 * specific owner instead.
 *
 * <p>Roots are matched against the whole extension universe (not just directly-declared extensions),
 * since the "true", narrower owner of a broad root's subtree is often a transitive extension (e.g.
 * Quarkus core) that the application never declares directly.
 */
public final class RootAttributor {

    private RootAttributor() {
    }

    /** One application-config key credited to the narrowest root claim(s) that matched it. */
    public record Attribution(String key, String root, Set<String> owners) {
    }

    /**
     * @param claims extension GA -&gt; config-root prefixes it claims (each ending in {@code '.'})
     * @param keys   application-config keys to attribute
     * @return one {@link Attribution} per key that matched at least one claimed root; ties (two or
     *         more extensions claiming the identical narrowest root) credit every tied owner, since a
     *         spurious extra "used" credit is safer than a dropped one for this signal.
     */
    public static List<Attribution> attribute(Map<String, Set<String>> claims, Set<String> keys) {
        Map<String, Set<String>> ownersByRoot = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : claims.entrySet()) {
            for (String root : entry.getValue()) {
                ownersByRoot.computeIfAbsent(root, r -> new TreeSet<>()).add(entry.getKey());
            }
        }

        List<String> rootsByLengthDesc = new ArrayList<>(ownersByRoot.keySet());
        rootsByLengthDesc.sort(Comparator.comparingInt(String::length).reversed());

        List<Attribution> out = new ArrayList<>();
        for (String key : keys) {
            for (String root : rootsByLengthDesc) {
                if (matches(key, root)) {
                    out.add(new Attribution(key, root, ownersByRoot.get(root)));
                    break;
                }
            }
        }
        return out;
    }

    /** Extension GA -&gt; keys it was credited for via {@link #attribute}. */
    public static Map<String, List<String>> byOwner(List<Attribution> attributions) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Attribution a : attributions) {
            for (String owner : a.owners()) {
                out.computeIfAbsent(owner, o -> new ArrayList<>()).add(a.key());
            }
        }
        return out;
    }

    private static boolean matches(String key, String root) {
        String exact = root.substring(0, root.length() - 1);
        return key.startsWith(root) || key.equals(exact);
    }
}
