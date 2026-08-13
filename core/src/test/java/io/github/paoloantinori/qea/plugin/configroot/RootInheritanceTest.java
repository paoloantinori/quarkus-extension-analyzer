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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RootInheritanceTest {

    /**
     * The M1 exit criterion (docs/SPIKE-RESULTS.md, "The JDBC hard case"): none of the four JDBC
     * drivers own a config root of their own, but they all directly depend on {@code quarkus-agroal},
     * which does own {@code quarkus.datasource.}. This models that scenario at a reduced scale (one
     * driver instead of four, {@code quarkus-core}/{@code quarkus-arc} standing in for the bench's
     * three ubiquitous extensions), using the exact dependent-count shape that makes them "ubiquitous"
     * (depended on by more than half of the extension universe).
     */
    @Test
    void jdbcDriverInheritsRootFromItsConnectionPoolDependency() {
        Map<String, Set<String>> ownRoots = new LinkedHashMap<>();
        ownRoots.put("io.quarkus:quarkus-jdbc-h2", Set.of());
        ownRoots.put("io.quarkus:quarkus-agroal", Set.of("quarkus.datasource."));
        ownRoots.put("io.quarkus:quarkus-core", Set.of());
        ownRoots.put("io.quarkus:quarkus-arc", Set.of());

        Map<String, Set<String>> directExtensionDeps = new LinkedHashMap<>();
        directExtensionDeps.put("io.quarkus:quarkus-jdbc-h2",
                Set.of("io.quarkus:quarkus-agroal", "io.quarkus:quarkus-core", "io.quarkus:quarkus-arc"));
        directExtensionDeps.put("io.quarkus:quarkus-agroal",
                Set.of("io.quarkus:quarkus-core", "io.quarkus:quarkus-arc"));
        directExtensionDeps.put("io.quarkus:quarkus-core", Set.of());
        directExtensionDeps.put("io.quarkus:quarkus-arc", Set.of("io.quarkus:quarkus-core"));

        // 4 extensions total; quarkus-core has 3 dependents (> 50%) and quarkus-arc has 2 (== 50%,
        // not strictly greater, so it stays non-ubiquitous here) -- see assertion below.
        RootInheritance.Result result = RootInheritance.inherit(ownRoots, directExtensionDeps, 4);

        assertThat(result.ubiquitous()).containsExactly("io.quarkus:quarkus-core");
        assertThat(result.inherited()).containsOnlyKeys("io.quarkus:quarkus-jdbc-h2");
        // quarkus-arc is also a (non-ubiquitous) direct dependency of the driver here, but contributes
        // nothing because its own root set is empty; only quarkus-agroal's root is inherited.
        assertThat(result.inherited().get("io.quarkus:quarkus-jdbc-h2")).containsExactly(
                new RootInheritance.InheritedRoot("quarkus.datasource.", "io.quarkus:quarkus-agroal"));
    }

    @Test
    void ubiquitousDependencyRootsAreExcludedFromInheritance() {
        Map<String, Set<String>> ownRoots = new LinkedHashMap<>();
        ownRoots.put("ext:leaf", Set.of());
        ownRoots.put("ext:core", Set.of("quarkus.log."));

        Map<String, Set<String>> directExtensionDeps = new LinkedHashMap<>();
        // 3 extensions depend on ext:core out of 4 total (> 50%): ubiquitous.
        directExtensionDeps.put("ext:leaf", Set.of("ext:core"));
        directExtensionDeps.put("ext:a", Set.of("ext:core"));
        directExtensionDeps.put("ext:b", Set.of("ext:core"));
        directExtensionDeps.put("ext:core", Set.of());

        RootInheritance.Result result = RootInheritance.inherit(ownRoots, directExtensionDeps, 4);

        assertThat(result.ubiquitous()).containsExactly("ext:core");
        assertThat(result.inherited()).doesNotContainKey("ext:leaf");
    }

    @Test
    void extensionWithItsOwnRootsDoesNotInherit() {
        Map<String, Set<String>> ownRoots = new LinkedHashMap<>();
        ownRoots.put("ext:a", Set.of("quarkus.a."));
        ownRoots.put("ext:b", Set.of("quarkus.b."));

        Map<String, Set<String>> directExtensionDeps = new LinkedHashMap<>();
        directExtensionDeps.put("ext:a", Set.of("ext:b"));

        RootInheritance.Result result = RootInheritance.inherit(ownRoots, directExtensionDeps, 2);

        assertThat(result.inherited()).isEmpty();
    }
}
