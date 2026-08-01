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
package io.github.pantinor.qea.plugin.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class IgnoreFragmentsTest {

    private static final IgnoreRecommendation AGROAL = new IgnoreRecommendation("io.quarkus:quarkus-agroal",
            Verdict.USED_CONFIG, "used via an application configuration key under this extension's own config "
            + "root (quarkus.datasource.)");

    @Test
    void mavenDependencyPluginFragmentUsesTheOfficialElementNamesAndBareGaFormat() {
        String xml = IgnoreFragments.mavenDependencyPluginFragment(List.of(AGROAL));

        assertThat(xml).contains("<ignoredUnusedDeclaredDependencies>");
        assertThat(xml).contains("<ignoredUnusedDeclaredDependency>io.quarkus:quarkus-agroal"
                + "</ignoredUnusedDeclaredDependency>");
        assertThat(xml).contains("</ignoredUnusedDeclaredDependencies>");
    }

    @Test
    void mavenDependencyPluginFragmentOnEmptyRecommendationsIsAnEmptyContainer() {
        String xml = IgnoreFragments.mavenDependencyPluginFragment(List.of());

        assertThat(xml).contains("<ignoredUnusedDeclaredDependencies>");
        assertThat(xml).contains("</ignoredUnusedDeclaredDependencies>");
        assertThat(xml).doesNotContain("<ignoredUnusedDeclaredDependency>");
    }

    @Test
    void depCleanFragmentEscapesLiteralDotsAndWildcardsVersionAndScope() {
        String xml = IgnoreFragments.depCleanFragment(List.of(AGROAL));

        assertThat(xml).contains("<ignoreDependencies>");
        assertThat(xml).contains("<ignoreDependency>io\\.quarkus:quarkus-agroal:.*</ignoreDependency>");
        assertThat(xml).contains("</ignoreDependencies>");
    }

    @Test
    void depCleanRegexMatchesTheFullGroupIdArtifactIdVersionScopeStringDepCleanUses() {
        String regex = IgnoreFragments.depCleanRegex("io.quarkus:quarkus-agroal");

        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        assertThat(pattern.matcher("io.quarkus:quarkus-agroal:3.3.2:compile").matches()).isTrue();
        // An unescaped dot would also match this unrelated coordinate; the escaped version must not.
        assertThat(pattern.matcher("ioXquarkus:quarkus-agroal:3.3.2:compile").matches()).isFalse();
    }

    @Test
    void depCleanFragmentOnEmptyRecommendationsIsAnEmptyContainer() {
        String xml = IgnoreFragments.depCleanFragment(List.of());

        assertThat(xml).contains("<ignoreDependencies>");
        assertThat(xml).contains("</ignoreDependencies>");
        assertThat(xml).doesNotContain("<ignoreDependency>");
    }

    @Test
    void xmlSpecialCharactersInAGaAreEscaped() {
        IgnoreRecommendation withSpecials = new IgnoreRecommendation("io.quarkus:quarkus-<a>&\"'", Verdict.USED_CONFIG,
                "irrelevant");

        String xml = IgnoreFragments.mavenDependencyPluginFragment(List.of(withSpecials));

        assertThat(xml).contains("io.quarkus:quarkus-&lt;a&gt;&amp;&quot;&apos;");
        assertThat(xml).doesNotContain("quarkus-<a>&\"'");
    }

    @Test
    void writeFragmentsWritesBothFilesUnderTheGivenDirectory(@TempDir Path dir) throws IOException {
        Path buildDir = dir.resolve("target");

        List<Path> written = IgnoreFragments.writeFragments(List.of(AGROAL), buildDir);

        assertThat(written).containsExactly(
                buildDir.resolve(IgnoreFragments.MAVEN_DEPENDENCY_PLUGIN_FILE_NAME),
                buildDir.resolve(IgnoreFragments.DEPCLEAN_FILE_NAME));
        String mdp = Files.readString(written.get(0), StandardCharsets.UTF_8);
        String depClean = Files.readString(written.get(1), StandardCharsets.UTF_8);
        assertThat(mdp).contains("<ignoredUnusedDeclaredDependency>io.quarkus:quarkus-agroal"
                + "</ignoredUnusedDeclaredDependency>");
        assertThat(depClean).contains("<ignoreDependency>io\\.quarkus:quarkus-agroal:.*</ignoreDependency>");
    }
}
