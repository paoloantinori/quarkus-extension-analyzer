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
package io.github.paoloantinori.qea.plugin.buildsteps;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.MethodInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * TASK-40, rung 3 of the total-detection ladder: the build-step producer/consumer graph, mined
 * from the -deployment artifacts' bytecode. This is the Quarkus-native authority: augmentation
 * fails when a REQUIRED build item has no producer, so an extension whose steps produce an item
 * that another extension's steps consume (as a plain, non-optional parameter) is load-bearing for
 * the build by the same mechanism Quarkus itself uses to fail.
 *
 * <p>Scope is deliberately DIRECT required consumption only: a {@code @BuildStep} method
 * parameter (no {@code @Nullable}, no {@code List<T>} multi-consume unwrapping for v1) whose
 * item type is produced by a {@code @BuildStep} method's RETURN type in another extension's
 * deployment artifact. Transitive chains and optional consumers stay out until a bench demands
 * them (the same incremental discipline TASK-38 used).
 *
 * <p>Known limitation (the honest totality statement): items consumed via
 * {@code MultiBuildItem} collections or produced via {@code @Produce} on void methods are not
 * extracted for v1; those edges are invisible here and remain TASK-40 follow-up scope.
 */
public final class BuildStepGraph {

    private static final DotName BUILD_STEP = DotName.createSimple("io.quarkus.deployment.annotations.BuildStep");

    private BuildStepGraph() {
    }

    /**
     * The direct required-consumption edges between extensions: producer GA -> the evidence line
     * naming the item and the consumer. Only the FIRST edge per producer is kept (deterministic:
     * extension GAs and items iterate in sorted order), because the engine's evidence field is a
     * single string.
     *
     * @param deploymentJarsByGa extension GA (runtime, no -deployment suffix) -> its -deployment
     *                           artifact paths (jars or classes directories)
     */
    public static Map<String, String> producerEdges(Map<String, List<Path>> deploymentJarsByGa)
            throws IOException {
        Map<String, Index> indexByGa = new LinkedHashMap<>();
        for (Map.Entry<String, List<Path>> e : deploymentJarsByGa.entrySet()) {
            Index idx = indexArtifacts(e.getValue());
            if (idx != null) {
                indexByGa.put(e.getKey(), idx);
            }
        }
        // item FQCN -> the GAs whose steps PRODUCE it
        Map<String, Set<String>> producersByItem = new TreeMap<>();
        // GA -> the item FQCNs its steps CONSUME (direct, required)
        Map<String, Set<String>> consumedByGa = new TreeMap<>();
        for (Map.Entry<String, Index> e : indexByGa.entrySet()) {
            String ga = e.getKey();
            for (AnnotationInstance ai : e.getValue().getAnnotations(BUILD_STEP)) {
                if (ai.target() == null || ai.target().kind() != AnnotationTarget.Kind.METHOD) {
                    continue;
                }
                MethodInfo m = ai.target().asMethod();
                if (m.returnType() != null
                        && m.returnType().name().toString().endsWith("BuildItem")) {
                    producersByItem.computeIfAbsent(m.returnType().name().toString(),
                            k -> new LinkedHashSet<>()).add(ga);
                }
                for (var t : m.parameterTypes()) {
                    if (t.name().toString().endsWith("BuildItem")) {
                        consumedByGa.computeIfAbsent(ga, k -> new LinkedHashSet<>())
                                .add(t.name().toString());
                    }
                }
            }
        }
        Map<String, String> edges = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : consumedByGa.entrySet()) {
            for (String item : e.getValue()) {
                for (String producer : producersByItem.getOrDefault(item, Set.of())) {
                    if (producer.equals(e.getKey())) {
                        continue; // self-consumption inside one extension is not an edge
                    }
                    edges.putIfAbsent(producer,
                            "build-step: produces " + item + ", consumed by " + e.getKey()
                                    + "'s build steps (the augmentation authority: a required item"
                                    + " without its producer fails the build)");
                }
            }
        }
        return edges;
    }

    /** A Jandex index over every .class entry in the given jars / classes directories, or null. */
    static Index indexArtifacts(List<Path> artifacts) throws IOException {
        Indexer indexer = new Indexer();
        boolean any = false;
        for (Path artifact : artifacts) {
            if (artifact == null || !Files.exists(artifact)) {
                continue;
            }
            if (Files.isDirectory(artifact)) {
                try (var walk = Files.walk(artifact)) {
                    for (Path c : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".class"))::iterator) {
                        try (InputStream in = Files.newInputStream(c)) {
                            indexer.index(in);
                            any = true;
                        } catch (IOException | IllegalArgumentException ignored) {
                            // unparseable: skip
                        }
                    }
                }
            } else if (artifact.toString().endsWith(".jar")) {
                try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(artifact))) {
                    for (ZipEntry ze; (ze = zip.getNextEntry()) != null; ) {
                        if (ze.getName().endsWith(".class")) {
                            try {
                                indexer.index(zip);
                                any = true;
                            } catch (IOException | IllegalArgumentException ignored) {
                                // unparseable: skip
                            }
                        }
                    }
                }
            }
        }
        return any ? indexer.complete() : null;
    }
}
