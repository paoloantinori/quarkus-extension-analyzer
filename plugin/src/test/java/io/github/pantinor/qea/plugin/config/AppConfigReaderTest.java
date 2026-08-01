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
package io.github.pantinor.qea.plugin.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigReaderTest {

    @Test
    void splitsKeysByProfileAndFlattensBareKeys(@TempDir Path dir) throws IOException {
        Path file = write(dir, "application.properties", """
                quarkus.http.port=8080
                %dev.quarkus.log.level=DEBUG
                %dev,%test.quarkus.datasource.db-kind=h2
                """);

        AppConfigReader cfg = AppConfigReader.readProperties(file);

        assertThat(cfg.allKeys()).containsExactlyInAnyOrder(
                "quarkus.http.port", "quarkus.log.level", "quarkus.datasource.db-kind");
        assertThat(cfg.keysByProfile().get("<none>")).containsExactly("quarkus.http.port");
        assertThat(cfg.keysByProfile().get("dev"))
                .containsExactlyInAnyOrder("quarkus.log.level", "quarkus.datasource.db-kind");
        assertThat(cfg.keysByProfile().get("test")).containsExactly("quarkus.datasource.db-kind");
    }

    /**
     * docs/SPIKE-RESULTS.md, "Why the M2 config reader still cannot delegate to SmallRye Config": the
     * key must stay visible even when its value contains an environment-variable expression that would
     * be unresolvable outside a running application (a real SmallRye Config resolution would fail or
     * silently drop it).
     */
    @Test
    void keyWithUnresolvedPlaceholderValueStaysVisible(@TempDir Path dir) throws IOException {
        Path file = write(dir, "application.properties",
                "quarkus.datasource.jdbc.url=${DATABASE_URL:jdbc:postgresql://localhost/db}\n");

        AppConfigReader cfg = AppConfigReader.readProperties(file);

        assertThat(cfg.allKeys()).containsExactly("quarkus.datasource.jdbc.url");
    }

    @Test
    void quotedMapKeySegmentIsPreservedVerbatim(@TempDir Path dir) throws IOException {
        Path file = write(dir, "application.properties",
                "quarkus.log.category.\"io.apicurio\".level=DEBUG\n");

        AppConfigReader cfg = AppConfigReader.readProperties(file);

        assertThat(cfg.allKeys()).containsExactly("quarkus.log.category.\"io.apicurio\".level");
    }

    @Test
    void yamlIsFlattenedIntoDottedKeysWithProfileSections(@TempDir Path dir) throws IOException {
        Path file = write(dir, "application.yaml", """
                quarkus:
                  http:
                    port: 8080
                "%dev":
                  quarkus:
                    log:
                      level: DEBUG
                """);

        AppConfigReader cfg = AppConfigReader.readYaml(file);

        assertThat(cfg.allKeys()).containsExactlyInAnyOrder("quarkus.http.port", "quarkus.log.level");
        assertThat(cfg.keysByProfile().get("<none>")).containsExactly("quarkus.http.port");
        assertThat(cfg.keysByProfile().get("dev")).containsExactly("quarkus.log.level");
    }

    /**
     * SnakeYAML auto-types unquoted scalar keys ({@code 8080:}, {@code true:}) as Integer/Boolean, not
     * String. A cast to {@code Map<String, Object>} followed by reading such a key as {@code String}
     * throws {@link ClassCastException}; {@link AppConfigReader#readYaml} must survive it.
     */
    @Test
    void yamlWithNonStringKeysDoesNotThrow(@TempDir Path dir) throws IOException {
        Path file = write(dir, "application.yaml", """
                quarkus:
                  http:
                    port: 8080
                8080:
                  foo: bar
                true:
                  baz: qux
                """);

        AppConfigReader cfg = AppConfigReader.readYaml(file);

        assertThat(cfg.allKeys()).containsExactlyInAnyOrder("quarkus.http.port", "8080.foo", "true.baz");
    }

    @Test
    void emptyClaimSetProducesNoKeys(@TempDir Path dir) throws IOException {
        Path file = write(dir, "application.properties", "");

        AppConfigReader cfg = AppConfigReader.readProperties(file);

        assertThat(cfg.allKeys()).isEmpty();
        assertThat(cfg.keysByProfile()).isEmpty();
    }

    @Test
    void emptyFactoryProducesNoKeysWithoutTouchingTheFilesystem() {
        AppConfigReader cfg = AppConfigReader.empty();

        assertThat(cfg.allKeys()).isEmpty();
        assertThat(cfg.keysByProfile()).isEmpty();
    }

    private static Path write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
