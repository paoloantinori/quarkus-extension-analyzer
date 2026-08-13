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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Signal 3: an extension already known to be used (via config or bytecode) pulls in, transitively,
 * every extension it needs to function, via two edge kinds (both from
 * {@code io.quarkus.bootstrap.model.ApplicationModel}, per DESIGN.md):
 *
 * <ul>
 *   <li>capability requires/provides: extension A requires capability C, extension B provides C;</li>
 *   <li>hard extension-to-extension dependency: A directly depends on extension B (e.g. RESTEasy
 *       Jackson pulling RESTEasy).</li>
 * </ul>
 *
 * <p>The join is a fixed point: an extension newly marked used by one edge can itself pull in further
 * providers/dependencies in the next round.
 */
public final class CapabilityJoin {

    private CapabilityJoin() {
    }

    /**
     * Why a previously-unused extension was pulled in. {@code reason} is either a required-capability
     * name, or the {@link #DIRECT_EXTENSION_DEPENDENCY} marker; callers should not compare against that
     * marker directly and should use {@link #isDirectExtensionDependency()} instead.
     */
    public record Edge(String requiringGa, String reason, String providingGa) {
        public boolean isDirectExtensionDependency() {
            return DIRECT_EXTENSION_DEPENDENCY.equals(reason);
        }
    }

    private static final String DIRECT_EXTENSION_DEPENDENCY = "(direct-extension-dependency)";

    /**
     * @param nodes            every resolved extension, keyed by GA
     * @param initiallyUsedGas extensions already marked used by signal 1 (config) or signal 2 (bytecode)
     * @return GAs newly marked used by this join (not already in {@code initiallyUsedGas}), each with
     *         the edge that justifies it
     */
    public static Map<String, Edge> join(Map<String, ExtensionNode> nodes, Set<String> initiallyUsedGas) {
        Map<String, String> capabilityToProvider = new HashMap<>();
        for (ExtensionNode node : nodes.values()) {
            for (String capability : node.providesCapabilities()) {
                // A capability normally has exactly one provider; first-registered wins on a clash
                // rather than the join failing outright.
                capabilityToProvider.putIfAbsent(capability, node.ga());
            }
        }

        Map<String, Edge> newlyUsed = new LinkedHashMap<>();
        Set<String> used = new HashSet<>(initiallyUsedGas);
        List<String> frontier = new ArrayList<>(used);
        while (!frontier.isEmpty()) {
            List<String> nextFrontier = new ArrayList<>();
            for (String ga : frontier) {
                ExtensionNode node = nodes.get(ga);
                if (node == null) {
                    continue;
                }
                for (String requiredCapability : node.requiresCapabilities()) {
                    String provider = capabilityToProvider.get(requiredCapability);
                    if (provider != null && used.add(provider)) {
                        newlyUsed.put(provider, new Edge(ga, requiredCapability, provider));
                        nextFrontier.add(provider);
                    }
                }
                for (String dep : node.directExtensionDeps()) {
                    if (nodes.containsKey(dep) && used.add(dep)) {
                        newlyUsed.put(dep, new Edge(ga, DIRECT_EXTENSION_DEPENDENCY, dep));
                        nextFrontier.add(dep);
                    }
                }
            }
            frontier = nextFrontier;
        }
        return newlyUsed;
    }
}
