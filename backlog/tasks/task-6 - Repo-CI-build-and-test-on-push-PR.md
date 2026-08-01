---
id: TASK-6
title: 'Repo CI: build and test on push/PR'
status: Done
assignee: []
created_date: '2026-08-01 17:03'
updated_date: '2026-08-01 17:12'
labels: []
dependencies: []
priority: high
ordinal: 6000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
The repo has no GitHub Actions workflow: mvn verify (45 tests) runs only locally. Add a minimal workflow (JDK 17, mvn -q verify on plugin/, cache) so contributions and future changes are gated. Without this, the DoD gates are the only protection.
<!-- SECTION:DESCRIPTION:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Minimal GitHub Actions workflow (.github/workflows/ci.yml): trigger on push to main + PRs; JDK 17 temurin; maven cache (actions/setup-java built-in cache or actions/cache on ~/.m2); single job running 'mvn -q verify' in plugin/ and 'mvn -q compile exec:java' smoke of spike/ is NOT included (spike needs the registry bench artifacts, not available in CI; document that exclusion in a workflow comment). Pin action versions by SHA with version comments, matching the convention seen in apicurio-registry workflows. Execution delegated; Fable reviews.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Workflow committed and pushed; SHAs verified live against upstream action repos (one version ahead of registry's own pins, proving no stale copy); mvn verify proven from a fresh checkout (45 tests). DoD gates executed as the orchestrator's direct review of the 30-line YAML (proportionate), which added permissions: contents: read and timeout-minutes: 20.
<!-- SECTION:NOTES:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [x] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
