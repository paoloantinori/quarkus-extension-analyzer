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
package io.github.pantinor.qea.plugin.configroot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pantinor.qea.plugin.util.PathUtils;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Recovers per-extension config-root prefixes from resolved runtime (and, per the M2 upgrade, {@code
 * -deployment}) artifacts. Ported from the M1 spike's {@code ConfigRoots} (docs/SPIKE-RESULTS.md),
 * with one deviation locked by the M2 plan: source D ({@code @ConfigMapping}/{@code @ConfigRoot} via
 * Jandex) is now probed against the extension's {@code -deployment} jar too, closing the {@code
 * BUILD_TIME}-only config-root gap the spike left open (a {@code @ConfigRoot} class that lives only in
 * the deployment module has no runtime-visible counterpart).
 *
 * <p>Three candidate sources are probed independently, and their union is exposed, so callers can
 * report per-source coverage:
 *
 * <ul>
 *   <li>B - {@code META-INF/quarkus-extension.yaml}, key {@code metadata.config}</li>
 *   <li>C - {@code META-INF/quarkus-config-doc/quarkus-config-model.json}</li>
 *   <li>D - {@code @ConfigMapping(prefix=)} / {@code @ConfigRoot} via Jandex over the jar</li>
 * </ul>
 *
 * <p>{@code META-INF/quarkus-extension.properties} (M1's "source A") is read too, but only for its
 * {@code deployment-artifact} GAV: M1 confirmed it never carries config-root information across all 51
 * bench extensions, so it does not contribute a fourth root source.
 */
public final class ConfigRootProbe {

    private static final DotName CONFIG_MAPPING = DotName.createSimple("io.smallrye.config.ConfigMapping");
    private static final DotName CONFIG_ROOT = DotName.createSimple("io.quarkus.runtime.annotations.ConfigRoot");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Per-extension probe result: what each source yielded, over the runtime jar and (if any) the deployment jar. */
    public static final class Probe {
        public final Set<String> fromExtensionYaml = new TreeSet<>();
        public final Set<String> fromConfigModelJson = new TreeSet<>();
        public final Set<String> fromAnnotations = new TreeSet<>();
        /** Extension GAs this artifact declares as extension dependencies (yaml metadata). */
        public final Set<String> extensionDependencies = new LinkedHashSet<>();
        /**
         * Every class contained in the probed jar(s), collected as a side effect of the source-D Jandex
         * pass (which already walks every {@code .class} entry). Reused by {@code Analyzer.bytecodeUsed}
         * for the extension bytecode signal instead of re-scanning the jar with a second class analyzer.
         */
        public final Set<String> containedClasses = new TreeSet<>();
        /** {@code groupId:artifactId:version} of the {@code -deployment} artifact, from source A, if present. */
        public String deploymentArtifactGav;
        public String error;

        /**
         * The union of B, C and D rather than a single winner.
         *
         * <p>No source strictly dominates. C and D are derived from the extension's own source code and
         * are authoritative where they exist, but they only cover classes annotated with {@code
         * @ConfigMapping}, so spec-defined prefixes ({@code mp.health.}, {@code mp.jwt.}) only appear in
         * B. B in turn is hand-maintained and can be plain wrong: quarkus-opentelemetry declares {@code
         * quarkus.opentelemetry.} while its real prefix is {@code quarkus.otel.}.
         *
         * <p>Taking the union is safe for a "is this extension used" signal because a spurious root can
         * only produce a false positive if it matches a key that is really owned by someone else, whereas
         * dropping a correct root produces a false "suspect" straight away. Cross-extension over-broad
         * roots (the {@code quarkus-logging-json} case) are handled downstream by {@link RootAttributor},
         * not here.
         */
        public Set<String> roots() {
            Set<String> all = new TreeSet<>();
            all.addAll(fromExtensionYaml);
            all.addAll(fromConfigModelJson);
            all.addAll(fromAnnotations);
            return all;
        }

        /** Which of B/C/D contributed at least one of the given roots. */
        public Set<ConfigRootSource> sourcesOf(Collection<String> roots) {
            Set<ConfigRootSource> sources = EnumSet.noneOf(ConfigRootSource.class);
            if (roots.stream().anyMatch(fromExtensionYaml::contains)) {
                sources.add(ConfigRootSource.EXTENSION_YAML);
            }
            if (roots.stream().anyMatch(fromConfigModelJson::contains)) {
                sources.add(ConfigRootSource.CONFIG_MODEL_JSON);
            }
            if (roots.stream().anyMatch(fromAnnotations::contains)) {
                sources.add(ConfigRootSource.ANNOTATIONS);
            }
            return sources;
        }

        Probe mergedWith(Probe other) {
            Probe merged = new Probe();
            merged.fromExtensionYaml.addAll(fromExtensionYaml);
            merged.fromExtensionYaml.addAll(other.fromExtensionYaml);
            merged.fromConfigModelJson.addAll(fromConfigModelJson);
            merged.fromConfigModelJson.addAll(other.fromConfigModelJson);
            merged.fromAnnotations.addAll(fromAnnotations);
            merged.fromAnnotations.addAll(other.fromAnnotations);
            merged.extensionDependencies.addAll(extensionDependencies);
            merged.extensionDependencies.addAll(other.extensionDependencies);
            merged.containedClasses.addAll(containedClasses);
            merged.containedClasses.addAll(other.containedClasses);
            merged.deploymentArtifactGav = deploymentArtifactGav != null ? deploymentArtifactGav : other.deploymentArtifactGav;
            if (error != null || other.error != null) {
                merged.error = error == null ? other.error : other.error == null ? error : error + "; " + other.error;
            }
            return merged;
        }
    }

    private ConfigRootProbe() {
    }

    /**
     * Probes an extension's runtime jar. If the runtime jar's {@code META-INF/quarkus-extension.properties}
     * (source A) declares a {@code deployment-artifact} GAV, {@code deploymentJarLookup} is asked to
     * resolve it to jar path(s); when it does, the deployment jar is probed too (all sources) and the
     * results are unioned into the returned {@link Probe}. This is the M2 upgrade over the M1 spike,
     * which probed the runtime jar only and left {@code BUILD_TIME}-only config roots undiscoverable.
     *
     * @param deploymentJarLookup resolves a {@code groupId:artifactId:version} to its resolved jar
     *                            path(s), or an empty/absent collection if not resolvable; may be
     *                            {@code null} to skip deployment-jar probing entirely
     */
    public static Probe probe(Collection<Path> runtimePaths, Function<String, Collection<Path>> deploymentJarLookup) {
        Probe runtime = probeOne(runtimePaths);
        if (deploymentJarLookup == null || runtime.deploymentArtifactGav == null) {
            return runtime;
        }
        Collection<Path> deploymentPaths = deploymentJarLookup.apply(runtime.deploymentArtifactGav);
        if (deploymentPaths == null || deploymentPaths.isEmpty()) {
            return runtime;
        }
        Probe deployment = probeOne(deploymentPaths);
        return runtime.mergedWith(deployment);
    }

    private static Probe probeOne(Collection<Path> resolvedPaths) {
        Probe p = new Probe();
        Path jar = PathUtils.firstJar(resolvedPaths);
        if (jar == null) {
            p.error = "no jar in resolved paths " + resolvedPaths;
            return p;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            // Each source is isolated: a malformed/unexpected artifact should degrade that one source,
            // not abort the others. SnakeYAML's YAMLException (probeExtensionYaml) and Jackson's
            // JsonProcessingException (probeConfigModelJson) are both unchecked and would otherwise
            // escape past an IOException-only catch and crash the whole run.
            runSource(p, "properties", () -> probeExtensionProperties(zip, p));
            runSource(p, "yaml", () -> probeExtensionYaml(zip, p));
            runSource(p, "config-model.json", () -> probeConfigModelJson(zip, p));
            runSource(p, "annotations", () -> probeAnnotations(zip, p));
        } catch (IOException e) {
            p.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return p;
    }

    /** A single probe source's body, allowed to throw like the {@code probeXxx} methods it wraps. */
    @FunctionalInterface
    private interface Source {
        void run() throws IOException;
    }

    /** Runs one source, isolating its failure into {@link Probe#error} instead of aborting the others. */
    private static void runSource(Probe p, String label, Source source) {
        try {
            source.run();
        } catch (IOException | RuntimeException e) {
            recordSourceError(p, label, e);
        }
    }

    /** Appends a per-source failure to {@link Probe#error} without discarding earlier ones. */
    private static void recordSourceError(Probe p, String source, Exception e) {
        String msg = source + ": " + e.getClass().getSimpleName() + ": " + e.getMessage();
        p.error = p.error == null ? msg : p.error + "; " + msg;
    }

    /**
     * Source A. Carries no config information (M1 confirmed this across all 51 bench extensions); only
     * its {@code deployment-artifact} GAV is used, to locate the {@code -deployment} jar for the source
     * D upgrade.
     */
    private static void probeExtensionProperties(ZipFile zip, Probe p) throws IOException {
        ZipEntry e = zip.getEntry("META-INF/quarkus-extension.properties");
        if (e == null) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = zip.getInputStream(e)) {
            props.load(in);
        }
        String deploymentArtifact = props.getProperty("deployment-artifact");
        if (deploymentArtifact != null && !deploymentArtifact.isBlank()) {
            p.deploymentArtifactGav = deploymentArtifact.trim();
        }
    }

    /** Source B: {@code metadata.config} is a list of dotted prefixes such as "quarkus.datasource.". */
    @SuppressWarnings("unchecked")
    private static void probeExtensionYaml(ZipFile zip, Probe p) throws IOException {
        ZipEntry e = zip.getEntry("META-INF/quarkus-extension.yaml");
        if (e == null) {
            return;
        }
        Object loaded;
        try (InputStream in = zip.getInputStream(e)) {
            loaded = new Yaml().load(in);
        }
        if (!(loaded instanceof Map)) {
            return;
        }
        Object metadata = ((Map<String, Object>) loaded).get("metadata");
        if (!(metadata instanceof Map)) {
            return;
        }
        Map<String, Object> md = (Map<String, Object>) metadata;
        Object config = md.get("config");
        if (config instanceof List) {
            for (Object o : (List<Object>) config) {
                p.fromExtensionYaml.add(normalize(String.valueOf(o)));
            }
        }
        Object extDeps = md.get("extension-dependencies");
        if (extDeps instanceof List) {
            for (Object o : (List<Object>) extDeps) {
                p.extensionDependencies.add(String.valueOf(o));
            }
        }
    }

    /**
     * Source C. The config-doc model carries an explicit {@code prefix} per config root, so no prefix
     * has to be inferred from individual property paths. Note the granularity is that of the declaring
     * {@code @ConfigMapping}, which is sometimes broader than the extension's real ownership:
     * quarkus-logging-json reports {@code quarkus.log} even though it only owns {@code
     * quarkus.log.console.json.*} ({@link RootAttributor} corrects for this downstream).
     */
    private static void probeConfigModelJson(ZipFile zip, Probe p) throws IOException {
        ZipEntry e = zip.getEntry("META-INF/quarkus-config-doc/quarkus-config-model.json");
        if (e == null) {
            return;
        }
        JsonNode root;
        try (InputStream in = zip.getInputStream(e)) {
            root = JSON.readTree(in);
        }
        JsonNode configRoots = root.get("configRoots");
        if (configRoots == null || !configRoots.isArray()) {
            return;
        }
        for (JsonNode cr : configRoots) {
            JsonNode prefix = cr.get("prefix");
            if (prefix != null && prefix.isTextual() && !prefix.asText().isBlank()) {
                p.fromConfigModelJson.add(normalize(prefix.asText()));
            }
        }
    }

    /**
     * Source D: Jandex the jar and read {@code @ConfigMapping(prefix)} / {@code @ConfigRoot(name)}. The
     * Jandex pass necessarily visits every class in the jar, so {@link Probe#containedClasses} is
     * populated here too, letting the bytecode signal reuse this scan instead of re-walking the jar.
     */
    private static void probeAnnotations(ZipFile zip, Probe p) throws IOException {
        Indexer indexer = new Indexer();
        boolean any = false;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                continue;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                indexer.index(in);
                any = true;
            } catch (IOException | IllegalArgumentException ignored) {
                // Unparseable class (multi-release, corrupt); skip it, coverage over completeness.
            }
        }
        if (!any) {
            return;
        }
        Index index = indexer.complete();
        for (ClassInfo ci : index.getKnownClasses()) {
            p.containedClasses.add(ci.name().toString());
        }
        for (AnnotationInstance ai : index.getAnnotations(CONFIG_MAPPING)) {
            AnnotationValue prefix = ai.value("prefix");
            if (prefix != null && !prefix.asString().isBlank()) {
                p.fromAnnotations.add(normalize(prefix.asString()));
            }
        }
        for (AnnotationInstance ai : index.getAnnotations(CONFIG_ROOT)) {
            if (ai.target() == null || ai.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            ClassInfo ci = ai.target().asClass();
            // A @ConfigRoot class almost always also carries @ConfigMapping; only add a fallback when
            // it does not, using the legacy "name" attribute.
            if (ci.declaredAnnotation(CONFIG_MAPPING) != null) {
                continue;
            }
            AnnotationValue name = ai.value("name");
            if (name != null && !name.asString().isBlank() && !"<<parent>>".equals(name.asString())) {
                p.fromAnnotations.add(normalize("quarkus." + name.asString()));
            }
        }
    }

    /** Config roots are compared as prefixes, so they always end with a dot. */
    static String normalize(String root) {
        String r = root.trim();
        if (r.startsWith("\"") && r.endsWith("\"") && r.length() > 1) {
            r = r.substring(1, r.length() - 1);
        }
        return r.endsWith(".") ? r : r + ".";
    }
}
