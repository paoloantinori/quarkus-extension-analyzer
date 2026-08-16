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
package io.github.paoloantinori.qea.plugin.annotation;

import java.nio.file.Path;

/**
 * The conventional Maven module layout, in one place (TASK-26). Before this, the
 * {@code src/main/resources} and {@code target/classes} idioms lived in four spellings across
 * the annotation rules' FILE: probe, the shell's config lookup, and the classes-dir resolution;
 * nothing forced them to move together. An empty root ({@code Path.of("")}) resolves to the
 * plain relative form, which is exactly the legacy CWD-relative behavior. Public since TASK-28:
 * the extension shell (another module) consumes it too.
 */
public final class MavenLayout {

    private MavenLayout() {
    }

    /** {@code <root>/src/main/resources/<name>}. */
    public static Path resourcesFile(Path root, String name) {
        return root.resolve(Path.of("src", "main", "resources", name));
    }

    /** {@code <root>/target/classes/<name>}. */
    public static Path classesFile(Path root, String name) {
        return root.resolve(Path.of("target", "classes", name));
    }

    /** {@code <root>/target/classes}. */
    public static Path classesDir(Path root) {
        return root.resolve(Path.of("target", "classes"));
    }

    /** {@code <root>/target/test-classes}. */
    public static Path testClassesDir(Path root) {
        return root.resolve(Path.of("target", "test-classes"));
    }

    /** Whether the path IS a main classes dir ({@code .../target/classes}, by name elements). */
    public static boolean isMainClassesDir(Path path) {
        return path.endsWith(Path.of("target", "classes"));
    }
}
