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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Derived config-root signal for extensions that own no config root of their own, e.g. the four JDBC
 * drivers ({@code quarkus-jdbc-h2}, {@code -mysql}, {@code -postgresql}, {@code -mssql}), which are
 * pure driver implementations with no {@code @ConfigMapping} of their own (M1 exit criterion).
 *
 * <p>An extension inherits the config roots of the extensions it directly depends on, excluding roots
 * owned by "ubiquitous" extensions: those depended on by more than {@link #UBIQUITY_THRESHOLD} of the
 * whole extension universe (e.g. {@code quarkus-core}, {@code quarkus-arc}). Without that exclusion
 * every extension would trivially inherit e.g. {@code quarkus.log.*} from core and the signal would be
 * worthless.
 */
public final class RootInheritance {

    /** Below this fraction of dependents the inheritance heuristic treats a root as extension-specific. */
    public static final double UBIQUITY_THRESHOLD = 0.5d;

    private RootInheritance() {
    }

    /** One root inherited from a directly-depended-on extension. */
    public record InheritedRoot(String root, String fromGa) {
    }

    /** {@link #inherit} result: the inherited roots plus the ubiquitous extensions excluded from them. */
    public record Result(Map<String, Set<InheritedRoot>> inherited, Set<String> ubiquitous) {
    }

    /**
     * @param ownRoots              extension GA -&gt; its own claimed roots (possibly empty)
     * @param directExtensionDeps   extension GA -&gt; direct dependency GAs that are themselves known
     *                              extensions
     * @param totalExtensionCount   denominator for the ubiquity threshold (size of the whole resolved
     *                              extension universe, not just directly-declared ones)
     */
    public static Result inherit(Map<String, Set<String>> ownRoots, Map<String, Set<String>> directExtensionDeps,
            int totalExtensionCount) {
        Map<String, Integer> dependents = new HashMap<>();
        for (Set<String> deps : directExtensionDeps.values()) {
            for (String dep : deps) {
                dependents.merge(dep, 1, Integer::sum);
            }
        }
        Set<String> ubiquitous = new HashSet<>();
        for (Map.Entry<String, Integer> entry : dependents.entrySet()) {
            if (entry.getValue() > totalExtensionCount * UBIQUITY_THRESHOLD) {
                ubiquitous.add(entry.getKey());
            }
        }

        Map<String, Set<InheritedRoot>> inherited = new LinkedHashMap<>();
        for (String ga : ownRoots.keySet()) {
            if (!ownRoots.getOrDefault(ga, Set.of()).isEmpty()) {
                continue; // only fills the gap for extensions with no root of their own
            }
            Set<InheritedRoot> roots = new LinkedHashSet<>();
            for (String dep : directExtensionDeps.getOrDefault(ga, Set.of())) {
                if (ubiquitous.contains(dep)) {
                    continue;
                }
                for (String root : ownRoots.getOrDefault(dep, Set.of())) {
                    roots.add(new InheritedRoot(root, dep));
                }
            }
            if (!roots.isEmpty()) {
                inherited.put(ga, roots);
            }
        }
        return new Result(inherited, ubiquitous);
    }
}
