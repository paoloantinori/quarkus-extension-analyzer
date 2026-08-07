# M4 Quarkiverse Proposal Evaluation

**Date:** 2026-08-07
**Author:** TASK-4 evaluation, grounded in research against official Quarkiverse sources
**Confidence:** HIGH on the category-fit finding (three official sources agree); MEDIUM on the
exact onboarding step list (the ChecklistForNewProjects page was not fetched due to a transient
tool outage; requirements are enumerated from the hub home).

## Executive recommendation

**Do not propose the plugin to Quarkiverse in its current form.** It is a Maven plugin, and
Quarkiverse is, by its own definition, a home for **Quarkus extensions**. The two genuine paths
are:

- **(A) Stay standalone** under `io.github.pantinor` (as today), publish to Maven Central, and
  promote via Quarkus channels. This is cheap, fits the artifact, and matches how every
  comparable analyzer is housed. **Recommended for the near term.**
- **(B) Recast as a Quarkus build-time extension** (a `-deployment` artifact with a `@BuildStep`
  that runs the three-signal analysis during augmentation) and *then* propose to Quarkiverse.
  This is a natural Quarkiverse fit and would let the analysis receive the `ApplicationModel`
  from the augmentation context, eliminating the reactor-resolution complexity (the
  `ChainedMavenWorkspaceReader` / TASK-9 machinery). It is the real Quarkiverse path, but it is a
  re-architecture and a different product shape. **A strategic option for later, not part of this
  evaluation.**

In short: Quarkiverse is not the right home for the *Maven plugin*; it is the right home for an
*extension* version of this idea, if and when that re-architecture is warranted.

## 1. Category fit (the decisive question)

Three official Quarkiverse sources define the organization identically:

> "The Quarkiverse GitHub organization provides repository hosting (including build, CI and
> release publishing setup) for **Quarkus extension projects** contributed by the community."
> (org README [1] and hub home [2], verbatim.)

The hub home [2] frames the entire org around extensions: it exists to "create a 'home' for such
extensions"; hosted projects feed the **extension catalog** (`code.quarkus.io`,
`extensions.quarkus.io`, `mvn quarkus:list-extensions`); the onboarding docs point at "Building My
First Extension" / "Writing Extensions." The infrastructure is extension-shaped:

- **Ecosystem CI** cross-tests each hosted project against Quarkus core builds [2][6], meaningful
  only for artifacts that plug into Quarkus augmentation.
- **Parent POM** `io.quarkiverse:quarkiverse-parent` [2][5] carries release/publishing config
  oriented to extension artifacts.
- **Naming** requires a `quarkus-` repo prefix, a `quarkus-` artifactId prefix, and a
  `io.quarkiverse.<name>` groupId / root package [3].
- **Release** is triggered by a PR changing `.github/project.yml` in the extension's repo [4].

A search for non-extension or build-tooling precedents in the org returned only extensions:
`quarkiverse-github-app` / `quarkus-github-app`, `quarkiverse-github-api`, and
`quarkus-asyncapi-scanner` are all Quarkus extensions (runtime artifacts), not standalone Maven
plugins. **No counterexample was found.** This plugin has no runtime/deployment artifact pair, does
not integrate with Quarkus augmentation, and integrates with Maven's build instead, which is a
category mismatch.

Decisively, the hub [2] explicitly anticipates projects that do not fit Quarkiverse's model:

> "If you do not want to commit to the above guidelines then you are more than welcome to publish
> an extension outside of Quarkus or Quarkiverse... In this case we request you to use your own
> group ID to clearly identify those artifacts are from a third-party organization."

So "does not fit Quarkiverse" is an explicitly supported status, not a dead end.

## 2. If forced through as-is: requirements vs gaps

| Requirement (Quarkiverse) | This project | Fit |
|---|---|---|
| License ASL 2.0 [2] | Apache-2.0 | yes |
| Parent POM `io.quarkiverse:quarkiverse-parent` [2][5] | standalone parent | would need adoption |
| Ecosystem CI cross-testing vs Quarkus core [2][6] | n/a (not an extension) | no, not meaningful |
| Repo / artifactId `quarkus-` prefix; groupId `io.quarkiverse.*` [3] | `*-maven-plugin`, `io.github.pantinor` | no, conflicts (plugin artifactId convention is `*-maven-plugin`, not `quarkus-*`) |
| Active maintainer + GitHub team [2] | solo | adoptable |
| Docs on `docs.quarkiverse.io` [2] | repo `docs/` | movable |
| Listing in extension catalog [2] | n/a (not an extension) | no, not eligible |

Forcing a Maven plugin into extension naming (`quarkus-…-maven-plugin`) and extension catalog
flows would be a poor fit on every axis except license.

## 3. The re-architecture option (the real Quarkiverse path)

Recast as a **Quarkus build-time extension**: a `-deployment` artifact with a `@BuildStep` that
runs the three-signal classification during the host app's augmentation, emits the report, and
optionally fails the build (the existing `qea.failOnSuspect` semantics).

Why it fits: a build-time-only extension (near-empty runtime artifact, analysis in `-deployment`)
is a legitimate Quarkus extension shape, and it would land natively in the Quarkiverse model
(catalog, Ecosystem CI, naming all apply cleanly). A genuine bonus: as an extension it receives
the `ApplicationModel` from the augmentation context directly, so the entire reactor-resolution
problem the mojo currently owns (`AnalyzeMojo`'s `ChainedMavenWorkspaceReader`, the TASK-9
first-run fix) **disappears**, because the model is already there.

Cost and trade-off: it is a real re-architecture, and it changes the product. The mojo analyzes
**any built Quarkus app from outside** (CI can run it against an app that never declares the
analyzer). An extension analyzes **the app that declares it**. Both are useful; they are different
products. The mojo form is the one currently validated on two benches.

This is a candidate **M5**, to be weighed after the analyzer's value is confirmed on the
re-baselined benches (TASK-13), not now.

## 4. Prior art (how comparable analyzers are housed)

| Analyzer | Home | Notes |
|---|---|---|
| autonomousapps `dependency-analysis-gradle-plugin` | standalone org (`autonomousapps`) | the semantic gold standard; standalone, not in Gradle's org |
| DepClean | `ASSERT-KTH` (academic org) | standalone / neutral |
| `maven-dependency-plugin` | Apache Maven core | framework-owned, but part of Maven itself |

The pattern is consistent: framework-aware dependency analyzers live in **standalone or neutral
orgs**, not inside the framework's extension catalog. A standalone home for this plugin follows
that norm. (Confidence: well-established; cross-check against the project's own DESIGN.md prior-art
section, which cites these.)

## 5. Recommendation and prerequisites

1. **Near term:** remain a standalone Maven plugin under `io.github.pantinor`; publish to Maven
   Central; promote through Quarkus channels (a `quarkus-dev` mailing-list note, a Quarkus blog
   guest post, a Stack Overflow `quarkus`-tagged answer pointing at the tool). Do **not** propose
   to Quarkiverse now.
2. **Before any Quarkiverse move (either path):** complete **TASK-13** (re-baseline both benches on
   an idle machine). Proposing a tool whose current-benchmarks validation is pending would be
   premature, by the same logic as TASK-4's own note that the evaluation should follow the TASK-9
   first-run fix (now done).
3. **Strategic fork (later, data-informed):**
   - cheap: standalone plus promotion (option A); or
   - biggest reach: recast as a build-time extension and propose to Quarkiverse (option B / M5).
   Decide after TASK-13 confirms the analyzer's real-world precision and after gauging adoption.

If the goal is **discoverability by Quarkus users without joining Quarkiverse**, note that manual
listing in the Quarkus extension registry does **not** apply (this is not an extension). The lever
is Maven Central publish plus Quarkus-channel promotion, not the registry.

## Confidence assessment

- **HIGH:** Quarkiverse is extension-only (org README, hub home, NamingConventions agree verbatim);
  naming/groupId rules; license requirement; the explicit "publish outside with your own groupId"
  option; no non-extension precedent found.
- **MEDIUM:** the exact onboarding step list (ChecklistForNewProjects not fetched, due to a
  transient tool outage; requirements taken from the hub home's "Expectations for Quarkiverse
  projects").
- **Design judgment (not researched):** the feasibility and elegance of the build-time-extension
  re-architecture in section 3. That is an architectural opinion to validate with a spike, not a
  fact.

## Sources

1. quarkiverse/quarkiverse README: https://github.com/quarkiverse/quarkiverse
2. Quarkiverse Hub home (What is / Why / Joining / Expectations): https://hub.quarkiverse.io/
3. NamingConventions: https://hub.quarkiverse.io/namingconventions/
4. Quarkiverse blog (release via `.github/project.yml`): https://quarkiverse.io/blog/quarkiverse/
5. quarkiverse-parent: https://github.com/quarkiverse/quarkiverse-parent
6. Quarkus Ecosystem CI: https://github.com/quarkusio/quarkus-ecosystem-ci
7. ChecklistForNewProjects (referenced; not fetched): https://hub.quarkiverse.io/checklistfornewprojects/
