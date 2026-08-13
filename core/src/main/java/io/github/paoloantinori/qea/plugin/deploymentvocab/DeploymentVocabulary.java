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
package io.github.paoloantinori.qea.plugin.deploymentvocab;

import io.github.paoloantinori.qea.plugin.bytecode.BytecodeUsage;
import io.github.paoloantinori.qea.plugin.util.PathUtils;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Signal 4 (TASK-8): the set of types an extension's {@code -deployment} jar REFERENCES, harvested as
 * the extension's "deployment vocabulary". This vocabulary is the library/API/annotation types the
 * extension knows about: bean types it produces (e.g. hibernate-validator references
 * {@code jakarta.validation.Validator}), clients it wires (kubernetes-client references
 * {@code io.fabric8...KubernetesClient}), and annotations it consumes (scheduler references
 * {@code io.quarkus.scheduler.Scheduled}).
 *
 * <p>When the analyzed app's compiled bytecode references a type that appears in EXACTLY ONE declared
 * extension's deployment vocabulary, the app is using that extension's contribution, so the extension
 * is credited {@code used-bean-producer} (TASK-8), even when the type lives in a shared jar that the
 * exclusive transitive-API signal (TASK-5) deliberately does not attribute. The exclusivity filter
 * (one vocabulary only) preserves the conservative safety property: a type referenced by two or more
 * declared extensions' deployment jars is ambiguous and is never attributed, exactly mirroring
 * {@code TransitiveApiAttribution}.
 *
 * <p>Reuses {@link BytecodeUsage#referencedTypes(Index)} so the deployment-jar vocabulary is computed
 * by the same referenced-type walk as the app-classes scan; the signal is then a set intersection, not
 * new analysis logic. No hand-curated producer table: the vocabulary is harvested from bytecode, which
 * the empirical TASK-8 phase-A scan confirmed carries the app-facing types for the producer and
 * annotation-consumer patterns (see docs/AUTONOMOUS-WORK-LOG.md, TASK-8 phase A).
 */
public final class DeploymentVocabulary {

    private DeploymentVocabulary() {
    }

    /**
     * Every type referenced by the classes in the given deployment-jar path(s), or an empty set if the
     * path is not a readable jar. A corrupt or non-jar path degrades to an empty vocabulary (this
     * extension then simply has no fourth-signal evidence), never throws.
     *
     * @param deploymentJar the resolved {@code -deployment} jar path (first jar wins if multiple)
     */
    public static Set<String> vocabularyOf(Path deploymentJar) {
        if (deploymentJar == null) {
            return Set.of();
        }
        Indexer indexer = new Indexer();
        boolean any = false;
        try (ZipFile zip = new ZipFile(deploymentJar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    indexer.index(in);
                    any = true;
                } catch (IOException | IllegalArgumentException ignored) {
                    // Unparseable class (multi-release, corrupt); skip it, coverage over completeness.
                }
            }
        } catch (IOException | RuntimeException e) {
            // A deployment jar that cannot be opened (missing, corrupt zip) yields no vocabulary; the
            // extension falls back to the other signals rather than aborting the run.
            return Set.of();
        }
        if (!any) {
            return Set.of();
        }
        return BytecodeUsage.referencedTypes(indexer.complete());
    }

    /**
     * The first jar path among the given resolved paths, or {@code null}; convenience for callers that
     * hold an extension's resolved paths and need the single deployment jar.
     */
    public static Path firstDeploymentJar(java.util.Collection<Path> paths) {
        return PathUtils.firstJar(paths);
    }
}
