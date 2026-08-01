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

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Which of {@link ConfigRootProbe}'s sources (or root inheritance) contributed a config root credited
 * to an extension in the report. A stable enum instead of the letter codes ("BCD") used internally by
 * {@link ConfigRootProbe.Probe#sourcesOf}, since this is part of the JSON report's public contract.
 * Serializes to its kebab-case {@link #label()}, matching {@code Verdict}'s convention.
 */
public enum ConfigRootSource {
    /** {@code META-INF/quarkus-extension.yaml}, key {@code metadata.config}. */
    EXTENSION_YAML("extension-yaml"),
    /** {@code META-INF/quarkus-config-doc/quarkus-config-model.json}. */
    CONFIG_MODEL_JSON("config-model-json"),
    /** {@code @ConfigMapping(prefix=)} / {@code @ConfigRoot} via Jandex. */
    ANNOTATIONS("annotations"),
    /** Root of a non-ubiquitous extension dependency, via {@link RootInheritance}. */
    INHERITED("inherited");

    private final String label;

    ConfigRootSource(String label) {
        this.label = label;
    }

    @JsonValue
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
