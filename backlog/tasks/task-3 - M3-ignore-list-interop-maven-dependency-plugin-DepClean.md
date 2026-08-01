---
id: TASK-3
title: 'M3: ignore-list interop (maven-dependency-plugin + DepClean)'
status: In Progress
assignee: []
created_date: '2026-08-01 09:51'
updated_date: '2026-08-01 12:24'
labels: []
dependencies: []
ordinal: 3000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Generate ignoredUnusedDeclaredDependencies fragments and DepClean ignoreDependencies regexes from the report so adopters keep their existing analyzer. CI-friendly JSON schema documented.
<!-- SECTION:DESCRIPTION:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Design decisions (Fable):
1. Scope of ignore recommendations: extensions with verdict used-config or used-capability ONLY. used-bytecode entries are visible to bytecode-based analyzers already; suspects are deliberately excluded (recommending to ignore an unproven extension would defeat the tool's purpose).
2. Opt-in flag -Dqea.ignoreFragments=true (default false) on the existing analyze goal; no new goal. When set, write two snippet files under the project build directory: qea-mdp-ignores.xml (a ready-to-paste <ignoredUnusedDeclaredDependencies> block for maven-dependency-plugin's analyze configuration) and qea-depclean-ignores.xml (a ready-to-paste <ignoreDependencies> block of regexes for DepClean). VERIFY the exact element names and accepted coordinate/wildcard formats against the two plugins' official docs before writing code; do not guess. DepClean entries are regexes: escape dots.
3. JSON report gains an ignoreRecommendations array: [{ga, verdict, reason-sentence}] so CI consumers can build their own formats without parsing XML.
4. Reporter text output: one closing line pointing at the generated files when the flag is on.
5. Tests: fragment generation (XML structure, escaping, regex escaping, empty recommendation set), JSON round-trip of ignoreRecommendations.
6. Docs: flip DESIGN.md Output bullet annotation to implemented; add the flag to README Usage. Bench validation (expect exactly the 10 used-config + 5 used-capability GAs of the registry bench in both fragments) is the orchestrator's job.
Execution delegated; Fable reviews the final diff.
<!-- SECTION:PLAN:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
