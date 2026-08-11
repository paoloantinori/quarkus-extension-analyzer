# Suspect triage from the TASK-13 bench re-baseline

Date: 2026-08-11. Author: autonomous triage pass. Input: the two bench JSONs
under `docs/_bench-runs/` plus source grep of both bench apps. Purpose: classify
each of the 8 remaining suspects as true positive vs false positive, find the
root cause, and decide whether TASK-8 (a fourth signal) is worth building.

## Headline finding

The false-positive rate among suspects is high. Of 8 suspects, at least 4 are
confirmed genuinely-used extensions the tool fails to credit. The dominant root
causes are the shared-jar and ubiquitous-jar attribution exclusions (accepted
design trade-offs from TASK-5/TASK-11), plus one possible signal-2 bug
(scheduler). TASK-8 as originally framed (DI-produced bean mapping) addresses
only a slice of this, so it is re-framed below.

## Per-suspect classification

Legend: FP = false positive (genuinely used, tool misses it). TP = true positive
(genuinely removable). U = uncertain.

### Apicurio `app` (5 suspects)

| Suspect | Evidence of real use | Verdict | Root cause |
|---|---|---|---|
| `apicurio-registry-config-index` | none found in app source; internal Apicurio extension, no config roots probed | U | unknown; needs Apicurio-internal knowledge |
| `quarkus-resteasy-client-jackson` | app uses REST client + Jackson; no direct producer reference | U/FP | shared jars (servlet/validation/ws.rs) |
| `quarkus-resteasy-jackson` | app uses `ObjectMapper` (`com.fasterxml.jackson`) | FP | jackson-databind is UBIQUITOUS, filtered by the >50% rule, so the reference is invisible |
| `quarkus-scheduler` | app uses `@Scheduled` (`io.quarkus.scheduler.Scheduled`) in `RegistryStorageConfigCache` | FP | `io.quarkus.scheduler.Scheduled` lives in the SHARED `quarkus-scheduler-api` jar (not the extension's own runtime jar, which holds only `io/quarkus/scheduler/runtime/*`); shared-jar exclusion. Initially flagged as an anomaly, RESOLVED as not-a-bug (TASK-17 closed): an airtight re-check showed the earlier "in its own jar" claim was a grep artifact. Same root cause as hibernate-validator. |
| `quarkus-smallrye-jwt` | app injects `JsonWebToken` in `AdminOverride` | FP | the JWT type lives in a shared jar; shared-jar exclusion |

### super-heroes `rest-fights` (3 suspects)

| Suspect | Evidence of real use | Verdict | Root cause |
|---|---|---|---|
| `quarkus-hibernate-validator` | `@NotNull`/`@NotBlank` on `Fight`, `ImageGenerationRequest` | FP | `jakarta.validation-api` is shared; shared-jar exclusion (confirmed in TASK-13) |
| `quarkus-info` | none in source/config; `/info` is a runtime endpoint | U/TP | runtime-only, no compile-time reference |
| `quarkus-micrometer-opentelemetry` | no `quarkus.micrometer.otel` config key; shared jars only | U/TP | likely genuinely unused, or auto-instrumented |

Summary: 4 confirmed FP, 1 FP-with-anomaly, 3 uncertain (leaning TP). So the
tool's real precision on suspects is roughly 50/50, worse than the bare
suspect count implies. This is important, honest context for any release or
promotion claim.

## The scheduler anomaly (RESOLVED, not a bug)

`quarkus-scheduler` was initially flagged as a signal-2 anomaly because the app
references `@Scheduled` and a loose `unzip` grep seemed to show the class in the
extension's runtime jar. An airtight re-check corrected this:

- Apicurio's `RegistryStorageConfigCache.class` does reference
  `io/quarkus.scheduler.Scheduled` (confirmed by `javap`).
- BUT `io.quarkus.scheduler.Scheduled` lives in `quarkus-scheduler-API`
  (the SHARED jar), not in `quarkus-scheduler`'s own runtime jar, which holds
  only `io/quarkus/scheduler/runtime/*` classes (confirmed by exact-class
  `unzip -l` on both jars).
- The earlier "in its own jar" reading was a grep artifact: the loose pattern
  `Scheduled.class` also matched runtime classes like `SchedulerConfig.class`.

So scheduler is the SAME shared-jar pattern as hibernate-validator, not a
signal-2 bug. TASK-17 was closed as not-a-bug. This reinforces the triage's
root-cause finding: every confirmed false positive traces to the
shared/ubiquitous-jar attribution exclusion, which is the analyzer's deliberate
safety property.

## TASK-8 verdict: re-frame and defer (do not build now)

TASK-8's original hypothesis (map extension to produced bean types; mark used
when project bytecode references those types) would resolve only the
producer-pattern FPs (hibernate-validator, smallrye-jwt), by deliberately
overriding the shared-jar exclusion for KNOWN producers. It would NOT resolve
the ubiquitous-jar FPs (resteasy-jackson) or the runtime-only/annotation-driven
extensions (info, otel).

So TASK-8's hit rate is low and it deliberately weakens the shared-jar safety
property. The bench triage suggests a BROADER re-frame: a curated "annotation
and API-type attribution" signal (credit an extension when the project uses an
annotation/type the extension provides, even via a shared jar) would cover more
cases (scheduler, validator, jwt), but it is exactly the curated-table approach
with its maintenance cost and its weakening of the safety property.

Decision for this autonomous run: do NOT implement TASK-8 or its re-frame. Both
require a curated table whose design (which types map to which extension,
validated against real Quarkus) and its deliberate weakening of the shared-jar
invariant are judgment calls the user should make. TASK-8 is updated to
deferred-with-evidence, with the re-frame recorded.
