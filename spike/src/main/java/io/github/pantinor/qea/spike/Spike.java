package io.github.pantinor.qea.spike;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ExtensionCapabilities;
import io.quarkus.bootstrap.resolver.BootstrapAppModelResolver;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.maven.dependency.ResolvedDependency;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * M1 spike. Answers the two risky assumptions of docs/DESIGN.md against a real application.
 *
 * <pre>
 *   mvn -q compile exec:java
 *   mvn -q compile exec:java -Dexec.args="&lt;groupId:artifactId:version&gt; &lt;application.properties&gt;"
 * </pre>
 */
public final class Spike {

    private static final String DEFAULT_APP = "io.apicurio:apicurio-registry-app:3.3.2-SNAPSHOT";
    private static final String DEFAULT_PROPERTIES =
            "/home/pantinor/data/repo/work/apicurio-registry/app/src/main/resources/application.properties";

    /** Below this the inheritance heuristic treats a config root as extension-specific. */
    private static final double UBIQUITY_THRESHOLD = 0.5d;

    public static void main(String[] args) throws Exception {
        String appCoords = args.length > 0 ? args[0] : DEFAULT_APP;
        Path propertiesFile = Path.of(args.length > 1 ? args[1] : DEFAULT_PROPERTIES);

        System.out.println("=".repeat(120));
        System.out.println("quarkus-extension-analyzer :: M1 spike");
        System.out.println("  application : " + appCoords);
        System.out.println("  config      : " + propertiesFile);
        System.out.println("=".repeat(120));

        // --- ASSUMPTION 2 -------------------------------------------------------------------
        long t0 = System.currentTimeMillis();
        ApplicationModel model = resolveModel(appCoords);
        long elapsed = System.currentTimeMillis() - t0;

        System.out.println();
        System.out.println("[A2] ApplicationModel resolved in " + elapsed + " ms");
        System.out.println("[A2]   app artifact          : " + model.getAppArtifact().toCompactCoords());
        System.out.println("[A2]   dependencies          : " + model.getDependencies().size());
        System.out.println("[A2]   runtime dependencies  : " + model.getRuntimeDependencies().size());
        System.out.println("[A2]   extension capabilities: " + model.getExtensionCapabilities().size());
        System.out.println("[A2]   platform imports      : "
                + (model.getPlatforms() == null ? "none" : model.getPlatforms().getImportedPlatformBoms().size()));

        List<ResolvedDependency> extensions = new ArrayList<>();
        for (ResolvedDependency d : model.getDependencies()) {
            if (d.isRuntimeExtensionArtifact()) {
                extensions.add(d);
            }
        }
        extensions.sort((a, b) -> a.toCompactCoords().compareTo(b.toCompactCoords()));
        List<ResolvedDependency> direct = extensions.stream().filter(Dependency::isDirect).toList();
        System.out.println("[A2]   quarkus extensions    : " + extensions.size()
                + " (" + direct.size() + " directly declared)");

        // --- ASSUMPTION 1 -------------------------------------------------------------------
        Map<String, ConfigRoots.Probe> probes = new LinkedHashMap<>();
        for (ResolvedDependency d : extensions) {
            probes.put(ga(d), ConfigRoots.probe(paths(d)));
        }
        printSourceCoverage(extensions, probes);

        AppConfig cfg = AppConfig.read(propertiesFile);
        System.out.println();
        System.out.println("[CFG] " + propertiesFile.getFileName() + ": " + cfg.rawLines() + " lines, "
                + cfg.allKeys().size() + " distinct keys, " + cfg.quarkusKeys().size() + " under quarkus.*, "
                + cfg.keysByProfile().size() + " profiles " + cfg.keysByProfile().keySet());

        Map<String, Set<String>> inherited = inheritRoots(extensions, probes);
        printTable(direct, probes, inherited, cfg);
        printCapabilities(model);
    }

    /**
     * Assumption 2 entry point. Everything below runs in a plain JVM: no Maven session, no mojo, no
     * augmentation. Workspace discovery is disabled so the resolver reads the artifact from the local
     * repository instead of trying to interpret the spike's own project as the application.
     */
    private static ApplicationModel resolveModel(String coords) throws Exception {
        MavenArtifactResolver mvn = MavenArtifactResolver.builder()
                .setWorkspaceDiscovery(false)
                .build();
        BootstrapAppModelResolver resolver = new BootstrapAppModelResolver(mvn);
        return resolver.resolveModel(ArtifactCoords.fromString(coords));
    }

    private static void printSourceCoverage(List<ResolvedDependency> extensions,
                                            Map<String, ConfigRoots.Probe> probes) {
        int total = extensions.size();
        int a = 0;
        int b = 0;
        int c = 0;
        int d = 0;
        int union = 0;
        for (ResolvedDependency ext : extensions) {
            ConfigRoots.Probe p = probes.get(ga(ext));
            if (!p.fromExtensionProperties.isEmpty()) {
                a++;
            }
            if (!p.fromExtensionYaml.isEmpty()) {
                b++;
            }
            if (!p.fromConfigModelJson.isEmpty()) {
                c++;
            }
            if (!p.fromAnnotations.isEmpty()) {
                d++;
            }
            if (!p.roots().isEmpty()) {
                union++;
            }
        }
        System.out.println();
        System.out.println("[A1] config-root source coverage over " + total + " resolved extensions");
        System.out.printf("[A1]   A META-INF/quarkus-extension.properties : %2d / %d (%.0f%%)%n", a, total, pct(a, total));
        System.out.printf("[A1]   B META-INF/quarkus-extension.yaml       : %2d / %d (%.0f%%)%n", b, total, pct(b, total));
        System.out.printf("[A1]   C quarkus-config-doc/config-model.json  : %2d / %d (%.0f%%)%n", c, total, pct(c, total));
        System.out.printf("[A1]   D @ConfigMapping/@ConfigRoot via Jandex : %2d / %d (%.0f%%)%n", d, total, pct(d, total));
        System.out.printf("[A1]   union of B+C+D (what the spike uses)    : %2d / %d (%.0f%%)%n", union, total, pct(union, total));
    }

    private static double pct(int n, int total) {
        return total == 0 ? 0d : (100d * n) / total;
    }

    /**
     * Derived signal for extensions that own no config root (the four JDBC drivers are the motivating
     * case). An extension inherits the roots of the extensions it directly depends on, except roots
     * owned by "ubiquitous" extensions, i.e. those more than half of all extensions depend on
     * (quarkus-core, quarkus-arc and friends). Without that filter every extension would inherit
     * quarkus.log.* from core and the signal would be worthless.
     */
    private static Map<String, Set<String>> inheritRoots(List<ResolvedDependency> extensions,
                                                         Map<String, ConfigRoots.Probe> probes) {
        Set<String> knownGa = new HashSet<>();
        for (ResolvedDependency d : extensions) {
            knownGa.add(ga(d));
        }

        Map<String, Integer> dependents = new HashMap<>();
        Map<String, Set<String>> extDepsOf = new LinkedHashMap<>();
        for (ResolvedDependency d : extensions) {
            Set<String> deps = extensionDepsOf(d, probes.get(ga(d)), knownGa);
            extDepsOf.put(ga(d), deps);
            for (String dep : deps) {
                dependents.merge(dep, 1, Integer::sum);
            }
        }
        Set<String> ubiquitous = new TreeSet<>();
        for (Map.Entry<String, Integer> e : dependents.entrySet()) {
            if (e.getValue() > extensions.size() * UBIQUITY_THRESHOLD) {
                ubiquitous.add(e.getKey());
            }
        }
        System.out.println("[A1]   ubiquitous extensions excluded from root inheritance: " + ubiquitous);

        Map<String, Set<String>> inherited = new LinkedHashMap<>();
        for (ResolvedDependency d : extensions) {
            String key = ga(d);
            if (!probes.get(key).roots().isEmpty()) {
                continue;
            }
            Set<String> roots = new TreeSet<>();
            for (String dep : extDepsOf.getOrDefault(key, Set.of())) {
                if (ubiquitous.contains(dep)) {
                    continue;
                }
                ConfigRoots.Probe dp = probes.get(dep);
                if (dp != null) {
                    for (String r : dp.roots()) {
                        roots.add(r + "  <-" + shortName(dep));
                    }
                }
            }
            if (!roots.isEmpty()) {
                inherited.put(key, roots);
            }
        }
        return inherited;
    }

    /** Direct dependencies of an extension that are themselves extensions of this application. */
    private static Set<String> extensionDepsOf(ResolvedDependency d, ConfigRoots.Probe probe, Set<String> known) {
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
        // extension-dependencies list is the documented fallback.
        for (String gaCoords : probe.extensionDependencies) {
            if (known.contains(gaCoords)) {
                out.add(gaCoords);
            }
        }
        return out;
    }

    private static void printTable(List<ResolvedDependency> direct,
                                   Map<String, ConfigRoots.Probe> probes,
                                   Map<String, Set<String>> inherited,
                                   AppConfig cfg) {
        System.out.println();
        System.out.println("=".repeat(140));
        System.out.println("CLASSIFICATION of directly declared Quarkus extensions "
                + "(bytecode signal OUT OF SCOPE for this spike)");
        System.out.println("SOURCE: B=quarkus-extension.yaml  C=quarkus-config-model.json  "
                + "D=@ConfigMapping via Jandex  inherit=root of a non-ubiquitous extension dependency");
        System.out.println("=".repeat(140));
        System.out.printf("%-46s %-7s %-34s %-6s %s%n",
                "EXTENSION", "SOURCE", "CONFIG ROOTS", "#KEYS", "VERDICT");
        System.out.println("-".repeat(140));

        int usedConfig = 0;
        int usedInherited = 0;
        int suspect = 0;
        for (ResolvedDependency ext : direct) {
            String key = ga(ext);
            ConfigRoots.Probe p = probes.get(key);
            Set<String> own = p.roots();
            Map<String, List<String>> matched = cfg.match(own);
            List<String> matchedFlat = AppConfig.flatten(matched);

            String source;
            String rootsText;
            String verdict;
            int keyCount;

            if (!matchedFlat.isEmpty()) {
                source = p.sourcesOf(matched.keySet());
                rootsText = join(own);
                keyCount = matchedFlat.size();
                verdict = "used-config";
                usedConfig++;
            } else if (!own.isEmpty()) {
                source = p.sourcesOf(own);
                rootsText = join(own);
                keyCount = 0;
                verdict = "suspect (roots known, no key)";
                suspect++;
            } else {
                Set<String> inh = inherited.get(key);
                Set<String> plainInh = new TreeSet<>();
                if (inh != null) {
                    for (String r : inh) {
                        plainInh.add(r.substring(0, r.indexOf("  <-")));
                    }
                }
                List<String> inhMatchedFlat = AppConfig.flatten(cfg.match(plainInh));
                if (!inhMatchedFlat.isEmpty()) {
                    source = "inherit";
                    rootsText = join(inh);
                    keyCount = inhMatchedFlat.size();
                    verdict = "used-config (inherited)";
                    usedInherited++;
                } else {
                    source = "-";
                    rootsText = "(none)";
                    keyCount = 0;
                    verdict = "suspect (no config metadata)";
                    suspect++;
                }
            }
            System.out.printf("%-46s %-7s %-34s %-6s %s%n",
                    shortName(key), source, truncate(rootsText, 34), keyCount, verdict);
            if (rootsText.length() > 34) {
                System.out.printf("%-46s %-7s %s%n", "", "", "  roots: " + rootsText);
            }
            if (!matchedFlat.isEmpty()) {
                System.out.printf("%-46s %-7s %s%n", "", "", "  keys : " + truncate(join(matchedFlat), 80));
            }
        }
        System.out.println("-".repeat(140));
        System.out.println("used-config = " + usedConfig
                + " | used-config (inherited) = " + usedInherited
                + " | suspect = " + suspect
                + " | total directly declared extensions = " + direct.size());
        System.out.println("Every row is additionally 'used-bytecode: NOT CHECKED' (signal 2 is out of scope for M1).");
    }

    private static void printCapabilities(ApplicationModel model) {
        Collection<ExtensionCapabilities> caps = model.getExtensionCapabilities();
        int provides = 0;
        int requires = 0;
        for (ExtensionCapabilities c : caps) {
            provides += c.getProvidesCapabilities().size();
            requires += c.getRequiresCapabilities().size();
        }
        System.out.println();
        System.out.println("[A2] capability graph available for signal 3: " + caps.size()
                + " extensions, " + provides + " provided capabilities, " + requires + " required capabilities");
        Set<String> sample = new TreeSet<>();
        for (ExtensionCapabilities c : caps) {
            if (!c.getRequiresCapabilities().isEmpty()) {
                sample.add(shortName(c.getExtension()) + " requires " + c.getRequiresCapabilities());
            }
        }
        sample.stream().limit(5).forEach(s -> System.out.println("[A2]   e.g. " + s));
    }

    private static String ga(ResolvedDependency d) {
        return d.getGroupId() + ":" + d.getArtifactId();
    }

    private static String shortName(String gaCoords) {
        return gaCoords.startsWith("io.quarkus:") ? gaCoords.substring("io.quarkus:".length()) : gaCoords;
    }

    private static List<Path> paths(ResolvedDependency d) {
        List<Path> out = new ArrayList<>();
        d.getResolvedPaths().forEach(out::add);
        return out;
    }

    private static String join(Collection<String> c) {
        return String.join(", ", new LinkedHashSet<>(c));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private Spike() {
    }
}
