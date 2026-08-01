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
package io.github.pantinor.qea.plugin;

import io.github.pantinor.qea.plugin.bytecode.BytecodeUsage;
import io.github.pantinor.qea.plugin.bytecode.TransitiveApiAttribution;
import io.github.pantinor.qea.plugin.capability.CapabilityJoin;
import io.github.pantinor.qea.plugin.config.AppConfigReader;
import io.github.pantinor.qea.plugin.configroot.ConfigRootProbe;
import io.github.pantinor.qea.plugin.configroot.ConfigRootSource;
import io.github.pantinor.qea.plugin.configroot.RootAttributor;
import io.github.pantinor.qea.plugin.configroot.RootInheritance;
import io.github.pantinor.qea.plugin.model.ExtensionNode;
import io.github.pantinor.qea.plugin.report.AnalysisReport;
import io.github.pantinor.qea.plugin.report.ExtensionReport;
import io.github.pantinor.qea.plugin.report.IgnoreRecommendation;
import io.github.pantinor.qea.plugin.report.Verdict;
import io.github.pantinor.qea.plugin.util.PathUtils;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ExtensionCapabilities;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependency;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates the three-signal classification described in docs/DESIGN.md over a resolved {@link
 * ApplicationModel}, producing one {@link ExtensionReport} row per directly-declared dependency
 * (Quarkus extension or plain jar).
 */
public final class Analyzer {

    private final ExecutorService executor;
    private final Consumer<String> debugAttributionLog;

    /**
     * @param debugAttributionLog TASK-5 bench diagnostics ({@code -Dqea.debugAttribution=true}): when
     *                            non-{@code null}, receives the {@link
     *                            io.github.pantinor.qea.plugin.bytecode.TransitiveApiAttribution}
     *                            subtree/exclusivity trace, the per-candidate-jar referenced/not
     *                            decisions from {@link #transitiveApiEvidenceByGa}, and any {@code
     *                            allDepsByGa} classifier collisions. {@code null} disables the trace
     *                            entirely, at no cost beyond the extra {@code null} checks; callers that
     *                            don't want it pass {@code null} explicitly rather than relying on an
     *                            overload.
     */
    public Analyzer(ExecutorService executor, Consumer<String> debugAttributionLog) {
        this.executor = executor;
        this.debugAttributionLog = debugAttributionLog;
    }

    public AnalysisReport analyze(ApplicationModel model, List<Path> classesDirs, AppConfigReader appConfig)
            throws IOException {
        List<ResolvedDependency> allExtensions = new ArrayList<>();
        for (ResolvedDependency d : model.getDependencies()) {
            if (d.isRuntimeExtensionArtifact()) {
                allExtensions.add(d);
            }
        }
        Set<String> knownExtensionGas = new HashSet<>();
        for (ResolvedDependency d : allExtensions) {
            knownExtensionGas.add(ga(d));
        }
        Map<String, ResolvedDependency> allDepsByGa = allDepsByGa(model.getDependencies(), debugAttributionLog);
        // TASK-5: attribution roots/owners are directly-declared extensions only (report rows); a
        // transitively-pulled-in extension (e.g. quarkus-kubernetes-client-internal under
        // quarkus-kubernetes-client) is never itself an owner -- see TransitiveApiAttribution's class
        // javadoc for the bug this distinction fixes.
        List<ResolvedDependency> declaredExtensions = new ArrayList<>();
        for (ResolvedDependency d : allExtensions) {
            if (d.isDirect()) {
                declaredExtensions.add(d);
            }
        }

        Function<String, Collection<Path>> deploymentJarLookup = deploymentJarLookup(model);

        // --- signal 1: config roots (probed concurrently per plan item 9) ------------------------
        Map<String, ConfigRootProbe.Probe> probes = probeConcurrently(allExtensions, deploymentJarLookup);

        Map<String, Set<String>> ownRoots = new LinkedHashMap<>();
        Map<String, Set<String>> directExtDeps = new LinkedHashMap<>();
        for (ResolvedDependency d : allExtensions) {
            String gaKey = ga(d);
            ConfigRootProbe.Probe probe = probes.get(gaKey);
            ownRoots.put(gaKey, probe.roots());
            directExtDeps.put(gaKey, extensionDepsOf(d, probe, knownExtensionGas));
        }

        RootInheritance.Result inheritance = RootInheritance.inherit(ownRoots, directExtDeps, allExtensions.size());
        List<RootAttributor.Attribution> attributions = RootAttributor.attribute(ownRoots, appConfig.allKeys());
        Map<String, List<String>> keysWonByOwner = RootAttributor.byOwner(attributions);
        Map<String, List<String>> inheritedKeysByGa = inheritedKeysByGa(inheritance, keysWonByOwner);

        // --- signal 2: bytecode -------------------------------------------------------------------
        // Extension jars were already Jandexed while probing signal 1 (ConfigRootProbe.Probe#containedClasses),
        // so no second jar scan is needed here for them; only plain jars still need one, run concurrently below.
        Set<String> jandexReferenced = BytecodeUsage.referencedTypesViaJandex(classesDirs);
        Set<String> asmReferenced = BytecodeUsage.referencedClassesViaAsm(classesDirs);
        Map<String, Boolean> bytecodeUsedByGa = new HashMap<>();
        for (ResolvedDependency d : allExtensions) {
            String gaKey = ga(d);
            bytecodeUsedByGa.put(gaKey, bytecodeUsed(probes.get(gaKey), jandexReferenced));
        }

        // --- signal 2b (TASK-5): transitive non-Quarkus API ----------------------------------------
        // Only the candidate exclusive jars are scanned (lazily), reusing the same scanPlainJar isolation
        // and executor as the directly-declared plain-jar scan below; jars already scanned there are never
        // rescanned (exclusive jars are, by construction, not directly declared, so the two sets are
        // disjoint). An extension already used-bytecode via its own runtime artifact skips transitive
        // attribution entirely -- its exclusive candidates are not even scanned -- so that
        // bytecodeViaTransitiveApi's contract ("this extension's own jar was NOT referenced") is
        // structurally true, not just documented.
        TransitiveApiAttribution.Result attribution = TransitiveApiAttribution.attribute(declaredExtensions,
                allDepsByGa, knownExtensionGas, debugAttributionLog);
        Map<String, Set<String>> exclusiveJarsByExtension = attribution.exclusiveByExtension();
        Map<String, Set<String>> transitiveApiCandidates =
                transitiveApiCandidates(exclusiveJarsByExtension, bytecodeUsedByGa, debugAttributionLog);
        Map<String, PlainJarScan> exclusiveJarScans =
                containedClassesConcurrently(resolvedJarDependencies(transitiveApiCandidates, allDepsByGa));
        Map<String, String> transitiveApiEvidenceByGa = transitiveApiEvidenceByGa(transitiveApiCandidates,
                exclusiveJarScans, jandexReferenced, debugAttributionLog);
        for (String gaKey : transitiveApiEvidenceByGa.keySet()) {
            bytecodeUsedByGa.put(gaKey, true);
        }

        // --- signal 2c (TASK-11): shared-referenced-jars evidence hint, suspect rows only -------------
        // Mirror case of the TASK-5 kubernetes-client fix: a jar reachable from 2+ declared extensions is
        // excluded from exclusiveJarsByExtension (attributing it to either would be a guess), but when
        // the project's own compiled classes DO reference it, that is still useful evidence for a human
        // triaging a suspect row -- it must never change the verdict, only decorate it (enforced in
        // classifyExtension, which only attaches this map's entries on its two SUSPECT-producing
        // branches). Reuses attribution.reachableByExtension()/extensionsReachingJar(), the raw BFS data
        // TransitiveApiAttribution already computed; no second traversal. Ubiquitous jars (bench
        // follow-up: reached by most of the project's declared extensions, e.g. jackson-databind/
        // cdi-api/smallrye-config-core) are excluded inside sharedCandidateJars -- they discriminate
        // nothing and would be noise on every suspect row, not a hint.
        Map<String, Set<String>> sharedCandidateJarsByExtension = sharedCandidateJars(attribution.reachableByExtension(),
                attribution.extensionsReachingJar(), declaredExtensions.size());
        Map<String, PlainJarScan> sharedJarScans =
                containedClassesConcurrently(resolvedJarDependencies(sharedCandidateJarsByExtension, allDepsByGa));
        Set<String> referencedSharedJarGas = referencedJarGas(sharedJarScans, jandexReferenced);
        Map<String, List<ExtensionReport.SharedReferencedJar>> sharedReferencedJarsHintByGa = sharedReferencedJarsHint(
                sharedCandidateJarsByExtension, attribution.extensionsReachingJar(), referencedSharedJarGas);

        // --- signal 3: capabilities + hard extension dependencies, seeded by signals 1 and 2 ------
        Map<String, ExtensionNode> nodes = buildExtensionNodes(model, allExtensions, directExtDeps);
        Set<String> usedByConfigOrBytecode = new HashSet<>();
        for (ResolvedDependency d : allExtensions) {
            String gaKey = ga(d);
            if (!keysWonByOwner.getOrDefault(gaKey, List.of()).isEmpty()
                    || inheritedKeysByGa.containsKey(gaKey)
                    || bytecodeUsedByGa.getOrDefault(gaKey, false)) {
                usedByConfigOrBytecode.add(gaKey);
            }
        }
        Map<String, CapabilityJoin.Edge> capabilityNewlyUsed = CapabilityJoin.join(nodes, usedByConfigOrBytecode);

        // --- plain-jar bytecode scans (signal 2 for non-extension dependencies), concurrently too --
        List<ResolvedDependency> directPlainJars = new ArrayList<>();
        for (ResolvedDependency d : model.getDependencies()) {
            if (d.isDirect() && !d.isRuntimeExtensionArtifact()) {
                directPlainJars.add(d);
            }
        }
        Map<String, PlainJarScan> plainJarScans = containedClassesConcurrently(directPlainJars);

        // --- assemble the report for every directly-declared dependency ---------------------------
        List<ExtensionReport> rows = new ArrayList<>();
        for (ResolvedDependency d : model.getDependencies()) {
            if (!d.isDirect()) {
                continue;
            }
            String gaKey = ga(d);
            if (d.isRuntimeExtensionArtifact()) {
                rows.add(classifyExtension(d, probes.get(gaKey), keysWonByOwner, inheritedKeysByGa, inheritance,
                        bytecodeUsedByGa.getOrDefault(gaKey, false), capabilityNewlyUsed,
                        transitiveApiEvidenceByGa.get(gaKey),
                        sharedReferencedJarsHintByGa.getOrDefault(gaKey, List.of())));
            } else {
                rows.add(classifyPlainJar(d, asmReferenced, plainJarScans));
            }
        }
        rows.sort((a, b) -> a.ga().compareTo(b.ga()));

        List<IgnoreRecommendation> ignoreRecommendations = IgnoreRecommendation.of(rows);

        // TASK-10: split the summary by ExtensionReport#quarkusExtension so the extension-level verdict
        // counts (the question this tool exists to answer) are never conflated with plain-jar rows; see
        // AnalysisReport's javadoc and docs/SECOND-BENCH.md for the triage that surfaced the conflation.
        Map<Boolean, List<ExtensionReport>> byExtension = rows.stream()
                .collect(Collectors.partitioningBy(ExtensionReport::quarkusExtension));
        AnalysisReport.Summary extensions = AnalysisReport.Summary.of(byExtension.get(true));
        AnalysisReport.Summary plainJars = AnalysisReport.Summary.of(byExtension.get(false));

        return new AnalysisReport(model.getAppArtifact().toCompactCoords(), Instant.now().toString(), rows,
                ignoreRecommendations, extensions, plainJars, AnalysisReport.Summary.combine(extensions, plainJars));
    }

    private Map<String, ConfigRootProbe.Probe> probeConcurrently(List<ResolvedDependency> extensions,
            Function<String, Collection<Path>> deploymentJarLookup) {
        Map<String, ConfigRootProbe.Probe> probes = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (ResolvedDependency d : extensions) {
            futures.add(CompletableFuture.runAsync(
                    () -> probes.put(ga(d), ConfigRootProbe.probe(paths(d), deploymentJarLookup)), executor));
        }
        futures.forEach(CompletableFuture::join);
        return probes;
    }

    /** One plain jar's contained-class scan, or the failure that prevented it. */
    record PlainJarScan(Set<String> containedClasses, String error) {
        static PlainJarScan ok(Set<String> classes) {
            return new PlainJarScan(classes, null);
        }

        static PlainJarScan failed(String error) {
            return new PlainJarScan(Set.of(), error);
        }
    }

    /**
     * Physically contained classes per direct plain (non-extension) jar, computed concurrently on the
     * same executor {@link #probeConcurrently} uses, so every jar-touching phase of the analysis shares
     * one pool. Extension jars don't need this: {@link ConfigRootProbe.Probe#containedClasses} already
     * has it, collected as a side effect of the source-D Jandex pass.
     *
     * <p>No future here can complete exceptionally: {@link #scanPlainJar} catches its own failures. One
     * corrupt dependency jar therefore cannot abort the scan of the others, structurally, not just by
     * convention.
     */
    private Map<String, PlainJarScan> containedClassesConcurrently(List<ResolvedDependency> plainJars) {
        Map<String, PlainJarScan> result = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (ResolvedDependency d : plainJars) {
            String gaKey = ga(d);
            futures.add(CompletableFuture.runAsync(() -> {
                PlainJarScan scan = scanPlainJar(PathUtils.firstJar(paths(d)));
                if (scan != null) {
                    result.put(gaKey, scan);
                }
            }, executor));
        }
        futures.forEach(CompletableFuture::join);
        return result;
    }

    /**
     * Scans one plain jar for its contained classes. Isolates a corrupt jar or any other I/O failure
     * into the returned {@link PlainJarScan#error()} instead of throwing, so {@link
     * #containedClassesConcurrently}'s per-dependency tasks never fail. Returns {@code null} only for
     * the unremarkable case of no jar to scan at all (distinct from a scan that was attempted and
     * failed).
     */
    static PlainJarScan scanPlainJar(Path jar) {
        if (jar == null) {
            return null;
        }
        try {
            return PlainJarScan.ok(BytecodeUsage.containedClasses(jar));
        } catch (IOException | RuntimeException e) {
            return PlainJarScan.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Function<String, Collection<Path>> deploymentJarLookup(ApplicationModel model) {
        Map<String, ResolvedDependency> deploymentByGa = new HashMap<>();
        for (ResolvedDependency d : model.getDependencies(DependencyFlags.DEPLOYMENT_CP)) {
            deploymentByGa.put(ga(d), d);
        }
        return gav -> {
            String[] parts = gav.split(":");
            if (parts.length < 2) {
                return List.of();
            }
            ResolvedDependency dep = deploymentByGa.get(parts[0] + ":" + parts[1]);
            return dep == null ? List.of() : paths(dep);
        };
    }

    /** Direct dependencies of an extension that are themselves extensions of this application. */
    private static Set<String> extensionDepsOf(ResolvedDependency d, ConfigRootProbe.Probe probe, Set<String> known) {
        Set<String> out = new LinkedHashSet<>();
        Collection<Dependency> directDeps = d.getDirectDependencies();
        if (directDeps != null) {
            for (Dependency dep : directDeps) {
                String key = dep.getGroupId() + ":" + dep.getArtifactId();
                if (known.contains(key)) {
                    out.add(key);
                }
            }
        }
        // The ApplicationModel does not always carry direct dependencies; the yaml descriptor's
        // extension-dependencies list is the documented fallback (M1 finding, kept in M2).
        for (String gaCoords : probe.extensionDependencies) {
            if (known.contains(gaCoords)) {
                out.add(gaCoords);
            }
        }
        return out;
    }

    private Map<String, ExtensionNode> buildExtensionNodes(ApplicationModel model, List<ResolvedDependency> extensions,
            Map<String, Set<String>> directExtDeps) {
        Map<String, ExtensionCapabilities> capsByGa = new HashMap<>();
        for (ExtensionCapabilities c : model.getExtensionCapabilities()) {
            capsByGa.put(c.getExtension(), c);
        }
        Map<String, ExtensionNode> nodes = new LinkedHashMap<>();
        for (ResolvedDependency d : extensions) {
            String gaKey = ga(d);
            ExtensionCapabilities caps = capsByGa.get(gaKey);
            Set<String> provides = caps == null ? Set.of() : new LinkedHashSet<>(caps.getProvidesCapabilities());
            Set<String> requires = caps == null ? Set.of() : new LinkedHashSet<>(caps.getRequiresCapabilities());
            nodes.put(gaKey, new ExtensionNode(gaKey, directExtDeps.getOrDefault(gaKey, Set.of()), provides, requires));
        }
        return nodes;
    }

    /** Set intersection against the containedClasses already collected by {@link ConfigRootProbe}; no I/O. */
    private static boolean bytecodeUsed(ConfigRootProbe.Probe probe, Set<String> jandexReferenced) {
        return probe.containedClasses.stream().anyMatch(jandexReferenced::contains);
    }

    /**
     * {@code groupId:artifactId} -&gt; resolved dependency, for every dependency in the resolved
     * application model. {@link #ga} strips the classifier, so two resolved dependencies can legitimately
     * collide on the same key (a jar and its {@code tests}/{@code sources} classifier variant); both the
     * TASK-5 BFS ({@link TransitiveApiAttribution}) and the {@code isDirect()} exclusivity check key off
     * this map, so silently keeping whichever variant {@code deps} happens to enumerate first could
     * corrupt either. The empty-classifier (primary) jar always wins, deterministically, regardless of
     * encounter order.
     *
     * <p>This is a conservative fix, not a general multi-classifier model: when neither colliding entry
     * has an empty classifier, whichever is encountered first is kept (arbitrarily, but stably across
     * runs since {@code deps}' own iteration order is deterministic) -- there is no documented "correct"
     * answer for a collision between two non-primary classifier variants, and this class does not attempt
     * to invent one.
     */
    static Map<String, ResolvedDependency> allDepsByGa(Collection<ResolvedDependency> deps, Consumer<String> debugLog) {
        Map<String, ResolvedDependency> result = new HashMap<>();
        for (ResolvedDependency d : deps) {
            String key = ga(d);
            ResolvedDependency existing = result.get(key);
            if (existing == null) {
                result.put(key, d);
            } else if (!existing.getClassifier().isEmpty() && d.getClassifier().isEmpty()) {
                if (debugLog != null) {
                    debugLog.accept("[qea-debug] allDepsByGa collision for " + key + ": replacing classified "
                            + "variant " + existing.toGACTVString() + " with primary " + d.toGACTVString());
                }
                result.put(key, d);
            } else if (debugLog != null) {
                debugLog.accept("[qea-debug] allDepsByGa collision for " + key + ": keeping "
                        + existing.toGACTVString() + ", dropping " + d.toGACTVString());
            }
        }
        return result;
    }

    /**
     * Extension GA -&gt; matched keys inherited via {@link RootInheritance}, computed once (rather than
     * on demand per extension, which recomputed it for every directly-declared extension a second time
     * during {@link #classifyExtension}). Absent from the map, not {@code null}, when nothing matched.
     *
     * <p>Deduplicated per GA: when the owning extension has two roots where one is a prefix of the
     * other (e.g. {@code quarkus.datasource.} and {@code quarkus.datasource.h2.}, both owned by
     * quarkus-agroal), a key under the narrower root matches both, and would otherwise be added to the
     * result twice.
     */
    static Map<String, List<String>> inheritedKeysByGa(RootInheritance.Result inheritance,
            Map<String, List<String>> keysWonByOwner) {
        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<String, Set<RootInheritance.InheritedRoot>> entry : inheritance.inherited().entrySet()) {
            Set<String> keys = new LinkedHashSet<>();
            for (RootInheritance.InheritedRoot ir : entry.getValue()) {
                for (String key : keysWonByOwner.getOrDefault(ir.fromGa(), List.of())) {
                    if (key.startsWith(ir.root())) {
                        keys.add(key);
                    }
                }
            }
            if (!keys.isEmpty()) {
                result.put(entry.getKey(), new ArrayList<>(keys));
            }
        }
        return result;
    }

    /**
     * TASK-5: filters {@link TransitiveApiAttribution#attribute}'s exclusive-jar candidates down to only
     * the extensions not already used-bytecode via their own runtime artifact. Extracted as a pure
     * function, like {@link #inheritedKeysByGa}, so the skip decision is unit-testable without a real
     * {@code ApplicationModel} or jar I/O -- and so that {@link #transitiveApiEvidenceByGa} never even
     * sees a candidate for an extension whose own jar already fired, which is what makes {@link
     * ExtensionReport#bytecodeViaTransitiveApi()}'s "own jar was NOT referenced" contract true rather than
     * merely documented.
     */
    static Map<String, Set<String>> transitiveApiCandidates(Map<String, Set<String>> exclusiveJarsByExtension,
            Map<String, Boolean> bytecodeUsedByGa, Consumer<String> debugLog) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : exclusiveJarsByExtension.entrySet()) {
            String gaKey = entry.getKey();
            if (bytecodeUsedByGa.getOrDefault(gaKey, false)) {
                if (debugLog != null) {
                    debugLog.accept("[qea-debug] " + gaKey + " already used-bytecode via its own runtime "
                            + "artifact; skipping transitive-API scan of " + entry.getValue());
                }
                continue;
            }
            result.put(gaKey, entry.getValue());
        }
        return result;
    }

    /**
     * TASK-5: extension GA -&gt; the GA of the one exclusive transitive jar (of {@link
     * TransitiveApiAttribution#attribute}'s candidates for that extension) that {@code jandexReferenced}
     * proves the project's compiled classes actually reference. Extracted as a pure function, like {@link
     * #inheritedKeysByGa}, so the referenced/not-referenced decision is unit-testable without a real
     * {@code ApplicationModel} or jar I/O.
     *
     * <p>Iterates each extension's candidates in their natural (sorted) order and stops at the first
     * match: {@link ExtensionReport#bytecodeViaTransitiveApi()} is a single nullable field, not a list, so
     * when an extension has more than one referenced exclusive jar, only one is surfaced as evidence, on a
     * deterministic tie-break rather than an arbitrary one.
     */
    static Map<String, String> transitiveApiEvidenceByGa(Map<String, Set<String>> exclusiveJarsByExtension,
            Map<String, PlainJarScan> exclusiveJarScans, Set<String> jandexReferenced) {
        return transitiveApiEvidenceByGa(exclusiveJarsByExtension, exclusiveJarScans, jandexReferenced, null);
    }

    /**
     * Same as {@link #transitiveApiEvidenceByGa(Map, Map, Set)}, with an optional diagnostic sink (TASK-5
     * bench follow-up, {@code -Dqea.debugAttribution=true}): when non-{@code null}, {@code debugLog}
     * receives one line per candidate jar tested for every extension that has at least one exclusive
     * candidate, naming the scan outcome (contained-class count, scan error, or "not scanned") and whether
     * it was referenced. Unlike the non-debug path, which stops at the first referenced candidate for
     * performance, the debug path keeps evaluating (without changing which jar is recorded as evidence:
     * still the first match) so every candidate's fate is visible, not just the winning one.
     */
    static Map<String, String> transitiveApiEvidenceByGa(Map<String, Set<String>> exclusiveJarsByExtension,
            Map<String, PlainJarScan> exclusiveJarScans, Set<String> jandexReferenced, Consumer<String> debugLog) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : exclusiveJarsByExtension.entrySet()) {
            String gaKey = entry.getKey();
            for (String jarGa : entry.getValue()) {
                PlainJarScan scan = exclusiveJarScans.get(jarGa);
                boolean referenced = scan != null && scan.error() == null
                        && scan.containedClasses().stream().anyMatch(jandexReferenced::contains);
                if (debugLog != null) {
                    String scanState = scan == null ? "not scanned"
                            : scan.error() != null ? "scan error: " + scan.error()
                            : scan.containedClasses().size() + " contained classes";
                    debugLog.accept("[qea-debug] " + gaKey + " candidate " + jarGa + " (" + scanState + "): "
                            + (referenced ? "REFERENCED -> used-bytecode evidence" : "not referenced"));
                }
                if (referenced) {
                    result.putIfAbsent(gaKey, jarGa);
                    if (debugLog == null) {
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * TASK-11: filters each declared extension's raw reachable-jar subtree ({@link
     * TransitiveApiAttribution.Result#reachableByExtension}) down to the jars reached by 2+ declared
     * extensions -- exactly the ones {@link TransitiveApiAttribution.Result#exclusiveByExtension}
     * necessarily excludes as ambiguous -- while also excluding UBIQUITOUS jars (bench follow-up: the
     * first cut of this signal surfaced {@code jackson-databind}/{@code cdi-api}/{@code
     * smallrye-config-core} on nearly every suspect row, which discriminates nothing and is noise, not a
     * hint). "Ubiquitous" reuses {@link RootInheritance#UBIQUITY_THRESHOLD} -- the same "more than 50% of
     * the universe" cutoff {@link RootInheritance#inherit} already applies to config-root inheritance, not
     * a second, independently-tuned number -- but against {@code totalDeclaredExtensions} rather than
     * {@code RootInheritance}'s whole-resolved-model denominator: {@link
     * TransitiveApiAttribution.Result#extensionsReachingJar} only ever ranges over declared extensions
     * (the BFS roots/owners), so that is the correct universe size for this signal specifically.
     *
     * <p>These are the only candidates the shared-referenced-jars hint considers; an extension absent from
     * the result had no non-ubiquitous shared jars in its subtree at all. Extracted as a pure function,
     * like the TASK-5 helpers above, so the candidate-selection logic is unit-testable without a real
     * {@code ApplicationModel} or jar I/O.
     *
     * @param totalDeclaredExtensions denominator for the ubiquity cutoff: the number of the project's
     *                                directly-declared extensions (the same population {@code
     *                                extensionsReachingJar}'s owner sets are drawn from)
     */
    static Map<String, Set<String>> sharedCandidateJars(Map<String, Set<String>> reachableByExtension,
            Map<String, Set<String>> extensionsReachingJar, int totalDeclaredExtensions) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : reachableByExtension.entrySet()) {
            Set<String> shared = new TreeSet<>();
            for (String jarGa : entry.getValue()) {
                int owners = extensionsReachingJar.getOrDefault(jarGa, Set.of()).size();
                if (owners >= 2 && owners <= totalDeclaredExtensions * RootInheritance.UBIQUITY_THRESHOLD) {
                    shared.add(jarGa);
                }
            }
            if (!shared.isEmpty()) {
                result.put(entry.getKey(), shared);
            }
        }
        return result;
    }

    /**
     * TASK-11: the GAs of every scanned jar whose contained classes the project's compiled classes
     * actually reference, per {@code jandexReferenced} -- the "(b) present in the project's
     * referenced-type set" half of the shared-referenced-jars hint (the "(a) reached by 2+ declared
     * extensions" half is {@link #sharedCandidateJars}). A jar whose scan failed, or that was never
     * scanned at all (absent from {@code scans}), is conservatively treated as not referenced.
     */
    static Set<String> referencedJarGas(Map<String, PlainJarScan> scans, Set<String> jandexReferenced) {
        Set<String> result = new TreeSet<>();
        for (Map.Entry<String, PlainJarScan> entry : scans.entrySet()) {
            PlainJarScan scan = entry.getValue();
            if (scan.error() == null && scan.containedClasses().stream().anyMatch(jandexReferenced::contains)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * TASK-11: declared extension GA -&gt; the {@link ExtensionReport.SharedReferencedJar} hints for its
     * shared, project-referenced jars. Conservative by construction: this only ever narrows {@link
     * #sharedCandidateJars}' output down to the referenced subset, so it can never manufacture a used
     * verdict; per the plan ("the hint informs human triage, never upgrades"), {@link #classifyExtension}
     * is the one place responsible for only attaching an entry to an already-SUSPECT row, never letting it
     * influence the verdict decision itself. Extracted as a pure function, like the other TASK-5/TASK-11
     * helpers, so the hint-assembly logic is unit-testable without a real {@code ApplicationModel} or jar
     * I/O. An extension with no qualifying hint is absent from the result, not mapped to an empty list.
     */
    static Map<String, List<ExtensionReport.SharedReferencedJar>> sharedReferencedJarsHint(
            Map<String, Set<String>> sharedCandidateJarsByExtension, Map<String, Set<String>> extensionsReachingJar,
            Set<String> referencedJarGas) {
        Map<String, List<ExtensionReport.SharedReferencedJar>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : sharedCandidateJarsByExtension.entrySet()) {
            String extensionGa = entry.getKey();
            List<ExtensionReport.SharedReferencedJar> hints = new ArrayList<>();
            for (String jarGa : entry.getValue()) {
                if (!referencedJarGas.contains(jarGa)) {
                    continue;
                }
                Set<String> alsoReachableFrom = new TreeSet<>(extensionsReachingJar.getOrDefault(jarGa, Set.of()));
                alsoReachableFrom.remove(extensionGa);
                hints.add(new ExtensionReport.SharedReferencedJar(jarGa, new ArrayList<>(alsoReachableFrom)));
            }
            if (!hints.isEmpty()) {
                result.put(extensionGa, hints);
            }
        }
        return result;
    }

    static ExtensionReport classifyExtension(ResolvedDependency d, ConfigRootProbe.Probe probe,
            Map<String, List<String>> keysWonByOwner, Map<String, List<String>> inheritedKeysByGa,
            RootInheritance.Result inheritance, boolean bytecodeReferenced,
            Map<String, CapabilityJoin.Edge> capabilityNewlyUsed, String bytecodeViaTransitiveApi,
            List<ExtensionReport.SharedReferencedJar> sharedReferencedJarsHint) {
        String gaKey = ga(d);

        List<String> ownKeys = keysWonByOwner.getOrDefault(gaKey, List.of());
        List<String> inheritedKeys = inheritedKeysByGa.get(gaKey);
        // CapabilityJoin only records GAs newly added on top of the initially-used seed set, so a hit
        // here already implies this extension was not used by config or bytecode.
        CapabilityJoin.Edge capabilityEdge = capabilityNewlyUsed.get(gaKey);

        Verdict verdict;
        boolean inherited = false;
        Set<String> configRoots = new TreeSet<>();
        List<String> configMatchedKeys = List.of();
        Set<ConfigRootSource> configSource = Set.of();
        List<RootInheritance.InheritedRoot> inheritedRoots = List.of();
        String note = null;
        // TASK-11: only ever set on the two SUSPECT-producing branches below -- this is what makes "the
        // hint never upgrades the verdict" a structural property of this method, not just documented.
        List<ExtensionReport.SharedReferencedJar> sharedReferencedJars = List.of();

        if (bytecodeReferenced) {
            verdict = Verdict.USED_BYTECODE;
        } else if (!ownKeys.isEmpty()) {
            verdict = Verdict.USED_CONFIG;
            configRoots.addAll(probe.roots());
            configMatchedKeys = ownKeys;
            configSource = probe.sourcesOf(probe.roots());
        } else if (inheritedKeys != null) {
            verdict = Verdict.USED_CONFIG;
            inherited = true;
            inheritedRoots = new ArrayList<>(inheritance.inherited().get(gaKey));
            inheritedRoots.sort(Comparator.comparing(RootInheritance.InheritedRoot::root)
                    .thenComparing(RootInheritance.InheritedRoot::fromGa));
            for (RootInheritance.InheritedRoot ir : inheritedRoots) {
                configRoots.add(ir.root());
            }
            configMatchedKeys = inheritedKeys;
            configSource = Set.of(ConfigRootSource.INHERITED);
        } else if (capabilityEdge != null) {
            verdict = Verdict.USED_CAPABILITY;
        } else if (!probe.roots().isEmpty()) {
            verdict = Verdict.SUSPECT;
            configRoots.addAll(probe.roots());
            configSource = probe.sourcesOf(probe.roots());
            note = "config roots known, but no application key falls under them";
            sharedReferencedJars = sharedReferencedJarsHint;
        } else {
            verdict = Verdict.SUSPECT;
            note = "no config-root metadata found in the runtime or deployment jar";
            sharedReferencedJars = sharedReferencedJarsHint;
        }

        if (probe.error != null) {
            note = note == null ? probe.error : note + "; " + probe.error;
        }

        List<String> capabilityEvidence = capabilityEdge == null ? List.of() : List.of(evidenceOf(capabilityEdge));

        return new ExtensionReport(gaKey, true, verdict, inherited, configRoots, configMatchedKeys, configSource,
                inheritedRoots, bytecodeReferenced, capabilityEvidence, note, bytecodeViaTransitiveApi,
                sharedReferencedJars);
    }

    private static String evidenceOf(CapabilityJoin.Edge edge) {
        return edge.isDirectExtensionDependency()
                ? "used because " + edge.requiringGa() + " depends on it (direct extension dependency)"
                : "used because it provides capability " + edge.reason() + " required by " + edge.requiringGa();
    }

    private ExtensionReport classifyPlainJar(ResolvedDependency d, Set<String> asmReferenced,
            Map<String, PlainJarScan> plainJarScans) {
        String gaKey = ga(d);
        PlainJarScan scan = plainJarScans.get(gaKey);
        if (scan != null && scan.error() != null) {
            return new ExtensionReport(gaKey, false, Verdict.SUSPECT, false, Set.of(), List.of(), Set.of(), List.of(),
                    false, List.of(), "bytecode scan failed: " + scan.error(), null, List.of());
        }
        Set<String> contained = scan == null ? Set.of() : scan.containedClasses();
        boolean used = contained.stream().anyMatch(asmReferenced::contains);
        Verdict verdict = used ? Verdict.USED_BYTECODE : Verdict.SUSPECT;
        String note = used ? null : "plain jar: no reference found in compiled classes (bytecode signal only)";
        return new ExtensionReport(gaKey, false, verdict, false, Set.of(), List.of(), Set.of(), List.of(), used,
                List.of(), note, null, List.of());
    }

    /**
     * Hand-rolled rather than {@code ResolvedDependency.toGacString()}: this format ({@code
     * groupId:artifactId}, no classifier/version) must match the GA strings produced by {@code
     * ExtensionCapabilities.getExtension()} and the yaml descriptor's {@code extension-dependencies}
     * list, neither of which {@code toGacString()} matches (it appends the classifier).
     */
    private static String ga(ResolvedDependency d) {
        return d.getGroupId() + ":" + d.getArtifactId();
    }

    private static List<Path> paths(ResolvedDependency d) {
        List<Path> out = new ArrayList<>();
        d.getResolvedPaths().forEach(out::add);
        return out;
    }

    /**
     * The union, across every extension, of a per-extension candidate-jar-GA map's values, resolved back
     * to their {@link ResolvedDependency} so {@link #containedClassesConcurrently} can scan each one
     * exactly once. Shared by both {@link TransitiveApiAttribution.Result#exclusiveByExtension} (TASK-5,
     * where a jar GA appears under at most one extension by construction) and {@link #sharedCandidateJars}
     * (TASK-11, where a jar GA can legitimately appear under several extensions) -- either way the
     * {@link LinkedHashSet} union de-duplicates it down to one scan.
     */
    private static List<ResolvedDependency> resolvedJarDependencies(Map<String, Set<String>> jarsByExtension,
            Map<String, ResolvedDependency> allDepsByGa) {
        Set<String> allGas = new LinkedHashSet<>();
        for (Set<String> jarGas : jarsByExtension.values()) {
            allGas.addAll(jarGas);
        }
        List<ResolvedDependency> out = new ArrayList<>();
        for (String jarGa : allGas) {
            ResolvedDependency dep = allDepsByGa.get(jarGa);
            if (dep != null) {
                out.add(dep);
            }
        }
        return out;
    }
}
