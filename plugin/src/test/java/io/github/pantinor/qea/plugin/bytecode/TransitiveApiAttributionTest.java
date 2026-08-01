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
package io.github.pantinor.qea.plugin.bytecode;

import io.quarkus.maven.dependency.Dependency;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure graph-only tests for {@link TransitiveApiAttribution#attribute}, covering two of the plan's four
 * synthetic model cases (TASK-5 backlog item 4); the other two (whether a referenced/not-referenced
 * exclusive jar actually promotes an extension to used-bytecode) are covered by {@code
 * AnalyzerTest#transitiveApiEvidenceByGa...}, which exercises the sibling pure function this class feeds.
 * A fifth case, {@link #traversesThroughANestedNonDeclaredExtensionAndAttributesItsPlainJarToTheDeclaredAncestor},
 * is a regression test for the apicurio-registry bench diagnosis (TASK-5 follow-up): the declared-vs-nested
 * extension bug documented on {@link TransitiveApiAttribution}'s class javadoc.
 *
 * <p>Built with real {@link ResolvedDependency}/{@link Dependency} instances (via {@link
 * ResolvedDependencyBuilder} and {@link Dependency#of}) rather than a hand-rolled fake, so the test
 * exercises the exact API surface {@link TransitiveApiAttribution} traverses in production.
 */
class TransitiveApiAttributionTest {

    private static ResolvedDependencyBuilder dependency(String groupId, String artifactId) {
        return ResolvedDependencyBuilder.newInstance().setGroupId(groupId).setArtifactId(artifactId)
                .setVersion("1.0");
    }

    /**
     * Case: a jar reachable from exactly one extension's subtree, and not directly declared by the
     * project, is attributed to that extension -- the exclusivity rule's happy path.
     */
    @Test
    void attributesJarReachableFromExactlyOneExtensionAndNotDirectlyDeclared() {
        ResolvedDependency exclusive = dependency("io.lib", "lib-exclusive-a").build();
        ResolvedDependency extA = dependency("io.ext", "ext-a").setRuntimeExtensionArtifact()
                .setDirectDependencies(List.of(Dependency.of("io.lib", "lib-exclusive-a"))).build();

        TransitiveApiAttribution.Result result = TransitiveApiAttribution.attribute(List.of(extA),
                byGa(extA, exclusive), Set.of("io.ext:ext-a"));

        assertThat(result.exclusiveByExtension().get("io.ext:ext-a")).containsExactly("io.lib:lib-exclusive-a");
    }

    /**
     * Case: a jar reachable from two different extensions' subtrees is shared, not exclusive, and must
     * not be attributed to either -- ambiguity must not manufacture a used verdict (plan item 1).
     *
     * <p>TASK-11: also verifies the raw reachability data {@link TransitiveApiAttribution.Result} exposes
     * alongside the exclusivity decision -- both extensions' subtrees still list the shared jar as
     * reachable, and {@code extensionsReachingJar} names both owners -- since that is exactly the data
     * {@code Analyzer}'s shared-referenced-jars hint reuses instead of recomputing the BFS.
     */
    @Test
    void doesNotAttributeAJarReachableFromTwoExtensions() {
        ResolvedDependency shared = dependency("io.lib", "lib-shared").build();
        ResolvedDependency extA = dependency("io.ext", "ext-a").setRuntimeExtensionArtifact()
                .setDirectDependencies(List.of(Dependency.of("io.lib", "lib-shared"))).build();
        ResolvedDependency extB = dependency("io.ext", "ext-b").setRuntimeExtensionArtifact()
                .setDirectDependencies(List.of(Dependency.of("io.lib", "lib-shared"))).build();

        TransitiveApiAttribution.Result result = TransitiveApiAttribution.attribute(List.of(extA, extB),
                byGa(extA, extB, shared), Set.of("io.ext:ext-a", "io.ext:ext-b"));

        assertThat(result.exclusiveByExtension()).doesNotContainKey("io.ext:ext-a");
        assertThat(result.exclusiveByExtension()).doesNotContainKey("io.ext:ext-b");
        assertThat(result.reachableByExtension().get("io.ext:ext-a")).containsExactly("io.lib:lib-shared");
        assertThat(result.reachableByExtension().get("io.ext:ext-b")).containsExactly("io.lib:lib-shared");
        assertThat(result.extensionsReachingJar().get("io.lib:lib-shared"))
                .containsExactly("io.ext:ext-a", "io.ext:ext-b");
    }

    /**
     * Case: a jar exclusively reachable from one extension but also directly declared by the project
     * itself is never attributed: it already has its own, unrelated plain-jar bytecode signal.
     */
    @Test
    void doesNotAttributeAnExclusiveJarThatIsAlsoDirectlyDeclared() {
        ResolvedDependency directAndExclusive = dependency("io.lib", "lib-direct").setDirect(true).build();
        ResolvedDependency extA = dependency("io.ext", "ext-a").setRuntimeExtensionArtifact()
                .setDirectDependencies(List.of(Dependency.of("io.lib", "lib-direct"))).build();

        TransitiveApiAttribution.Result result = TransitiveApiAttribution.attribute(List.of(extA),
                byGa(extA, directAndExclusive), Set.of("io.ext:ext-a"));

        assertThat(result.exclusiveByExtension()).doesNotContainKey("io.ext:ext-a");
    }

    /**
     * The BFS must not cross into another declared extension's subtree: that extension has its own
     * signal, and a jar only reachable through it should not be attributed to the extension walking past
     * it.
     */
    @Test
    void stopsTraversalAtAnotherDeclaredExtensionAndDoesNotAttributeWhatLiesBeyondIt() {
        ResolvedDependency beyondB = dependency("io.lib", "lib-beyond-b").build();
        ResolvedDependency extB = dependency("io.ext", "ext-b").setRuntimeExtensionArtifact()
                .setDirectDependencies(List.of(Dependency.of("io.lib", "lib-beyond-b"))).build();
        ResolvedDependency extA = dependency("io.ext", "ext-a").setRuntimeExtensionArtifact()
                .setDirectDependencies(List.of(Dependency.of("io.ext", "ext-b"))).build();

        TransitiveApiAttribution.Result result = TransitiveApiAttribution.attribute(List.of(extA, extB),
                byGa(extA, extB, beyondB), Set.of("io.ext:ext-a", "io.ext:ext-b"));

        assertThat(result.exclusiveByExtension()).doesNotContainKey("io.ext:ext-a");
        assertThat(result.exclusiveByExtension().get("io.ext:ext-b")).containsExactly("io.lib:lib-beyond-b");
    }

    /**
     * Regression test for the apicurio-registry bench finding: {@code quarkus-kubernetes-client} pulls in
     * an internal, never-directly-declared extension ({@code quarkus-kubernetes-client-internal} in the
     * real bench) that itself depends on the exact jar
     * ({@code io.fabric8:kubernetes-client-api}) the project's bytecode references. Only declared
     * extensions are roots and owners, so the BFS must traverse through the nested extension -- crediting
     * its plain-jar dependency to the declared ancestor -- while never attributing the nested extension's
     * own artifact (it is an extension, not a plain jar, and never appears as a candidate).
     */
    @Test
    void traversesThroughANestedNonDeclaredExtensionAndAttributesItsPlainJarToTheDeclaredAncestor() {
        ResolvedDependency plainJarX = dependency("io.lib", "lib-x").build();
        ResolvedDependency nestedExtensionB = dependency("io.ext", "ext-b-internal").setRuntimeExtensionArtifact()
                .setDirectDependencies(List.of(Dependency.of("io.lib", "lib-x"))).build();
        ResolvedDependency declaredExtensionA = dependency("io.ext", "ext-a").setRuntimeExtensionArtifact()
                .setDirect(true).setDirectDependencies(List.of(Dependency.of("io.ext", "ext-b-internal"))).build();

        // allExtensionGas includes the nested extension so the BFS recognizes and traverses through it;
        // only declaredExtensionA is passed as a root/owner (it alone has a report row).
        TransitiveApiAttribution.Result result = TransitiveApiAttribution.attribute(List.of(declaredExtensionA),
                byGa(declaredExtensionA, nestedExtensionB, plainJarX),
                Set.of("io.ext:ext-a", "io.ext:ext-b-internal"));

        assertThat(result.exclusiveByExtension().get("io.ext:ext-a")).containsExactly("io.lib:lib-x");
        assertThat(result.exclusiveByExtension()).doesNotContainKey("io.ext:ext-b-internal");
    }

    private static Map<String, ResolvedDependency> byGa(ResolvedDependency... deps) {
        Map<String, ResolvedDependency> map = new HashMap<>();
        for (ResolvedDependency d : deps) {
            map.put(d.getGroupId() + ":" + d.getArtifactId(), d);
        }
        return map;
    }
}
