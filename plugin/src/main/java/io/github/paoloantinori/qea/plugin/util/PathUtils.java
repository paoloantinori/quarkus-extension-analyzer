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
package io.github.paoloantinori.qea.plugin.util;

import java.nio.file.Path;
import java.util.Collection;

/** Small shared helpers over {@link Path} collections, used by both jar-probing call sites. */
public final class PathUtils {

    private PathUtils() {
    }

    /** The first {@code .jar} path in {@code paths}, or {@code null} if none is present. */
    public static Path firstJar(Collection<Path> paths) {
        for (Path p : paths) {
            if (p.toString().endsWith(".jar")) {
                return p;
            }
        }
        return null;
    }
}
