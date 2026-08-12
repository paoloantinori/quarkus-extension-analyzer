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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Signal 4 attribution (TASK-8): credits a declared extension when the app's compiled bytecode
 * references a type that appears in EXACTLY ONE declared extension's deployment vocabulary.
 *
 * <p>This is the conservative exclusivity principle from {@code TransitiveApiAttribution} (TASK-5)
 * applied to the deployment-jar vocabulary instead of the transitive plain-jar graph: a type
 * referenced by two or more declared extensions' deployment vocabularies is ambiguous and is never
 * attributed, because crediting either would be a guess. Only a type exclusive to one extension's
 * vocabulary can credit that extension, and only when the app actually references it.
 *
 * <p><b>Noise filter (bench-driven):</b> the raw deployment-jar vocabulary includes every JDK and
 * primitive type the build-step classes touch ({@code java.time.OffsetDateTime}, {@code byte[]},
 * etc.). Those appear in the app's bytecode too and are "exclusive" to one deployment vocabulary
 * only by coincidence, so without filtering they manufacture spurious credits (the rest-fights bench
 * credited {@code quarkus-info} via {@code java.time.OffsetDateTime} and {@code quarkus-rest-jackson}
 * via {@code byte}). Only non-JDK, non-primitive types (the extension's provided API/bean types and
 * the third-party library types it wires) carry signal, so JDK and primitive types are excluded from
 * the vocabulary before exclusivity is evaluated.
 *
 * <p>Extracted as a pure function so the attribution logic is unit-testable without a real
 * {@code ApplicationModel} or jar I/O, following the project's purity-for-testability convention.
 */
public final class VocabularyAttribution {

    private VocabularyAttribution() {
    }

    /**
     * @param vocabByExtension     declared-extension GA -> its deployment-jar referenced-type vocabulary
     * @param appReferencedTypes   the types the app's compiled classes reference (the Jandex referenced set)
     * @return declared-extension GA -> the (sorted) app-referenced types that are exclusive to that
     *         extension's deployment vocabulary. An extension absent from the result had no exclusive,
     *         app-referenced vocabulary type.
     */
    public static Map<String, Set<String>> attribute(Map<String, Set<String>> vocabByExtension,
            Set<String> appReferencedTypes) {
        // For each type, which declared extensions' vocabularies mention it? (owner set)
        Map<String, Set<String>> ownersByType = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : vocabByExtension.entrySet()) {
            String ga = e.getKey();
            for (String type : e.getValue()) {
                if (isNoise(type)) {
                    continue;
                }
                ownersByType.computeIfAbsent(type, t -> new TreeSet<>()).add(ga);
            }
        }
        // An extension is credited for each app-referenced type it owns EXCLUSIVELY (owners.size() == 1).
        Map<String, Set<String>> credited = new LinkedHashMap<>();
        for (String type : appReferencedTypes) {
            if (isNoise(type)) {
                continue;
            }
            Set<String> owners = ownersByType.get(type);
            if (owners == null || owners.size() != 1) {
                continue;
            }
            String owner = owners.iterator().next();
            credited.computeIfAbsent(owner, g -> new TreeSet<>()).add(type);
        }
        return credited;
    }

    /**
     * Whether a type is noise for vocabulary attribution: JDK core packages ({@code java.*},
     * {@code javax.*}, {@code sun.*}, {@code com.sun.*}, {@code org.w3c.*}, {@code org.xml.*},
     * {@code kotlin.*}, {@code org.jetbrains.*}) and primitives/arrays. These appear in every
     * deployment jar and every app, so an "exclusive" overlap is coincidence, not a producer signal.
     *
     * <p>Deliberately does NOT filter {@code jakarta.*}: those are the producer/API types this signal
     * targets (e.g. {@code jakarta.validation.Validator}); sharing of a {@code jakarta.*} type across
     * extensions is genuine signal handled by exclusivity (correctly declining to attribute), not noise.
     */
    static boolean isNoise(String type) {
        if (type == null || type.isEmpty()) {
            return true;
        }
        // Primitives and arrays (Jandex represents arrays with '[' or trailing "[]"); a primitive like
        // "byte" or "int" is also noise.
        if (type.startsWith("[") || type.endsWith("[]") || isPrimitive(type)) {
            return true;
        }
        return type.startsWith("java.")
                || type.startsWith("javax.")
                || type.startsWith("sun.")
                || type.startsWith("com.sun.")
                || type.startsWith("org.w3c.")
                || type.startsWith("org.xml.")
                || type.startsWith("kotlin.")
                || type.startsWith("org.jetbrains.")
                || type.startsWith("org.slf4j.")
                || type.startsWith("org.apache.commons.logging.")
                || type.startsWith("org.jboss.logging.");
    }

    private static boolean isPrimitive(String type) {
        return switch (type) {
            case "boolean", "byte", "char", "short", "int", "long", "float", "double", "void" -> true;
            default -> false;
        };
    }
}
