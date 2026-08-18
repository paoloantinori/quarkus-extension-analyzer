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
package io.github.paoloantinori.qea.plugin.report;

import java.util.List;
import java.util.Map;

/**
 * TASK-41: runtime verification instructions for the build-time-invisible residual. Every signal
 * in this tool is static; the honest totality statement says runtime-only usage (an {@code /info}
 * endpoint, a serializer used only by build-step-generated implementations, a scheduler firing
 * with zero source references) cannot be seen at build time BY CONSTRUCTION. This class turns
 * that documented residual into an actionable checklist per suspect - concrete commands a human
 * tester or an agent can run against the STARTED application (or the ablation they can perform)
 * to close the loop the static analysis cannot.
 *
 * <p>Two tiers of instructions: a curated table for the families with well-known runtime
 * surface (endpoints, log lines), and a generic ablation protocol for everything else. The
 * output is plain text appended to the report (the JSON schema stays untouched; the checklist is
 * derived, not data).
 */
public final class RuntimeVerificationPlan {

    /**
     * Curated runtime checks per extension GA (the family's runtime surface). Kept conservative:
     * default management-path endpoints and observable behavior, no assumptions about app
     * config. Keys are full GAs; a missing key falls back to the generic protocol.
     */
    private static final Map<String, List<String>> CURATED = Map.ofEntries(
            Map.entry("io.quarkus:quarkus-info", List.of(
                    "start the app, then: curl -s http://localhost:8080/q/info -o /dev/null -w '%{http_code}'",
                    "removable if you do not need the build/OS info endpoint (a 404 after removal is fine)")),
            Map.entry("io.quarkus:quarkus-smallrye-health", List.of(
                    "start the app, then: curl -s http://localhost:8080/q/health",
                    "if the app declares health checks, removal loses /q/health (orchestrators probe it)")),
            Map.entry("io.quarkus:quarkus-micrometer", List.of(
                    "start the app, exercise an endpoint, then: curl -s http://localhost:8080/q/metrics | head",
                    "removable if nothing scrapes /q/metrics")),
            Map.entry("io.quarkus:quarkus-micrometer-registry-prometheus", List.of(
                    "start the app, then: curl -s http://localhost:8080/q/metrics | grep -c '^# HELP'",
                    "removable if no Prometheus scrape target points at the app")),
            Map.entry("io.quarkus:quarkus-micrometer-opentelemetry", List.of(
                    "start the app with the OTLP endpoint configured, exercise an endpoint, check the collector received metrics",
                    "removable if metrics are not exported via OTLP")),
            Map.entry("io.quarkus:quarkus-opentelemetry", List.of(
                    "start the app with tracing enabled, exercise an endpoint, confirm a span reaches the configured backend",
                    "removable if nothing consumes the traces")),
            Map.entry("io.quarkus:quarkus-rest-jackson", List.of(
                    "start the app, call a POJO-returning endpoint: curl -s -H 'Accept: application/json' http://localhost:8080/<endpoint>",
                    "the body must be JSON; an empty/error body after removal means it was load-bearing (ablation-proven shape: the serializer is absent at runtime while the build stays green)")),
            Map.entry("io.quarkus:quarkus-resteasy-jackson", List.of(
                    "start the app, call a POJO-returning endpoint: curl -s -H 'Accept: application/json' http://localhost:8080/<endpoint>",
                    "the body must be JSON; an empty/error body after removal means it was load-bearing")),
            Map.entry("io.quarkus:quarkus-scheduler", List.of(
                    "start the app, watch the log for the @Scheduled method firing over one interval",
                    "removable only if no job observes it (side effects: emails, cleanups, polls)")),
            Map.entry("io.quarkus:quarkus-smallrye-jwt", List.of(
                    "call an authenticated endpoint with a valid JWT: expect 200; without the extension the token validation path is gone",
                    "ablation: remove, run the app's auth tests")),
            Map.entry("io.quarkus:quarkus-smallrye-openapi", List.of(
                    "start the app, then: curl -s http://localhost:8080/q/openapi | head",
                    "removable if nothing consumes the OpenAPI schema (code generators, docs portals)")),
            Map.entry("io.quarkus:quarkus-config-yaml", List.of(
                    "confirm the keys from application.yml are actually read: start the app and check a behavior/log driven by a yaml-only key",
                    "removal makes the yaml config silently unreadable while the build stays green")));

    private static final List<String> GENERIC = List.of(
            "1. remove the dependency from the pom (keep a backup)",
            "2. mvn verify - the app's own tests are the cheapest oracle",
            "3. start the app and smoke-test the surface this family provides (endpoints, jobs, exported data)",
            "4. anything broken -> it was load-bearing: re-add and record why (a near-miss report helps the tool improve)");

    private RuntimeVerificationPlan() {
    }

    /**
     * The report's suspect EXTENSION rows (the shared predicate for the runtime plan and the
     * probe mode, so the checklist and the probe always cover the same set): suspect verdict,
     * quarkus-extension rows, the analyzer's own extension excluded (always a self-inflicted
     * suspect).
     */
    public static List<ExtensionReport> extensionSuspects(List<ExtensionReport> rows) {
        return rows.stream()
                .filter(r -> r.verdict() == Verdict.SUSPECT && r.quarkusExtension()
                        && !r.ga().startsWith("io.github.paoloantinori:"))
                .toList();
    }

    /**
     * The checklist for the report's SUSPECT extension rows: the runtime residual the static
     * signals cannot close, as concrete steps per suspect. Empty string when there is nothing
     * to verify (no extension suspects).
     */
    public static String plan(List<ExtensionReport> rows) {
        var suspects = extensionSuspects(rows);
        if (suspects.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "runtime verification (the build-time-invisible residual - steps for a human or an agent):\n");
        for (ExtensionReport s : suspects) {
            sb.append("  ").append(s.ga()).append('\n');
            for (String step : CURATED.getOrDefault(s.ga(), GENERIC)) {
                sb.append("    - ").append(step).append('\n');
            }
        }
        return sb.toString();
    }
}
