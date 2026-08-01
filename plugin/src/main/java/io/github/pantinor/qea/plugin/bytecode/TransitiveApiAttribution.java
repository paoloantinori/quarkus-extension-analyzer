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
package io.github.pantinor.qea.plugin.bytecode;

import io.quarkus.maven.dependency.Dependency;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependency;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * TASK-5: attributes each extension its EXCLUSIVE transitive non-Quarkus API, closing the DESIGN.md
 * signal-2 scope cut (docs/DESIGN.md, "M2 implements the extension's own runtime artifact check only").
 *
 * <p>A plain (non-extension) jar is attributed to a DECLARED extension (one this project directly
 * declares, i.e. one with a report row) only when it is reachable, via BFS over {@link
 * ResolvedDependency#getDirectDependencies()} starting at that extension's own runtime artifact, from
 * exactly that ONE declared extension, and is not itself directly declared by the project. Both
 * restrictions are deliberate: a jar reachable from two or more declared extensions is ambiguous
 * (crediting bytecode usage to either would be a guess), and a jar the project already declares directly
 * has its own, unrelated plain-jar bytecode signal, so attributing it to some extension too would
 * manufacture a second, redundant "used" reason instead of new evidence.
 *
 * <p><b>Declared vs. nested extensions (bench diagnosis, TASK-5 follow-up):</b> the resolved application
 * model contains many extensions that are never directly declared by the project -- pulled in transitively
 * by a declared extension (e.g. {@code quarkus-kubernetes-client} pulls in an internal, undeclared
 * {@code quarkus-kubernetes-client-internal}; nearly every extension pulls in {@code quarkus-arc}). Only
 * directly-declared extensions are attribution roots and only they count as "reaching" a jar for the
 * exclusivity check -- a nested extension is not itself an owner. The BFS still stops, without descending
 * further, at another DECLARED extension (that one has its own three-signal classification, so nothing
 * beneath it should be credited to the extension currently being walked), but it traverses THROUGH a
 * nested (non-declared) extension: that extension's own subtree belongs to its declared ancestor
 * conceptually, even though the nested extension's own artifact is itself excluded from the plain-jar
 * candidates (it is an extension, not a plain jar). An earlier version of this class used every extension
 * in the whole model as both a root and a "reaches" contributor; that made every jar under a nested
 * extension automatically shared between the nested extension and its declared ancestor, so nothing below
 * an extension-of-an-extension could ever be exclusive -- confirmed via the {@code -Dqea.debugAttribution}
 * trace against the apicurio-registry bench, where {@code quarkus-kubernetes-client-internal} (never a
 * report row) was shadowing {@code io.fabric8:kubernetes-client-api} out of {@code
 * quarkus-kubernetes-client}'s attribution.
 *
 * <p>The BFS also stops at any edge {@link DependencyFlags#MISSING_FROM_APPLICATION}, or one whose target
 * is not part of the resolved application dependency set at all: there is nothing to scan there.
 *
 * <p>Pure graph analysis, no I/O: this class only decides which jars are candidates. Scanning a candidate
 * jar for its contained classes, and checking those against the project's referenced types, is the
 * caller's job ({@code Analyzer}), so this class stays unit-testable with synthetic {@link
 * ResolvedDependency} instances.
 */
public final class TransitiveApiAttribution {

    private TransitiveApiAttribution() {
    }

    /**
     * TASK-11: the full result of {@link #attribute}, exposing not just the exclusive-jar attribution
     * ({@link #exclusiveByExtension}) but the raw reachability data it was computed from, so a caller can
     * build additional evidence signals -- e.g. {@code Analyzer}'s shared-referenced-jars hint for suspect
     * rows -- without a second BFS pass over the same dependency graph.
     *
     * @param exclusiveByExtension  declared extension GA -&gt; GAs of its exclusive transitive plain jars
     *                              (sorted, deterministic iteration order); extensions with no exclusive
     *                              jars are absent from the map, not mapped to an empty set. This is the
     *                              field the TASK-5 transitive-API bytecode signal itself consumes.
     * @param reachableByExtension  declared extension GA -&gt; EVERY plain jar reachable from its subtree,
     *                              before the exclusivity filter -- includes jars {@link
     *                              #exclusiveByExtension} excludes for being shared or directly declared
     * @param extensionsReachingJar plain jar GA -&gt; the declared extension GAs that reach it (sorted);
     *                              size 1 for a jar exclusive to one extension, 2+ for a jar shared
     *                              between extensions
     */
    public record Result(Map<String, Set<String>> exclusiveByExtension, Map<String, Set<String>> reachableByExtension,
            Map<String, Set<String>> extensionsReachingJar) {
    }

    /**
     * @param declaredExtensions the project's directly-declared extensions only (one report row each);
     *                           these are both the BFS roots and the only possible "owners" an exclusive
     *                           jar can be attributed to
     * @param allDepsByGa        {@code groupId:artifactId} -&gt; resolved dependency, for every dependency
     *                           in the resolved application model, used both to continue the BFS past a
     *                           plain jar and to check whether a candidate jar is directly declared
     * @param allExtensionGas    {@code groupId:artifactId} of EVERY extension in the whole resolved model,
     *                           declared or not -- used only to recognize a nested extension node during
     *                           the BFS (traverse through it, but never attribute its own artifact); must
     *                           be a superset of {@code declaredExtensions}' GAs
     * @return see {@link Result}
     */
    public static Result attribute(Collection<ResolvedDependency> declaredExtensions,
            Map<String, ResolvedDependency> allDepsByGa, Set<String> allExtensionGas) {
        return attribute(declaredExtensions, allDepsByGa, allExtensionGas, null);
    }

    /**
     * Same as {@link #attribute(Collection, Map, Set)}, with an optional diagnostic sink (TASK-5 bench
     * follow-up, {@code -Dqea.debugAttribution=true}): when non-{@code null}, {@code debugLog} receives one
     * line per declared extension's raw (pre-exclusivity) reachable-jar subtree, one line per jar found to
     * be shared by two or more declared extensions (naming all of them), one line per jar excluded for
     * being both exclusive and directly declared, and one line per extension's final exclusive-candidate
     * set. This is the only way to see, after the fact, *why* a given jar was or wasn't attributed -- the
     * exclusivity decision is a whole-model property (which other declared extensions also reach the jar),
     * not visible from any single extension's own data. This trace is what surfaced the declared-vs-nested
     * bug documented on the class: without it, "shared" told only half the story (shared with what).
     */
    public static Result attribute(Collection<ResolvedDependency> declaredExtensions,
            Map<String, ResolvedDependency> allDepsByGa, Set<String> allExtensionGas, Consumer<String> debugLog) {
        Set<String> declaredExtensionGas = new HashSet<>();
        for (ResolvedDependency extension : declaredExtensions) {
            declaredExtensionGas.add(ga(extension));
        }

        Map<String, Set<String>> reachableByExtension = new LinkedHashMap<>();
        Map<String, Set<String>> extensionsReaching = new HashMap<>();

        for (ResolvedDependency extension : declaredExtensions) {
            String extensionGa = ga(extension);
            Set<String> reachablePlainJars =
                    reachablePlainJars(extensionGa, allDepsByGa, allExtensionGas, declaredExtensionGas);
            reachableByExtension.put(extensionGa, reachablePlainJars);
            if (debugLog != null) {
                debugLog.accept("[qea-debug] subtree " + extensionGa + " reachable plain jars ("
                        + reachablePlainJars.size() + "): " + reachablePlainJars);
            }
            for (String plainGa : reachablePlainJars) {
                extensionsReaching.computeIfAbsent(plainGa, k -> new HashSet<>()).add(extensionGa);
            }
        }

        Map<String, Set<String>> exclusiveByExtension = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : reachableByExtension.entrySet()) {
            String extensionGa = entry.getKey();
            Set<String> exclusive = new TreeSet<>();
            for (String plainGa : entry.getValue()) {
                Set<String> owners = extensionsReaching.get(plainGa);
                if (owners.size() != 1) {
                    if (debugLog != null) {
                        debugLog.accept("[qea-debug] jar " + plainGa + " is SHARED, reached by "
                                + new TreeSet<>(owners) + "; not attributed to " + extensionGa);
                    }
                    continue; // shared: ambiguity must not manufacture used verdicts (plan item 1)
                }
                ResolvedDependency plainDep = allDepsByGa.get(plainGa);
                if (plainDep.isDirect()) {
                    if (debugLog != null) {
                        debugLog.accept("[qea-debug] jar " + plainGa + " is exclusive to " + extensionGa
                                + " but is also directly declared by the project; not attributed");
                    }
                    continue; // directly declared by the project: has its own, unrelated bytecode signal
                }
                exclusive.add(plainGa);
            }
            if (!exclusive.isEmpty()) {
                exclusiveByExtension.put(extensionGa, exclusive);
                if (debugLog != null) {
                    debugLog.accept("[qea-debug] extension " + extensionGa + " exclusive candidate jars: " + exclusive);
                }
            }
        }

        // TASK-11: sort both raw-reachability maps for deterministic downstream iteration (the shared-
        // referenced-jars hint's "alsoReachableFrom" list must not depend on HashSet/LinkedHashSet
        // iteration order), mirroring exclusiveByExtension's own TreeSet already above.
        Map<String, Set<String>> reachableByExtensionSorted = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : reachableByExtension.entrySet()) {
            reachableByExtensionSorted.put(entry.getKey(), new TreeSet<>(entry.getValue()));
        }
        Map<String, Set<String>> extensionsReachingSorted = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : extensionsReaching.entrySet()) {
            extensionsReachingSorted.put(entry.getKey(), new TreeSet<>(entry.getValue()));
        }
        return new Result(exclusiveByExtension, reachableByExtensionSorted, extensionsReachingSorted);
    }

    /**
     * BFS over direct dependencies from {@code rootGa} (a declared extension), collecting every plain
     * (non-extension) jar reached. Stops, without enqueueing, at another DECLARED extension. Traverses
     * through a nested (non-declared) extension -- its subtree belongs to {@code rootGa} conceptually --
     * without adding the nested extension's own artifact to the result: it is not a plain jar.
     */
    private static Set<String> reachablePlainJars(String rootGa, Map<String, ResolvedDependency> allDepsByGa,
            Set<String> allExtensionGas, Set<String> declaredExtensionGas) {
        Set<String> visited = new HashSet<>();
        visited.add(rootGa);
        Set<String> reachablePlainJars = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootGa);
        while (!queue.isEmpty()) {
            ResolvedDependency node = allDepsByGa.get(queue.poll());
            if (node == null) {
                continue;
            }
            Collection<Dependency> directDeps = node.getDirectDependencies();
            if (directDeps == null) {
                continue;
            }
            for (Dependency dep : directDeps) {
                if (dep.isFlagSet(DependencyFlags.MISSING_FROM_APPLICATION)) {
                    continue;
                }
                String depGa = dep.getGroupId() + ":" + dep.getArtifactId();
                if (!visited.add(depGa) || !allDepsByGa.containsKey(depGa)) {
                    continue;
                }
                if (declaredExtensionGas.contains(depGa)) {
                    // Another DECLARED extension: it has its own signal and its own attribution root; do
                    // not walk into it, and it is not a plain-jar candidate.
                    continue;
                }
                if (allExtensionGas.contains(depGa)) {
                    // A nested extension, never directly declared: its subtree belongs to rootGa
                    // conceptually, so traverse through it, but its own artifact is not a plain jar.
                    queue.add(depGa);
                    continue;
                }
                reachablePlainJars.add(depGa);
                queue.add(depGa);
            }
        }
        return reachablePlainJars;
    }

    private static String ga(ResolvedDependency d) {
        return d.getGroupId() + ":" + d.getArtifactId();
    }
}
