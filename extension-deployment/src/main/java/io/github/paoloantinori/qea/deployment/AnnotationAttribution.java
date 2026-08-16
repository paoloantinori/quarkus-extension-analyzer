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
package io.github.paoloantinori.qea.deployment;

import io.github.paoloantinori.qea.plugin.report.AnalysisReport;
import io.github.paoloantinori.qea.plugin.report.ExtensionReport;
import io.github.paoloantinori.qea.plugin.report.Verdict;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.maven.dependency.ResolvedDependency;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The annotation-consumer resolution signal, unique to the extension form. Uses ArC's bean index
 * (the app's Jandex index) to check whether the app uses annotation families processed by known
 * extensions, and credits those extensions when the core Analyzer left them suspect.
 *
 * <p>This resolves the false positives that the mojo cannot: extensions used via annotations they
 * process (not beans they produce), where the annotation type lives in a shared jar that exclusive
 * attribution correctly refuses to credit. Inside augmentation, the bean index authoritatively
 * confirms the app uses the annotation, so the curated mapping is safe (the annotation IS present,
 * and the mapping says which extension processes it).
 *
 * <p>The curated table is small and validated by the bench triage (docs/SUSPECT-TRIAGE.md):
 * <ul>
 *   <li>{@code jakarta.validation.constraints.*} -> {@code quarkus-hibernate-validator}
 *   <li>{@code io.quarkus.scheduler.Scheduled} -> {@code quarkus-scheduler}
 *   <li>{@code org.eclipse.microprofile.jwt.JsonWebToken} -> {@code quarkus-smallrye-jwt}
 * </ul>
 * Each entry fires only if the annotation type is present in the bean index AND the target extension
 * is a directly-declared suspect (so it never manufactures a verdict for an undeclared extension).
 */
public final class AnnotationAttribution {

    /** Maps an annotation type (or prefix) to the extension GA that processes it. */
    private record AnnotationRule(String annotationPrefix, String extensionGa) {}

    private static final List<AnnotationRule> RULES = List.of(
            new AnnotationRule("jakarta.validation.constraints.", "io.quarkus:quarkus-hibernate-validator"),
            new AnnotationRule("jakarta.validation.executable.", "io.quarkus:quarkus-hibernate-validator"),
            new AnnotationRule("io.quarkus.scheduler.Scheduled", "io.quarkus:quarkus-scheduler"),
            new AnnotationRule("org.eclipse.microprofile.jwt.JsonWebToken", "io.quarkus:quarkus-smallrye-jwt"),
            new AnnotationRule("jakarta.ws.rs", "io.quarkus:quarkus-resteasy-jackson"),
            new AnnotationRule("jakarta.ws.rs", "io.quarkus:quarkus-resteasy-client-jackson"),
            // The modern Quarkus REST artifact (formerly resteasy-reactive) and the legacy plain
            // resteasy: whichever is the declared suspect gets credited by the same @Path evidence.
            new AnnotationRule("jakarta.ws.rs", "io.quarkus:quarkus-rest"),
            new AnnotationRule("jakarta.ws.rs", "io.quarkus:quarkus-resteasy"),
            new AnnotationRule("org.eclipse.microprofile.faulttolerance.", "io.quarkus:quarkus-smallrye-fault-tolerance"),
            new AnnotationRule("io.smallrye.faulttolerance.api.", "io.quarkus:quarkus-smallrye-fault-tolerance"),
            new AnnotationRule("io.quarkus.mongodb.panache.", "io.quarkus:quarkus-mongodb-panache"),
            new AnnotationRule("org.eclipse.microprofile.openapi.annotations.", "io.quarkus:quarkus-smallrye-openapi"),
            new AnnotationRule("io.quarkus.qute.", "io.quarkus:quarkus-rest-qute"),
            new AnnotationRule("FILE:application.yml", "io.quarkus:quarkus-config-yaml"),
            new AnnotationRule("FILE:application.yaml", "io.quarkus:quarkus-config-yaml"),
            // Serialization-only family (TASK-21, ablation-bench evidence): a declared REST serializer
            // is load-bearing when endpoints return POJOs; removing it builds green but breaks every
            // POJO endpoint at runtime (fast-jar left with zero serializer artifacts).
            new AnnotationRule("REST-SERIALIZER:", "io.quarkus:quarkus-rest-jackson"),
            new AnnotationRule("REST-SERIALIZER:", "io.quarkus:quarkus-resteasy-jackson")
    );

    private AnnotationAttribution() {
    }

    /**
     * Post-processes the report: for each suspect extension whose annotation family appears in the
     * bean index, flips it to {@code used-bytecode} with an evidence note naming the annotation and
     * the rule. Non-suspect rows and extensions not in the declared set are untouched.
     *
     * @param report the core Analyzer's report (may contain suspect annotation-consumer extensions)
     * @param beanIndex ArC's Jandex index of the app (authoritative: knows which annotations are used)
     * @param model the resolved ApplicationModel (to check the target GA is a declared extension)
     * @return a new report with annotation-consumer suspects resolved where the bean index confirms use
     */
    public static AnalysisReport apply(AnalysisReport report, IndexView beanIndex, ApplicationModel model) {
        return apply(report, beanIndex, model, Set.of());
    }

    /**
     * @param dbKindValues the {@code quarkus.datasource[.<name>].db-kind} values present in the app
     *                    config (any profile), used by the TASK-23 disambiguation when multiple
     *                    reactive clients are declared: the client matching the configured kind is
     *                    credited, the others stay suspect. Empty set = no explicit db-kind.
     */
    public static AnalysisReport apply(AnalysisReport report, IndexView beanIndex, ApplicationModel model,
            Set<String> dbKindValues) {
        Set<String> declaredExtensionGas = collectDeclaredExtensionGas(model);

        // Collect which annotation prefixes are present in the bean index.
        Set<String> presentAnnotationPrefixes = new java.util.TreeSet<>();
        for (var rule : RULES) {
            // Jandex getAnnotations returns annotations by exact DotName, not prefix; probe the
            // known representative types per family instead.
            if (annotationFamilyPresent(beanIndex, rule.annotationPrefix())) {
                presentAnnotationPrefixes.add(rule.annotationPrefix());
            }
        }

        if (presentAnnotationPrefixes.isEmpty()) {
            // No annotation family matched; the reactive-driver join may still resolve.
            Map<String, String> joinOnly = reactiveDriverJoin(report, dbKindValues);
            if (joinOnly.isEmpty()) {
                return report;
            }
            return flipSuspects(report, joinOnly);
        }

        // Build the updated report: flip matching suspects to used-bytecode.
        Map<String, String> resolvedByGa = new LinkedHashMap<>();
        for (var rule : RULES) {
            if (presentAnnotationPrefixes.contains(rule.annotationPrefix())
                    && declaredExtensionGas.contains(rule.extensionGa())) {
                resolvedByGa.put(rule.extensionGa(),
                        "annotation-consumer: app uses " + rule.annotationPrefix()
                                + "*, which is processed by " + rule.extensionGa());
            }
        }

        // TASK-22 reactive-driver join (may add credits even when no annotation rule fired).
        resolvedByGa.putAll(reactiveDriverJoin(report, dbKindValues));

        if (resolvedByGa.isEmpty()) {
            return report;
        }

        return flipSuspects(report, resolvedByGa);
    }

    /**
     * Flips each suspect row named in {@code credits} to {@code used-bytecode} with the given
     * evidence, recomputes the summaries, and returns the new report. Shared by the annotation-rule
     * path and the reactive-driver join path.
     */
    private static AnalysisReport flipSuspects(AnalysisReport report, Map<String, String> credits) {
        List<ExtensionReport> updatedRows = new ArrayList<>();
        for (ExtensionReport r : report.dependencies()) {
            if (r.verdict() == Verdict.SUSPECT && credits.containsKey(r.ga())) {
                List<String> vocab = new ArrayList<>(r.vocabularyEvidence());
                vocab.add(credits.get(r.ga()));
                updatedRows.add(new ExtensionReport(r.ga(), r.quarkusExtension(), Verdict.USED_BYTECODE,
                        r.configInherited(), r.configRoots(), r.configMatchedKeys(), r.configSource(),
                        r.inheritedRoots(), true, r.capabilityEvidence(),
                        "resolved by annotation-consumer signal: " + credits.get(r.ga()),
                        r.bytecodeViaTransitiveApi(), r.valueRuleEvidence(), r.sharedReferencedJars(), vocab));
            } else {
                updatedRows.add(r);
            }
        }
        Map<Boolean, List<ExtensionReport>> byExtension = updatedRows.stream()
                .collect(java.util.stream.Collectors.partitioningBy(ExtensionReport::quarkusExtension));
        AnalysisReport.Summary extensions = AnalysisReport.Summary.of(byExtension.get(true));
        AnalysisReport.Summary plainJars = AnalysisReport.Summary.of(byExtension.get(false));
        return new AnalysisReport(report.applicationArtifact(), report.generatedAt(), updatedRows,
                report.ignoreRecommendations(), extensions, plainJars,
                AnalysisReport.Summary.combine(extensions, plainJars));
    }

    /**
     * Whether the bean index contains any annotation whose type name starts with the given prefix.
     * Checks a small set of known annotation types per family (cheaper than scanning all annotations).
     */
    private static boolean annotationFamilyPresent(IndexView index, String prefix) {
        // For each known prefix, probe a few representative annotation types.
        // This avoids a full annotation scan while catching the common cases.
        if (prefix.startsWith("jakarta.validation.constraints")) {
            return index.getAnnotations(DotName.createSimple("jakarta.validation.constraints.NotNull")).stream().findAny().isPresent()
                    || index.getAnnotations(DotName.createSimple("jakarta.validation.constraints.NotBlank")).stream().findAny().isPresent()
                    || index.getAnnotations(DotName.createSimple("jakarta.validation.constraints.NotEmpty")).stream().findAny().isPresent()
                    || index.getAnnotations(DotName.createSimple("jakarta.validation.constraints.Size")).stream().findAny().isPresent()
                    || index.getAnnotations(DotName.createSimple("jakarta.validation.Valid")).stream().findAny().isPresent();
        }
        if (prefix.startsWith("jakarta.validation.executable")) {
            return index.getAnnotations(DotName.createSimple("jakarta.validation.executable.ValidateOnExecution")).stream().findAny().isPresent();
        }
        if (prefix.startsWith("io.quarkus.scheduler.Scheduled")) {
            return index.getAnnotations(DotName.createSimple("io.quarkus.scheduler.Scheduled")).stream().findAny().isPresent();
        }
        if (prefix.startsWith("org.eclipse.microprofile.jwt.JsonWebToken")) {
            // Check if any injection point references JsonWebToken (it may be via @Inject, not as annotation)
            return !index.getAnnotations(DotName.createSimple("jakarta.inject.Inject")).isEmpty()
                    && index.getKnownClasses().stream().anyMatch(ci ->
                    ci.fields().stream().anyMatch(f -> f.type().name().toString().contains("JsonWebToken"))
                    || ci.methods().stream().anyMatch(m -> m.returnType().name().toString().contains("JsonWebToken")
                    || m.parameterTypes().stream().anyMatch(p -> p.name().toString().contains("JsonWebToken"))));
        }
        if (prefix.startsWith("jakarta.ws.rs")) {
            return !index.getAnnotations(DotName.createSimple("jakarta.ws.rs.Path")).isEmpty();
        }
        if (prefix.startsWith("org.eclipse.microprofile.faulttolerance.")) {
            return !index.getAnnotations(DotName.createSimple("org.eclipse.microprofile.faulttolerance.Fallback")).isEmpty()
                    || !index.getAnnotations(DotName.createSimple("org.eclipse.microprofile.faulttolerance.Retry")).isEmpty()
                    || !index.getAnnotations(DotName.createSimple("org.eclipse.microprofile.faulttolerance.Timeout")).isEmpty()
                    || !index.getAnnotations(DotName.createSimple("org.eclipse.microprofile.faulttolerance.CircuitBreaker")).isEmpty()
                    || !index.getAnnotations(DotName.createSimple("org.eclipse.microprofile.faulttolerance.Asynchronous")).isEmpty();
        }
        if (prefix.startsWith("io.smallrye.faulttolerance.api.")) {
            return !index.getAnnotations(DotName.createSimple("io.smallrye.faulttolerance.api.Async")).isEmpty()
                    || !index.getAnnotations(DotName.createSimple("io.smallrye.faulttolerance.api.ApplyProfile")).isEmpty();
        }
        if (prefix.startsWith("io.quarkus.mongodb.panache.")) {
            return index.getKnownClasses().stream().anyMatch(ci ->
                    ci.superName() != null && ci.superName().toString().startsWith("io.quarkus.mongodb.panache"));
        }
        if (prefix.startsWith("org.eclipse.microprofile.openapi.annotations.")) {
            return !index.getAnnotations(DotName.createSimple("org.eclipse.microprofile.openapi.annotations.Operation")).isEmpty()
                    || !index.getAnnotations(DotName.createSimple("org.eclipse.microprofile.openapi.annotations.media.Schema")).isEmpty()
                    || !index.getAnnotations(DotName.createSimple("org.eclipse.microprofile.openapi.annotations.tags.Tag")).isEmpty();
        }
        if (prefix.startsWith("io.quarkus.qute.")) {
            // Qute templates: the app declares @CheckedTemplate classes or injects Template/TemplateInstance.
            return !index.getAnnotations(DotName.createSimple("io.quarkus.qute.CheckedTemplate")).isEmpty()
                    || !index.getAnnotations(DotName.createSimple("io.quarkus.qute.Location")).isEmpty()
                    || index.getKnownClasses().stream().anyMatch(ci ->
                    ci.methods().stream().anyMatch(m -> m.returnType().name().toString().equals("io.quarkus.qute.TemplateInstance"))
                    || ci.fields().stream().anyMatch(f -> f.type().name().toString().equals("io.quarkus.qute.Template")));
        }
        if (prefix.startsWith("FILE:")) {
            // Config-file presence rule: the extension that parses the file the app actually ships.
            // Evidence is the file on disk, not the bean index (config-yaml contributes no annotations).
            String fileName = prefix.substring("FILE:".length());
            return java.nio.file.Files.isRegularFile(java.nio.file.Path.of("src", "main", "resources", fileName))
                    || java.nio.file.Files.isRegularFile(java.nio.file.Path.of("target", "classes", fileName));
        }
        if (prefix.startsWith("REST-SERIALIZER:")) {
            // Serialization-only rule (TASK-21, from the ablation bench): a declared REST-serializer
            // extension is load-bearing when the app has @Path resource methods returning POJOs
            // (anything the serializer must convert that is not itself the HTTP machinery). Evidence:
            // the endpoint return types in the bean index. Not an annotation the extension processes;
            // the serializer's whole value is converting payloads the endpoints produce.
            return restEndpointsReturningPojos(index);
        }
        return false;
    }

    /** REST method annotations whose presence marks a method as an endpoint. */
    private static final List<String> REST_METHOD_ANNOTATIONS = List.of(
            "jakarta.ws.rs.GET", "jakarta.ws.rs.POST", "jakarta.ws.rs.PUT", "jakarta.ws.rs.DELETE",
            "jakarta.ws.rs.PATCH", "jakarta.ws.rs.HEAD", "jakarta.ws.rs.OPTIONS");

    /** Types the app returning which need NO serializer (HTTP machinery or primitives). */
    private static final Set<String> NON_SERIALIZED_RETURNS = Set.of(
            "void", "boolean", "byte", "char", "short", "int", "long", "float", "double",
            "java.lang.String", "java.lang.Void",
            "jakarta.ws.rs.core.Response", "jakarta.ws.rs.core.StreamingOutput",
            "io.quarkus.rest.runtime.RestResponse", "io.quarkus.resteasy.runtime.ResteasyResponse",
            // Qute/HTML and ServerSentEvent returns are not Jackson-serialized either
            "io.quarkus.qute.TemplateInstance",
            "org.jboss.resteasy.reactive.server.SseInOutEvent");

    /**
     * TASK-21: whether any {@code @Path}-annotated class has a REST-method whose return type needs a
     * serializer (a POJO: not a primitive, not String/Void, not the HTTP response machinery). This is
     * the evidence that a declared REST-serializer extension is load-bearing: the ablation bench
     * (docs/ABLATION-BENCH.md) showed removing it builds green but leaves the fast-jar with zero
     * serializer artifacts and every POJO endpoint failing at runtime.
     */
    private static boolean restEndpointsReturningPojos(IndexView index) {
        var pathClasses = index.getAnnotations(DotName.createSimple("jakarta.ws.rs.Path"));
        for (var ai : pathClasses) {
            if (ai.target() == null || ai.target().kind() != org.jboss.jandex.AnnotationTarget.Kind.CLASS) {
                continue;
            }
            for (var m : ai.target().asClass().methods()) {
                boolean isRestMethod = m.annotations().stream().anyMatch(a ->
                        REST_METHOD_ANNOTATIONS.contains(a.name().toString()));
                if (!isRestMethod) {
                    continue;
                }
                String ret = m.returnType().name().toString();
                if (!NON_SERIALIZED_RETURNS.contains(ret)) {
                    return true; // a POJO (or collection/Uni of one) needs a serializer
                }
            }
        }
        return false;
    }

    /**
     * TASK-22 + TASK-23 dependency-join rule (ablation-bench evidence): when Hibernate Reactive (or
     * its Panache variant) is declared AND classified used, a declared {@code
     * quarkus-reactive-*-client} suspect is load-bearing: Hibernate Reactive cannot build its
     * persistence unit without a reactive SQL driver (the ablation of quarkus-reactive-pg-client on
     * rest-heroes failed exactly there).
     *
     * <p>Disambiguation when MULTIPLE reactive clients are declared (TASK-23): the empirical bench
     * proved two things. First, two clients with no explicit datasource config make the build FAIL
     * ("The datasource must be configured for Hibernate Reactive") — Quarkus refuses to guess. So a
     * buildable multi-client app necessarily carries an explicit {@code db-kind}, which IS the
     * authority for which client is the driver. When a {@code db-kind} value is present, the client
     * whose family matches it is credited and the others stay suspect (they are genuinely removable
     * dead weight, per the same ablation logic). When multiple clients are declared and NO db-kind
     * is present, all stay suspect: either the app does not build (moot) or the config arrived by a
     * path this join cannot see, and ambiguity must not manufacture a verdict.
     *
     * @param dbKindValues the db-kind values from the app config (any profile), empty when none
     */
    private static Map<String, String> reactiveDriverJoin(AnalysisReport report, Set<String> dbKindValues) {
        Map<String, String> credits = new LinkedHashMap<>();
        boolean hibernateReactiveUsed = report.dependencies().stream().anyMatch(r ->
                r.quarkusExtension() && r.verdict() != Verdict.SUSPECT
                        && (r.ga().equals("io.quarkus:quarkus-hibernate-reactive")
                                || r.ga().equals("io.quarkus:quarkus-hibernate-reactive-panache")));
        if (!hibernateReactiveUsed) {
            return credits;
        }
        List<ExtensionReport> reactiveDeclared = report.dependencies().stream()
                .filter(r -> r.quarkusExtension()
                        && r.ga().startsWith("io.quarkus:quarkus-reactive-") && r.ga().endsWith("-client"))
                .toList();
        List<ExtensionReport> reactiveSuspects = reactiveDeclared.stream()
                .filter(r -> r.verdict() == Verdict.SUSPECT)
                .toList();
        // Single-DECLARED shortcut (not single-suspect): when the app declares exactly one reactive
        // client total, that one is necessarily the driver. When several are declared, a single
        // remaining suspect is the LEFTOVER after its siblings were credited elsewhere — crediting it
        // would be wrong (two clients + db-kind=postgresql leaves mysql as the leftover, and mysql
        // is dead weight). In the multi-declared case only the db-kind match decides.
        if (reactiveDeclared.size() == 1 && reactiveSuspects.size() == 1) {
            String ga = reactiveSuspects.get(0).ga();
            credits.put(ga, "reactive-driver: hibernate-reactive is used and requires exactly this "
                    + "reactive SQL client (ablation-verified: removal fails the persistence-unit build)");
            return credits;
        }
        if (reactiveDeclared.size() > 1 && !dbKindValues.isEmpty()) {
            for (ExtensionReport r : reactiveSuspects) {
                String family = reactiveFamilyOf(r.ga());
                if (family != null && dbKindValues.stream().anyMatch(k -> k.equalsIgnoreCase(family))) {
                    credits.put(r.ga(), "reactive-driver: hibernate-reactive is used and db-kind="
                            + family + " selects exactly this reactive SQL client; the other declared "
                            + "reactive clients are removable dead weight");
                }
            }
        }
        return credits;
    }

    /**
     * The db-kind family a reactive client serves ({@code postgresql} for
     * {@code quarkus-reactive-pg-client}, {@code mysql}/{@code mariadb} for
     * {@code quarkus-reactive-mysql-client}, ...), or {@code null} for an unknown artifact.
     */
    private static String reactiveFamilyOf(String ga) {
        String artifact = ga.substring(ga.lastIndexOf(':') + 1);
        if (artifact.equals("quarkus-reactive-pg-client")) {
            return "postgresql";
        }
        if (artifact.equals("quarkus-reactive-mysql-client")) {
            return "mysql";
        }
        if (artifact.equals("quarkus-reactive-mariadb-client")) {
            return "mariadb";
        }
        if (artifact.equals("quarkus-reactive-mssql-client")) {
            return "mssql";
        }
        if (artifact.equals("quarkus-reactive-db2-client")) {
            return "db2";
        }
        if (artifact.equals("quarkus-reactive-oracle-client")) {
            return "oracle";
        }
        return null;
    }

    private static Set<String> collectDeclaredExtensionGas(ApplicationModel model) {
        Set<String> gas = new java.util.TreeSet<>();
        for (ResolvedDependency d : model.getDependencies()) {
            if (d.isRuntimeExtensionArtifact() && d.isDirect()) {
                gas.add(d.getGroupId() + ":" + d.getArtifactId());
            }
        }
        return gas;
    }
}
