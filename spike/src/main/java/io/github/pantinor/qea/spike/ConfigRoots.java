package io.github.pantinor.qea.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.jandex.AnnotationInstance;
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
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Assumption 1: recover per-extension config-root prefixes from a resolved runtime artifact.
 *
 * <p>Four candidate sources are probed independently so the spike can report per-source coverage
 * rather than only the winner:
 *
 * <ul>
 *   <li>A - {@code META-INF/quarkus-extension.properties}</li>
 *   <li>B - {@code META-INF/quarkus-extension.yaml}, key {@code metadata.config}</li>
 *   <li>C - {@code META-INF/quarkus-config-doc/quarkus-config-model.json}</li>
 *   <li>D - {@code @ConfigMapping(prefix=)} / {@code @ConfigRoot} via Jandex over the jar</li>
 * </ul>
 */
public final class ConfigRoots {

    private static final DotName CONFIG_MAPPING = DotName.createSimple("io.smallrye.config.ConfigMapping");
    private static final DotName CONFIG_ROOT = DotName.createSimple("io.quarkus.runtime.annotations.ConfigRoot");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Per-artifact probe result: what each source yielded. */
    public static final class Probe {
        public final Set<String> fromExtensionProperties = new TreeSet<>();
        public final Set<String> fromExtensionYaml = new TreeSet<>();
        public final Set<String> fromConfigModelJson = new TreeSet<>();
        public final Set<String> fromAnnotations = new TreeSet<>();
        /** Extension GAs this artifact declares as extension dependencies (yaml metadata). */
        public final Set<String> extensionDependencies = new LinkedHashSet<>();
        public String error;

        /**
         * The union of B, C and D rather than a single winner.
         *
         * <p>No source strictly dominates. C and D are derived from the extension's own source code
         * and are authoritative where they exist, but they only cover classes annotated with
         * {@code @ConfigMapping}, so spec-defined prefixes ({@code mp.health.}, {@code mp.jwt.}) only
         * appear in B. B in turn is hand-maintained and can be plain wrong: quarkus-opentelemetry
         * declares {@code quarkus.opentelemetry.} while its real prefix is {@code quarkus.otel.}.
         *
         * <p>Taking the union is safe for a "is this extension used" signal because a spurious root
         * can only produce a false positive if it matches a key that is really owned by someone else,
         * whereas dropping a correct root produces a false "suspect" straight away.
         */
        public Set<String> roots() {
            Set<String> all = new TreeSet<>();
            all.addAll(fromExtensionYaml);
            all.addAll(fromConfigModelJson);
            all.addAll(fromAnnotations);
            return all;
        }

        /** Which of B/C/D contributed at least one of the given roots. */
        public String sourcesOf(Collection<String> roots) {
            StringBuilder sb = new StringBuilder();
            if (roots.stream().anyMatch(fromExtensionYaml::contains)) {
                sb.append('B');
            }
            if (roots.stream().anyMatch(fromConfigModelJson::contains)) {
                sb.append('C');
            }
            if (roots.stream().anyMatch(fromAnnotations::contains)) {
                sb.append('D');
            }
            return sb.length() == 0 ? "-" : sb.toString();
        }
    }

    private ConfigRoots() {
    }

    public static Probe probe(Collection<Path> resolvedPaths) {
        Probe p = new Probe();
        Path jar = resolvedPaths.stream().filter(x -> x.toString().endsWith(".jar")).findFirst().orElse(null);
        if (jar == null) {
            p.error = "no jar in resolved paths " + resolvedPaths;
            return p;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            // Each source is isolated: a malformed/unexpected artifact should degrade that one
            // source, not abort the other three. SnakeYAML's YAMLException (probeExtensionYaml)
            // and Jackson's JsonProcessingException (probeConfigModelJson) are both unchecked and
            // would otherwise escape past an IOException-only catch and crash the whole run.
            try {
                probeExtensionProperties(zip, p);
            } catch (IOException | RuntimeException e) {
                recordSourceError(p, "properties", e);
            }
            try {
                probeExtensionYaml(zip, p);
            } catch (IOException | RuntimeException e) {
                recordSourceError(p, "yaml", e);
            }
            try {
                probeConfigModelJson(zip, p);
            } catch (IOException | RuntimeException e) {
                recordSourceError(p, "config-model.json", e);
            }
            try {
                probeAnnotations(zip, p);
            } catch (IOException | RuntimeException e) {
                recordSourceError(p, "annotations", e);
            }
        } catch (IOException e) {
            p.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return p;
    }

    /** Appends a per-source failure to {@link Probe#error} without discarding earlier ones. */
    private static void recordSourceError(Probe p, String source, Exception e) {
        String msg = source + ": " + e.getClass().getSimpleName() + ": " + e.getMessage();
        p.error = p.error == null ? msg : p.error + "; " + msg;
    }

    /**
     * Source A. Included to prove the negative: this descriptor carries deployment coordinates and
     * capabilities but no configuration information at all.
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
        for (Enumeration<?> names = props.propertyNames(); names.hasMoreElements(); ) {
            String name = (String) names.nextElement();
            // Only a key that actually looks like config-root metadata counts as a hit.
            if (name.contains("config-root") || name.contains("config-prefix")) {
                p.fromExtensionProperties.add(props.getProperty(name));
            }
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
     * Source C. The config-doc model carries an explicit {@code prefix} per config root, so no
     * prefix has to be inferred from individual property paths. Note the granularity is that of the
     * declaring {@code @ConfigMapping}, which is sometimes broader than the extension's real
     * ownership: quarkus-logging-json reports {@code quarkus.log} even though it only owns
     * {@code quarkus.log.console.json.*}.
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

    /** Source D: Jandex the jar and read {@code @ConfigMapping(prefix)} / {@code @ConfigRoot(name)}. */
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
                // Unparseable class (multi-release, corrupt); skip it, the spike only needs coverage.
            }
        }
        if (!any) {
            return;
        }
        Index index = indexer.complete();
        for (AnnotationInstance ai : index.getAnnotations(CONFIG_MAPPING)) {
            AnnotationValue prefix = ai.value("prefix");
            if (prefix != null && !prefix.asString().isBlank()) {
                p.fromAnnotations.add(normalize(prefix.asString()));
            }
        }
        for (AnnotationInstance ai : index.getAnnotations(CONFIG_ROOT)) {
            if (ai.target() == null || ai.target().kind() != org.jboss.jandex.AnnotationTarget.Kind.CLASS) {
                continue;
            }
            ClassInfo ci = ai.target().asClass();
            // A @ConfigRoot class almost always also carries @ConfigMapping; only add a fallback
            // when it does not, using the legacy "name" attribute.
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
