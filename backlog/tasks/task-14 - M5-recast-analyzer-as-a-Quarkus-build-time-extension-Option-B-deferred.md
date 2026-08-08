---
id: TASK-14
title: 'M5: recast analyzer as a Quarkus build-time extension (Option B, deferred)'
status: To Do
assignee: []
created_date: '2026-08-07 06:49'
labels: []
dependencies: []
references:
  - TASK-4
  - docs/M4-QUARKIVERSE-EVAL.md
  - TASK-13
priority: low
type: feature
ordinal: 14000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Deferred strategic option (decided 2026-08-07: pursue Option A now, Option B eventually). Recast the analyzer as a Quarkus build-time extension: a `-deployment` artifact with a `@BuildStep` that runs the three-signal classification during augmentation, emits the report, and optionally fails the build (qea.failOnSuspect semantics). Then propose to Quarkiverse.

Why this is the real Quarkiverse path (vs the standalone mojo): see docs/M4-QUARKIVERSE-EVAL.md. As an extension the analysis receives the ApplicationModel from the augmentation context directly, eliminating the ChainedMavenWorkspaceReader / TASK-9 reactor-resolution complexity. Native fit for Quarkiverse (catalog, Ecosystem CI, quarkus-* naming).

Costs/trade-offs (do NOT start until these are accepted):
- Re-architecture: the mojo entry point (AnalyzeMojo) is replaced by build-step wiring; the Analyzer core (three signals) is reusable. New milestone, not a small step.
- Capability loss: an extension only analyzes the app that declares it, so the central-CI-sweep use case (the Apicurio 67-module origin scenario) stops working. The mojo (Option A) must stay for that.
- Validation reset: the two benches validate the mojo form; the extension form needs its own bench pass.

Prerequisites: (1) TASK-13 bench re-baseline confirms the analyzer's real-world precision; (2) Option A (standalone) has shipped and adoption/feedback suggests the in-build form is wanted. Decision point, not a committed milestone.
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
