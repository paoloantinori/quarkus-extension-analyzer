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
package io.github.paoloantinori.qea.plugin.bytecode;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-36 diagnostic probe against the REAL quarkus-github-app events module (skipped when the
 * bench clone is absent): does the walker see the org.kohsuke reference that lives only in an
 * annotation class member?
 */
class EventsProbeTest {

    @Test
    void walkerSeesAnnotationMemberReferenceOnRealEventsModule() throws java.io.IOException {
        Path classes = Path.of("/private/tmp/quarkus-github-app/events/target/classes");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(classes),
                "bench clone not present; probe skipped");

        Set<String> referenced = BytecodeUsage.referencedTypesViaJandex(List.of(classes));
        System.out.println("[probe] referenced size=" + referenced.size());
        System.out.println("[probe] kohsuke referenced: "
                + referenced.stream().filter(s -> s.startsWith("org.kohsuke")).toList());

        Path githubApiJar = Path.of(
                "/Users/pantinor/.m2/repository/org/kohsuke/github-api/1.330/github-api-1.330.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(githubApiJar),
                "github-api jar not present; containment probe skipped");
        Set<String> contained = BytecodeUsage.containedClasses(githubApiJar);
        System.out.println("[probe] contained size=" + contained.size());
        System.out.println("[probe] contains GHEventPayload$IssueComment="
                + contained.contains("org.kohsuke.github.GHEventPayload$IssueComment"));

        // The nested-class containment pin: the ASM analyzer returned top-level classes only,
        // so the nested-only reference shape never matched its own jar (TASK-36).
        assertThat(contained).contains("org.kohsuke.github.GHEventPayload$IssueComment");
        assertThat(referenced).anyMatch(s -> s.startsWith("org.kohsuke.github"));
    }
}
