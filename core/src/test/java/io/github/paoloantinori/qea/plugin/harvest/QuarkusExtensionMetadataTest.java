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
package io.github.paoloantinori.qea.plugin.harvest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the public harvest facade on synthetic jars built in-memory. A real-extension smoke test
 * (quarkus-scheduler runtime + deployment from the local repository) backs these up: see the
 * TASK-18 notes in the backlog.
 */
class QuarkusExtensionMetadataTest {

    @TempDir
    Path dir;

    /**
     * A runtime jar carrying the two metadata files the probe reads (source A properties naming the
     * deployment artifact, source B yaml claiming a config root) yields both through the facade.
     */
    @Test
    void harvestsDeploymentGavAndConfigRootsFromSyntheticJar() throws Exception {
        Path jar = dir.resolve("fake-runtime.jar");
        try (JarOutputStream out = new JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/quarkus-extension.properties"));
            out.write("deployment-artifact=org.acme:fake-deployment:1.0\n".getBytes());
            out.closeEntry();
            out.putNextEntry(new JarEntry("META-INF/quarkus-extension.yaml"));
            out.write("name: Fake\nmetadata:\n  config:\n    - quarkus.fake.\n".getBytes());
            out.closeEntry();
        }

        QuarkusExtensionMetadata.Result r = QuarkusExtensionMetadata.harvest(jar, null);

        assertThat(r.deploymentArtifactGav()).isEqualTo("org.acme:fake-deployment:1.0");
        assertThat(r.configRoots()).containsExactly("quarkus.fake.");
        assertThat(r.errors()).isEmpty();
    }

    /** A jar without metadata yields empty sets (graceful degradation), never throws. */
    @Test
    void emptyJarYieldsEmptyMetadata() throws Exception {
        Path jar = dir.resolve("empty.jar");
        try (JarOutputStream out = new JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("placeholder.txt"));
            out.write("x".getBytes());
            out.closeEntry();
        }

        QuarkusExtensionMetadata.Result r = QuarkusExtensionMetadata.harvest(jar, null);

        assertThat(r.configRoots()).isEmpty();
        assertThat(r.deploymentArtifactGav()).isNull();
        assertThat(r.extensionDependencies()).isEmpty();
    }

    /** A missing path degrades to empty metadata rather than throwing (a tool's null-safety net). */
    @Test
    void missingFileDegradesToEmpty() {
        QuarkusExtensionMetadata.Result r = QuarkusExtensionMetadata.harvest(
                dir.resolve("does-not-exist.jar"), null);
        assertThat(r.configRoots()).isEmpty();
        assertThat(QuarkusExtensionMetadata.deploymentVocabulary(
                dir.resolve("no-deployment.jar"))).isEmpty();
    }
}
