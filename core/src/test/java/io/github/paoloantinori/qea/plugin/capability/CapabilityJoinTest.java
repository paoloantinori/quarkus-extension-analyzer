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
package io.github.paoloantinori.qea.plugin.capability;

import io.github.paoloantinori.qea.plugin.model.ExtensionNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M1 spike's validation bench declared zero {@code requires} capabilities (docs/SPIKE-RESULTS.md,
 * "Signal 3 (capabilities) data is present and usable straight off the model... This app happens to
 * declare zero requires capabilities, so the 'A requires C, B provides C' chain could not be exercised
 * end-to-end on this bench"). Per the M2 plan (item 7), this join is exercised here against a synthetic
 * model instead.
 */
class CapabilityJoinTest {

    @Test
    void providerOfARequiredCapabilityIsMarkedUsed() {
        ExtensionNode reactiveResteasy = new ExtensionNode("io.quarkus:quarkus-resteasy-reactive", Set.of(),
                Set.of(), Set.of("io.quarkus.vertx.http"));
        ExtensionNode vertxHttp = new ExtensionNode("io.quarkus:quarkus-vertx-http", Set.of(),
                Set.of("io.quarkus.vertx.http"), Set.of());
        Map<String, ExtensionNode> nodes = Map.of(
                reactiveResteasy.ga(), reactiveResteasy,
                vertxHttp.ga(), vertxHttp);

        Map<String, CapabilityJoin.Edge> newlyUsed = CapabilityJoin.join(nodes, Set.of(reactiveResteasy.ga()));

        assertThat(newlyUsed).containsOnlyKeys(vertxHttp.ga());
        CapabilityJoin.Edge edge = newlyUsed.get(vertxHttp.ga());
        assertThat(edge.requiringGa()).isEqualTo(reactiveResteasy.ga());
        assertThat(edge.reason()).isEqualTo("io.quarkus.vertx.http");
        assertThat(edge.providingGa()).isEqualTo(vertxHttp.ga());
    }

    @Test
    void directExtensionDependencyOfAUsedExtensionIsMarkedUsed() {
        // DESIGN.md's own example: RESTEasy Jackson pulling RESTEasy.
        ExtensionNode resteasyJackson = new ExtensionNode("io.quarkus:quarkus-resteasy-jackson",
                Set.of("io.quarkus:quarkus-resteasy"), Set.of(), Set.of());
        ExtensionNode resteasy = new ExtensionNode("io.quarkus:quarkus-resteasy", Set.of(), Set.of(), Set.of());
        Map<String, ExtensionNode> nodes = Map.of(
                resteasyJackson.ga(), resteasyJackson,
                resteasy.ga(), resteasy);

        Map<String, CapabilityJoin.Edge> newlyUsed = CapabilityJoin.join(nodes, Set.of(resteasyJackson.ga()));

        assertThat(newlyUsed).containsOnlyKeys(resteasy.ga());
        assertThat(newlyUsed.get(resteasy.ga()).isDirectExtensionDependency()).isTrue();
    }

    @Test
    void joinIsTransitive() {
        ExtensionNode a = new ExtensionNode("ext:a", Set.of(), Set.of(), Set.of("cap.b"));
        ExtensionNode b = new ExtensionNode("ext:b", Set.of(), Set.of("cap.b"), Set.of("cap.c"));
        ExtensionNode c = new ExtensionNode("ext:c", Set.of(), Set.of("cap.c"), Set.of());
        Map<String, ExtensionNode> nodes = new LinkedHashMap<>();
        nodes.put(a.ga(), a);
        nodes.put(b.ga(), b);
        nodes.put(c.ga(), c);

        Map<String, CapabilityJoin.Edge> newlyUsed = CapabilityJoin.join(nodes, Set.of("ext:a"));

        assertThat(newlyUsed).containsOnlyKeys("ext:b", "ext:c");
        assertThat(newlyUsed.get("ext:c").requiringGa()).isEqualTo("ext:b");
        assertThat(newlyUsed.get("ext:b").isDirectExtensionDependency()).isFalse();
    }

    @Test
    void extensionWithNoReachableEdgeStaysUnreported() {
        ExtensionNode used = new ExtensionNode("ext:used", Set.of(), Set.of(), Set.of());
        ExtensionNode orphan = new ExtensionNode("ext:orphan", Set.of(), Set.of("cap.unrelated"), Set.of());
        Map<String, ExtensionNode> nodes = Map.of(used.ga(), used, orphan.ga(), orphan);

        Map<String, CapabilityJoin.Edge> newlyUsed = CapabilityJoin.join(nodes, Set.of("ext:used"));

        assertThat(newlyUsed).isEmpty();
    }
}
