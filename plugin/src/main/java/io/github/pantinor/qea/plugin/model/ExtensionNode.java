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
package io.github.pantinor.qea.plugin.model;

import java.util.Set;

/**
 * A resolved Quarkus extension, stripped down to what the pure-logic signal classes ({@code
 * configroot.RootInheritance}, {@code capability.CapabilityJoin}) need. Decoupled on purpose from
 * {@code io.quarkus.bootstrap.model.ApplicationModel} / {@code ResolvedDependency} so those classes
 * can be unit tested with plain synthetic instances instead of the real Quarkus bootstrap builders.
 *
 * @param ga                    {@code groupId:artifactId}
 * @param directExtensionDeps   GAs of other resolved extensions this one directly depends on
 * @param providesCapabilities  capabilities this extension provides
 * @param requiresCapabilities  capabilities this extension requires
 */
public record ExtensionNode(
        String ga,
        Set<String> directExtensionDeps,
        Set<String> providesCapabilities,
        Set<String> requiresCapabilities) {
}
