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

import io.github.paoloantinori.qea.plugin.report.AnalysisReport;
import io.github.paoloantinori.qea.plugin.report.ExtensionReport;
import io.github.paoloantinori.qea.plugin.report.Verdict;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The annotation-consumer resolution signal: a Jandex index over the app's classes says which
 * annotation families the app uses, and a curated table says which extension processes each
 * family; suspects in that table are credited accordingly.
 *
 * <p>This resolves the false positives exclusive attribution cannot: extensions used via
 * annotations they process (not beans they produce), where the annotation type lives in a shared
 * jar that exclusive attribution correctly refuses to credit. The index authoritatively confirms
 * the app uses the annotation, so the curated mapping is safe (the annotation IS present, and the
 * mapping says which extension processes it). Shared by both execution forms (TASK-28): the
 * extension form passes ArC's bean index and the model-derived declared set; the mojo form passes
 * the bytecode-scan index and the Maven-model-derived declared set.
 *
 * <p>The curated table started small (bench triage, docs/SUSPECT-TRIAGE.md: validation
 * constraints, @Scheduled, JsonWebToken) and grew through TASK-21/22/23 (REST serializers,
 * reactive-driver join, REST/OpenAPI/Qute/Panache/Fault-Tolerance families); see the RULES table
 * below for the current entries. Each entry fires only if the family's probe evidence is present
 * (annotation usage in the index, a declared type, a shipped file, or endpoint return types) AND
 * the target extension is a directly-declared suspect (so it never manufactures a verdict for an
 * undeclared extension).
 */
public final class AnnotationConsumerRules {

    /** Maps an annotation type (or prefix) to the extension GA that processes it. */
    private record AnnotationRule(String annotationPrefix, String extensionGa) {}

    // Exact-FQCN families referenced from MORE than one site (RULES entry + probe + scanner).
    // Single constants so a future edit cannot desynchronize the rule table from the probe: the
    // RegisterRestClient rule was dead on arrival TWICE, once for a missing probe branch and once
    // for a probe name that did not match the real annotation (restclient vs rest.client).
    private static final String SCHEDULED_ANNOTATION = "io.quarkus.scheduler.Scheduled";
    private static final String JSON_WEB_TOKEN_TYPE = "org.eclipse.microprofile.jwt.JsonWebToken";
    private static final String JSON_WEB_TOKEN_GA = "io.quarkus:quarkus-smallrye-jwt";
    private static final String REGISTER_REST_CLIENT_ANNOTATION =
            "org.eclipse.microprofile.rest.client.inject.RegisterRestClient";
    private static final String JAKARTA_WS_RS_PATH = "jakarta.ws.rs.Path";

    private static final List<AnnotationRule> RULES = List.of(
            new AnnotationRule("jakarta.validation.constraints.", "io.quarkus:quarkus-hibernate-validator"),
            new AnnotationRule("jakarta.validation.executable.", "io.quarkus:quarkus-hibernate-validator"),
            new AnnotationRule(SCHEDULED_ANNOTATION, "io.quarkus:quarkus-scheduler"),
            new AnnotationRule(JSON_WEB_TOKEN_TYPE, JSON_WEB_TOKEN_GA),
            new AnnotationRule("jakarta.ws.rs", "io.quarkus:quarkus-resteasy-jackson"),
            // Client serializer credited by @RegisterRestClient, not server @Path (skeptic finding 5:
            // resteasy-jackson and resteasy-client-jackson have NO overlapping capability, so they
            // coexist in a buildable app; crediting the client from server @Path was wrong).
            new AnnotationRule(REGISTER_REST_CLIENT_ANNOTATION, "io.quarkus:quarkus-resteasy-client-jackson"),
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

    private AnnotationConsumerRules() {
    }

    /**
     * Post-processes the report: for each suspect extension whose annotation family appears in the
     * index, flips it to {@code used-bytecode} with an evidence note naming the annotation and the
     * rule. Non-suspect rows and extensions not in the declared set are untouched.
     *
     * @param report the core Analyzer's report (may contain suspect annotation-consumer extensions)
     * @param index a Jandex index over the app's classes (the extension form passes ArC's bean
     *              index; the mojo form passes the index built for the bytecode signal)
     * @param declaredExtensionGas the directly-declared runtime-extension GAs (a rule fires only
     *              for a declared target, so it never manufactures a verdict for an undeclared
     *              extension)
     * @param dbKindValues the {@code quarkus.datasource[.<name>].db-kind} values present in the app
     *                    config (any profile), used by the TASK-23 disambiguation when multiple
     *                    reactive clients are declared: the client matching the configured kind is
     *                    credited, the others stay suspect. Empty set = no explicit db-kind.
     * @param projectRoot the root of the module being analyzed (TASK-24): the FILE: rules probe
     *                   {@code src/main/resources} and {@code target/classes} under THIS root, not
     *                   the process CWD (in a multi-module reactor the CWD is the reactor root, so
     *                   a CWD-relative probe inspected the wrong module's resources). The empty
     *                   path ({@code Path.of("")}) preserves the legacy CWD-relative behavior for
     *                   callers that cannot derive a root.
     */
    public static AnalysisReport apply(AnalysisReport report, IndexView index,
            Set<String> declaredExtensionGas, Set<String> dbKindValues, java.nio.file.Path projectRoot) {
        // Collect which annotation prefixes are present in the index.
        Set<String> presentAnnotationPrefixes = new java.util.TreeSet<>();
        Set<String> distinctPrefixes = new java.util.LinkedHashSet<>();
        for (var rule : RULES) {
            distinctPrefixes.add(rule.annotationPrefix());
        }
        for (String prefix : distinctPrefixes) {
            // Jandex getAnnotations returns annotations by exact DotName, not prefix; probe the
            // known representative types per family once per DISTINCT prefix (code-review finding
            // 9: the shared jakarta.ws.rs prefix was probed 3x and restEndpointsReturningPojos ran
            // twice per augmentation; the probe is deterministic and side-effect-free).
            boolean present = prefix.startsWith("FILE:")
                    ? configFilePresent(prefix, projectRoot)
                    : annotationFamilyPresent(index, prefix);
            if (present) {
                presentAnnotationPrefixes.add(prefix);
            }
        }

        if (presentAnnotationPrefixes.isEmpty()) {
            // No annotation family matched; the reactive-driver join may still resolve.
            Map<String, String> joinOnly = reactiveDriverJoin(report, dbKindValues);
            AnalysisReport out = joinOnly.isEmpty() ? report : flipSuspects(report, joinOnly);
            return annotateNearMisses(out, index, declaredExtensionGas);
        }

        // Build the updated report: flip matching suspects to used-bytecode. The evidence names the
        // PREFIX probed, not every annotation under it (code-review finding 8: when only @Valid
        // matched, the note still claimed jakarta.validation.constraints.*, which the index
        // contradicted; the prefix is the honest statement of what was probed).
        Map<String, String> resolvedByGa = new LinkedHashMap<>();
        for (var rule : RULES) {
            if (presentAnnotationPrefixes.contains(rule.annotationPrefix())
                    && declaredExtensionGas.contains(rule.extensionGa())) {
                resolvedByGa.put(rule.extensionGa(),
                        "annotation-consumer: app uses the " + rule.annotationPrefix()
                                + " family, which is processed by " + rule.extensionGa());
            }
        }

        // TASK-22 reactive-driver join (may add credits even when no annotation rule fired).
        resolvedByGa.putAll(reactiveDriverJoin(report, dbKindValues));

        AnalysisReport credited = resolvedByGa.isEmpty()
                ? report
                : flipSuspects(report, resolvedByGa);
        return annotateNearMisses(credited, index, declaredExtensionGas);
    }

    /**
     * TASK-32: near-miss telemetry. Every shape-blindness bug found so far (Uni&lt;Pojo&gt;,
     * RestResponse&lt;Pojo&gt;, method-level @Path, Instance&lt;JsonWebToken&gt;) was a rule whose model of
     * "how the evidence appears in bytecode" was narrower than reality, and the behavioral suite
     * could not catch it because its fixtures encode the same model. This pass is the runtime
     * detector for that bug class: for a family that did NOT credit, a LOOSE probe (evidence in
     * any declaration shape, at any depth) runs against the index, and when it hits while the
     * strict probe did not, the still-suspect row's note says so. The next Apicurio then
     * self-reports instead of waiting for a bench re-run. Near-miss evidence NEVER credits.
     *
     * <p>Pilot: the JWT type-mention family (recursive type-graph walk). The mechanism (map of
     * GA to diagnostic, appended to the surviving suspect row's note) is family-agnostic; add
     * loose probes per family as shapes are discovered in the wild.
     */
    private static AnalysisReport annotateNearMisses(AnalysisReport report, IndexView index,
            Set<String> declaredExtensionGas) {
        Map<String, String> nearMissByGa = new LinkedHashMap<>();
        if (declaredExtensionGas.contains(JSON_WEB_TOKEN_GA)) {
            boolean strictHit = index.getKnownClasses().stream().anyMatch(ci ->
                    ci.fields().stream().anyMatch(f -> mentionsJwt(f.type()))
                    || ci.methods().stream().anyMatch(m -> mentionsJwt(m.returnType())
                            || m.parameterTypes().stream().anyMatch(AnnotationConsumerRules::mentionsJwt)));
            if (!strictHit && mentionsJwtAnywhere(index)) {
                nearMissByGa.put(JSON_WEB_TOKEN_GA,
                        "near-miss (diagnostic): the app mentions " + JSON_WEB_TOKEN_TYPE
                                + " in a declaration shape the rule does not credit (e.g. nested or"
                                + " wildcard wrapping); if this is real usage the probe needs extending");
            }
        }
        if (nearMissByGa.isEmpty()) {
            return report;
        }
        List<ExtensionReport> annotated = new ArrayList<>();
        for (ExtensionReport r : report.dependencies()) {
            String diagnostic = nearMissByGa.get(r.ga());
            if (diagnostic != null && r.verdict() == Verdict.SUSPECT
                    && (r.note() == null || !r.note().contains("near-miss"))) {
                annotated.add(new ExtensionReport(r.ga(), r.quarkusExtension(), r.verdict(),
                        r.configInherited(), r.configRoots(), r.configMatchedKeys(), r.configSource(),
                        r.inheritedRoots(), r.bytecodeReferenced(), r.capabilityEvidence(),
                        r.note() == null ? diagnostic : r.note() + " | " + diagnostic,
                        r.bytecodeViaTransitiveApi(), r.valueRuleEvidence(), r.sharedReferencedJars(),
                        r.vocabularyEvidence()));
            } else {
                annotated.add(r);
            }
        }
        // Notes only: verdicts and summaries unchanged.
        return new AnalysisReport(report.applicationArtifact(), report.generatedAt(), annotated,
                report.ignoreRecommendations(), report.extensions(), report.plainJars(),
                report.summary());
    }

    /** The loose JWT probe: the exact FQCN anywhere in a declaration's type graph. */
    private static boolean mentionsJwtAnywhere(IndexView index) {
        return index.getKnownClasses().stream().anyMatch(ci ->
                ci.fields().stream().anyMatch(f -> typeGraphMentionsJwt(f.type()))
                || ci.methods().stream().anyMatch(m -> typeGraphMentionsJwt(m.returnType())
                        || m.parameterTypes().stream()
                                .anyMatch(AnnotationConsumerRules::typeGraphMentionsJwt)));
    }

    /** Recursive walk: bare name, parameterized arguments, array component, wildcard bound. */
    private static boolean typeGraphMentionsJwt(org.jboss.jandex.Type type) {
        if (type == null) {
            return false;
        }
        if (type.name().toString().equals(JSON_WEB_TOKEN_TYPE)) {
            return true;
        }
        if (type instanceof org.jboss.jandex.ParameterizedType pt) {
            return pt.arguments().stream().anyMatch(AnnotationConsumerRules::typeGraphMentionsJwt);
        }
        if (type instanceof org.jboss.jandex.ArrayType at) {
            return typeGraphMentionsJwt(at.component());
        }
        if (type instanceof org.jboss.jandex.WildcardType wt && wt.extendsBound() != null) {
            return typeGraphMentionsJwt(wt.extendsBound());
        }
        return false;
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
                // Contract compliance (skeptic finding 6): sharedReferencedJars is SUSPECT-row-only
                // per ExtensionReport's javadoc and must not survive the flip, while
                // vocabularyEvidence carries independent TASK-8 signal output and is preserved.
                // The note field carries THIS pass's evidence.
                updatedRows.add(new ExtensionReport(r.ga(), r.quarkusExtension(), Verdict.USED_BYTECODE,
                        r.configInherited(), r.configRoots(), r.configMatchedKeys(), r.configSource(),
                        r.inheritedRoots(), true, r.capabilityEvidence(),
                        "resolved by annotation-consumer signal: " + credits.get(r.ga()),
                        r.bytecodeViaTransitiveApi(), r.valueRuleEvidence(), List.of(), r.vocabularyEvidence()));
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
     * The FILE: rule probe (TASK-24): whether the config file the prefix names sits in a
     * conventional location under the PROJECT ROOT being analyzed. Evidence is the file on
     * disk, not the index (config-yaml contributes no annotations). Resolved strictly
     * under the passed root, never the process CWD: a CWD-relative probe inspected the reactor
     * root's resources in multi-module builds (the same class of bug as the readAppConfig CWD
     * lookup). Package-visible so the behavioral suite can pin the resolution semantics without
     * depending on the surefire working directory.
     */
    static boolean configFilePresent(String prefix, java.nio.file.Path projectRoot) {
        String fileName = prefix.substring("FILE:".length());
        return java.nio.file.Files.isRegularFile(MavenLayout.resourcesFile(projectRoot, fileName))
                || java.nio.file.Files.isRegularFile(MavenLayout.classesFile(projectRoot, fileName));
    }

    /**
     * Whether the index contains any annotation whose type name starts with the given prefix.
     * Checks a small set of known annotation types per family (cheaper than scanning all
     * annotations). Index-based prefixes only: {@code FILE:} prefixes are evidence-on-disk and
     * dispatch to {@link #configFilePresent(String, java.nio.file.Path)} in {@link #apply}.
     */
    private static boolean annotationFamilyPresent(IndexView index, String prefix) {
        // For each known prefix, probe a few representative annotation types.
        // This avoids a full annotation scan while catching the common cases.
        if (prefix.startsWith("jakarta.validation.constraints")) {
            return index.getAnnotations(DotName.createSimple("jakarta.validation.constraints.NotNull")).stream().findAny().isPresent()
                    || index.getAnnotations(DotName.createSimple("jakarta.validation.constraints.NotBlank")).stream().findAny().isPresent()
                    || index.getAnnotations(DotName.createSimple("jakarta.validation.constraints.NotEmpty")).stream().findAny().isPresent()
                    || index.getAnnotations(DotName.createSimple("jakarta.validation.constraints.Size")).stream().findAny().isPresent()
                    // @Valid is jakarta.validation.Valid (not .constraints.Valid - the original
                    // FQCN never matched, skeptic finding 6)
                    || index.getAnnotations(DotName.createSimple("jakarta.validation.Valid")).stream().findAny().isPresent();
        }
        if (prefix.startsWith("jakarta.validation.executable")) {
            return index.getAnnotations(DotName.createSimple("jakarta.validation.executable.ValidateOnExecution")).stream().findAny().isPresent();
        }
        if (prefix.startsWith(SCHEDULED_ANNOTATION)) {
            return index.getAnnotations(DotName.createSimple(SCHEDULED_ANNOTATION)).stream().findAny().isPresent();
        }
        if (prefix.startsWith(JSON_WEB_TOKEN_TYPE)) {
            // Check for the exact JWT type in any declaration position (code-review finding 5: the
            // original contains() matched user types like com.acme.JsonWebTokenWrapper). The
            // evidence is the declared type in a field/return/parameter position; an @Inject is
            // not required (a producer or a mapper parameter is equally valid usage). The common
            // CDI shape is Instance<JsonWebToken> (found on the Apicurio bench): a parameterized
            // type whose raw name is Instance, so the unwrap checks the type argument too (the
            // same parameterized-type blindness the REST serializer unwrap fixed for Uni<T>).
            return index.getKnownClasses().stream().anyMatch(ci ->
                    ci.fields().stream().anyMatch(f -> mentionsJwt(f.type()))
                    || ci.methods().stream().anyMatch(m -> mentionsJwt(m.returnType())
                            || m.parameterTypes().stream().anyMatch(AnnotationConsumerRules::mentionsJwt)));
        }
        if (prefix.startsWith("jakarta.ws.rs")) {
            return !index.getAnnotations(DotName.createSimple(JAKARTA_WS_RS_PATH)).isEmpty();
        }
        if (prefix.startsWith(REGISTER_REST_CLIENT_ANNOTATION)) {
            // The real FQCN (verified against microprofile-rest-client-api): "rest.client" with
            // the dot. The original probe spelled it "restclient", a phantom name that never
            // matched any index, leaving the rule dead (caught by TASK-25's behavioral suite).
            return !index.getAnnotations(
                    DotName.createSimple(REGISTER_REST_CLIENT_ANNOTATION)).isEmpty();
        }
        if (prefix.startsWith("org.eclipse.microprofile.faulttolerance.")) {
            return !index.getAnnotations(DotName.createSimple("org.eclipse.microprofile.faulttolerance.Fallback")).isEmpty()
                    || !index.getAnnotations(DotName.createSimple("org.eclipse.microprofile.faulttolerance.Retry")).isEmpty()
                    || !index.getAnnotations(DotName.createSimple("org.eclipse.microprofile.faulttolerance.Timeout")).isEmpty()
                    || !index.getAnnotations(DotName.createSimple("org.eclipse.microprofile.faulttolerance.CircuitBreaker")).isEmpty()
                    || !index.getAnnotations(DotName.createSimple("org.eclipse.microprofile.faulttolerance.Asynchronous")).isEmpty();
        }
        if (prefix.startsWith("io.smallrye.faulttolerance.api.")) {
            // Probes verified against smallrye-fault-tolerance-api 6.10.1: the original names
            // (api.Async, api.ApplyProfile) do not exist in any jar, leaving this branch dead;
            // ApplyGuard and ApplyFaultTolerance are the real annotations in that package.
            return !index.getAnnotations(DotName.createSimple("io.smallrye.faulttolerance.api.ApplyGuard")).isEmpty()
                    || !index.getAnnotations(DotName.createSimple("io.smallrye.faulttolerance.api.ApplyFaultTolerance")).isEmpty();
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
            // Unreachable: apply() dispatches FILE: prefixes to configFilePresent before calling
            // here (the loud failure guards against a future call path reintroducing the silent
            // fall-through that left probes dead).
            throw new IllegalStateException("FILE: prefixes must dispatch to configFilePresent");
        }
        if (prefix.startsWith("REST-SERIALIZER:")) {
            // Serialization-only rule (TASK-21, from the ablation bench): a declared REST-serializer
            // extension is load-bearing when the app has @Path resource methods returning POJOs
            // (anything the serializer must convert that is not itself the HTTP machinery). Evidence:
            // the endpoint return types in the index. Not an annotation the extension processes;
            // the serializer's whole value is converting payloads the endpoints produce.
            return restEndpointsReturningPojos(index);
        }
        return false;
    }

    /** REST method annotations whose presence marks a method as an endpoint. */
    private static final List<String> REST_METHOD_ANNOTATIONS = List.of(
            "jakarta.ws.rs.GET", "jakarta.ws.rs.POST", "jakarta.ws.rs.PUT", "jakarta.ws.rs.DELETE",
            "jakarta.ws.rs.PATCH", "jakarta.ws.rs.HEAD", "jakarta.ws.rs.OPTIONS");

    /**
     * Types the app returning which need NO serializer (HTTP machinery or primitives). FQCNs verified
     * against real jars (skeptic review 2026-08-16): the original table listed three phantom classes
     * that do not exist in any artifact ({@code io.quarkus.rest.runtime.RestResponse} - the real type
     * is {@code org.jboss.resteasy.reactive.RestResponse} in resteasy-reactive-common; {@code
     * io.quarkus.resteasy.runtime.ResteasyResponse} - only a nested wrapper exists; {@code
     * org.jboss.resteasy.reactive.server.SseInOutEvent} - no such class). Phantom entries never
     * matched, so {@code RestResponse<String>} endpoints were over-crediting the serializer.
     */
    private static final Set<String> NON_SERIALIZED_RETURNS = Set.of(
            "void", "boolean", "byte", "char", "short", "int", "long", "float", "double",
            "java.lang.String", "java.lang.Void",
            "jakarta.ws.rs.core.Response", "jakarta.ws.rs.core.StreamingOutput",
            "org.jboss.resteasy.reactive.RestResponse",
            // Qute/HTML returns are not Jackson-serialized either
            "io.quarkus.qute.TemplateInstance");

    /**
     * TASK-21: whether any REST resource (class-level OR method-level {@code @Path}, including
     * interface-shaped resources that Quarkus registers from method annotations alone) has a REST
     * method whose return type needs a serializer. A return type "needs a serializer" when neither
     * the raw type NOR the type argument of a parameterized wrapper ({@code Uni<T>}, {@code
     * CompletionStage<T>}, {@code Optional<T>}) is a non-serialized type: {@code Uni<Void>} and
     * {@code Uni<Response>} collapse to the raw name {@code io.smallrye.mutiny.Uni} which is not in
     * the exclusion set, so they were over-crediting the serializer (skeptic finding 2).
     *
     * <p>Interface resources are included via the method-target branch (skeptic finding 3): Quarkus's
     * own scanner registers resources from method-level {@code @Path} on interfaces, so dropping
     * non-CLASS targets produced {@code familyPresent=true} but {@code pojos()=false} - a false
     * negative on exactly the apps TASK-21 was filed for.
     */
    private static boolean restEndpointsReturningPojos(IndexView index) {
        // Collect the resource classes: @Path on a class targets it directly; @Path on a method
        // targets its declaring class (same resolution the RESTEasy Reactive scanner applies).
        Set<org.jboss.jandex.ClassInfo> resourceClasses = new java.util.LinkedHashSet<>();
        for (var ai : index.getAnnotations(DotName.createSimple(JAKARTA_WS_RS_PATH))) {
            if (ai.target() == null) {
                continue;
            }
            if (ai.target().kind() == org.jboss.jandex.AnnotationTarget.Kind.CLASS) {
                resourceClasses.add(ai.target().asClass());
            } else if (ai.target().kind() == org.jboss.jandex.AnnotationTarget.Kind.METHOD) {
                resourceClasses.add(ai.target().asMethod().declaringClass());
            }
        }
        for (org.jboss.jandex.ClassInfo ci : resourceClasses) {
            // Include inherited REST methods (code-review finding 3: Jandex methods() returns
            // declared methods only, so @Path on a subclass with endpoint methods in a base
            // class would be a false negative). Walk the class + its superclass chain +
            // interfaces, bounded by what the index knows (unknown supertypes return null and
            // stop the walk).
            for (org.jboss.jandex.ClassInfo owner : classHierarchy(index, ci)) {
                for (var m : owner.methods()) {
                    boolean isRestMethod = m.annotations().stream().anyMatch(a ->
                            REST_METHOD_ANNOTATIONS.contains(a.name().toString()));
                    if (!isRestMethod) {
                        continue;
                    }
                    if (returnTypeNeedsSerializer(m.returnType())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The class plus every supertype the index knows (superclass chain and direct interfaces,
     * transitively). Stops at classes the index does not contain (framework types outside the app
     * have no REST-method evidence anyway: annotations on framework base classes are not the app's
     * endpoints).
     */
    private static Set<org.jboss.jandex.ClassInfo> classHierarchy(IndexView index,
            org.jboss.jandex.ClassInfo start) {
        Set<org.jboss.jandex.ClassInfo> seen = new java.util.LinkedHashSet<>();
        Deque<org.jboss.jandex.ClassInfo> queue = new java.util.ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            org.jboss.jandex.ClassInfo ci = queue.poll();
            if (!seen.add(ci)) {
                continue;
            }
            if (ci.superName() != null) {
                org.jboss.jandex.ClassInfo sup = index.getClassByName(ci.superName());
                if (sup != null) {
                    queue.add(sup);
                }
            }
            for (DotName itf : ci.interfaceNames()) {
                org.jboss.jandex.ClassInfo iface = index.getClassByName(itf);
                if (iface != null) {
                    queue.add(iface);
                }
            }
        }
        return seen;
    }

    /**
     * Whether a REST-method return type needs a serializer: the raw type must not be in {@link
     * #NON_SERIALIZED_RETURNS} AND, when it is a parameterized async/container wrapper, its type
     * argument must not be either ({@code Uni<Void>} and {@code Uni<Response>} need no serializer;
     * {@code Uni<Pojo>} does). Jandex {@code ParameterizedType.name()} returns the raw name (it does
     * not override {@code name()}), which is why the unwrapping must be explicit.
     *
     * <p>The response-machinery exclusion ({@code RestResponse}, {@code Response}) is checked by raw
     * name BEFORE the wrapper unwrap AND inside it ({@code RestResponse<Pojo>} has a POJO payload
     * even though the raw name is the HTTP machinery; {@code Uni<RestResponse<Pojo>>} doubly so),
     * because the {@code NON_SERIALIZED_RETURNS} set alone cannot discriminate parameterized uses of
     * a machinery type.
     */
    private static boolean returnTypeNeedsSerializer(org.jboss.jandex.Type type) {
        // Unwrap one level of async/container wrapper to inspect the payload type.
        if (type instanceof org.jboss.jandex.ParameterizedType pt) {
            String raw = pt.name().toString();
            if (raw.equals("io.smallrye.mutiny.Uni")
                    || raw.equals("io.smallrye.mutiny.Multi")
                    || raw.equals("java.util.concurrent.CompletionStage")
                    || raw.equals("java.util.Optional")) {
                var args = pt.arguments();
                if (!args.isEmpty()) {
                    return returnTypeNeedsSerializer(args.get(0));
                }
                return true; // raw Uni/Multi/CompletionStage with no argument: assume a payload
            }
            // RestResponse<Pojo> and Uni<RestResponse<Pojo>>: the HTTP-machinery wrapper has a
            // POJO payload, so the serializer IS needed despite the machinery raw name.
            if (raw.equals("org.jboss.resteasy.reactive.RestResponse")) {
                var args = pt.arguments();
                if (!args.isEmpty()) {
                    return returnTypeNeedsSerializer(args.get(0));
                }
            }
            // List<Foo>, Set<Foo> etc.: the raw container name is not excluded, so a POJO
            // element type means the serializer fires (the conservative direction).
        }
        return !NON_SERIALIZED_RETURNS.contains(type.name().toString());
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
     * ("The datasource must be configured for Hibernate Reactive") - Quarkus refuses to guess. So a
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
        // remaining suspect is the LEFTOVER after its siblings were credited elsewhere - crediting it
        // would be wrong (two clients + db-kind=postgresql leaves mysql as the leftover, and mysql
        // is dead weight). In the multi-declared case only the db-kind match decides.
        if (reactiveDeclared.size() == 1 && reactiveSuspects.size() == 1) {
            String ga = reactiveSuspects.get(0).ga();
            credits.put(ga, "reactive-driver: hibernate-reactive is used and requires exactly this "
                    + "reactive SQL client (ablation-verified: removal fails the persistence-unit build)");
            return credits;
        }
        if (reactiveDeclared.size() > 1 && !dbKindValues.isEmpty()) {
            // Two passes so the evidence tail can distinguish the single-match case (the other
            // declared clients really are dead weight) from a multi-datasource app where several
            // clients are each selected by their own db-kind.
            Map<String, String> matchedKindByGa = new LinkedHashMap<>();
            for (ExtensionReport r : reactiveSuspects) {
                List<String> families = reactiveFamiliesOf(r.ga());
                String matched = families.stream()
                        .filter(f -> dbKindValues.stream().anyMatch(k -> k.equalsIgnoreCase(f)))
                        .findFirst().orElse(null);
                if (matched != null) {
                    matchedKindByGa.put(r.ga(), matched);
                }
            }
            for (var e : matchedKindByGa.entrySet()) {
                credits.put(e.getKey(), "reactive-driver: hibernate-reactive is used and db-kind="
                        + e.getValue() + " selects exactly this reactive SQL client"
                        + (matchedKindByGa.size() == 1
                                ? "; the other declared reactive clients are removable dead weight"
                                : "; the other declared kinds select the other declared clients"));
            }
        }
        return credits;
    }

    /**
     * The {@code quarkus.datasource[.<name>].db-kind} values present in an app config (any
     * profile), the input {@link #apply} uses for the multi-reactive-client disambiguation.
     * Shared by both shells so the extraction lives once.
     */
    public static Set<String> dbKindValues(io.github.paoloantinori.qea.plugin.config.AppConfigReader appConfig) {
        Set<String> values = new java.util.TreeSet<>();
        for (var e : appConfig.valuesByKey().entrySet()) {
            if (e.getKey().endsWith(".db-kind")) {
                values.addAll(e.getValue());
            }
        }
        return values;
    }

    /**
     * Whether a declared type IS the exact JWT type or wraps it as a single type argument
     * ({@code Instance<JsonWebToken>}, the CDI shape the Apicurio bench uses). Deeper nesting
     * ({@code Provider<Instance<JsonWebToken>>}) does not occur in real code and stays unflagged.
     */
    private static boolean mentionsJwt(org.jboss.jandex.Type type) {
        if (type.name().toString().equals(JSON_WEB_TOKEN_TYPE)) {
            return true;
        }
        if (type instanceof org.jboss.jandex.ParameterizedType pt && !pt.arguments().isEmpty()) {
            return pt.arguments().stream().anyMatch(a -> a.name().toString().equals(JSON_WEB_TOKEN_TYPE));
        }
        return false;
    }

    /**
     * The db-kind families a reactive client serves ({@code postgresql} for the pg client; the
     * mysql client serves BOTH {@code mysql} and {@code mariadb}: no separate mariadb reactive
     * artifact exists, per the Quarkus BOM and the still-open dedicated-extension request
     * quarkusio/quarkus#55695), or an empty list for an unknown artifact. Ordered so the
     * evidence names a deterministic kind when both are configured. Keep in sync with the
     * reactive family in value-rules.txt (same module, other signal).
     */
    private static List<String> reactiveFamiliesOf(String ga) {
        String artifact = ga.substring(ga.lastIndexOf(':') + 1);
        if (artifact.equals("quarkus-reactive-pg-client")) {
            return List.of("postgresql");
        }
        if (artifact.equals("quarkus-reactive-mysql-client")) {
            return List.of("mysql", "mariadb");
        }
        if (artifact.equals("quarkus-reactive-mssql-client")) {
            return List.of("mssql");
        }
        if (artifact.equals("quarkus-reactive-db2-client")) {
            return List.of("db2");
        }
        if (artifact.equals("quarkus-reactive-oracle-client")) {
            return List.of("oracle");
        }
        return List.of();
    }
}
