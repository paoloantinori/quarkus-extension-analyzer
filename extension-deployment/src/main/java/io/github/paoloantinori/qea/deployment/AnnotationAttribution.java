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
            new AnnotationRule("jakarta.ws.rs", "io.quarkus:quarkus-resteasy-client-jackson")
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
        Set<String> declaredExtensionGas = collectDeclaredExtensionGas(model);

        // Collect which annotation prefixes are present in the bean index.
        Set<String> presentAnnotationPrefixes = new java.util.TreeSet<>();
        for (var rule : RULES) {
            DotName probe = DotName.createSimple(rule.annotationPrefix() + "X"); // probe a likely class
            // The index may not have the exact prefix; check known annotations directly.
            // Jandex getAnnotations returns annotations by exact DotName, not prefix.
            // Instead, check all known annotations in the index for a prefix match.
        }
        // For each annotation known in the index, check if any rule's prefix matches.
        for (var rule : RULES) {
            // Check if any annotation in the index starts with the rule's prefix.
            // Jandex doesn't expose "all annotations by prefix" directly, but we can check common
            // annotation types for each rule's family.
            if (annotationFamilyPresent(beanIndex, rule.annotationPrefix())) {
                presentAnnotationPrefixes.add(rule.annotationPrefix());
            }
        }

        if (presentAnnotationPrefixes.isEmpty()) {
            return report; // no annotation-consumer signal to apply
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

        if (resolvedByGa.isEmpty()) {
            return report;
        }

        List<ExtensionReport> updatedRows = new ArrayList<>();
        for (ExtensionReport r : report.dependencies()) {
            if (r.verdict() == Verdict.SUSPECT && resolvedByGa.containsKey(r.ga())) {
                // Flip to used-bytecode with the annotation-consumer evidence.
                List<String> vocab = new ArrayList<>(r.vocabularyEvidence());
                vocab.add(resolvedByGa.get(r.ga()));
                updatedRows.add(new ExtensionReport(r.ga(), r.quarkusExtension(), Verdict.USED_BYTECODE,
                        r.configInherited(), r.configRoots(), r.configMatchedKeys(), r.configSource(),
                        r.inheritedRoots(), true, r.capabilityEvidence(),
                        "resolved by annotation-consumer signal: " + resolvedByGa.get(r.ga()),
                        r.bytecodeViaTransitiveApi(), r.valueRuleEvidence(), r.sharedReferencedJars(), vocab));
            } else {
                updatedRows.add(r);
            }
        }

        // Recompute the summary.
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
        return false;
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
