---
id: TASK-9
title: 'Bug: first-run model resolution fails on non-installed reactor apps'
status: In Progress
assignee: []
created_date: '2026-08-01 17:21'
updated_date: '2026-08-01 19:14'
labels: []
dependencies: []
priority: high
ordinal: 9000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found on the second bench (super-heroes rest-fights): the mojo resolves the current project's ApplicationModel via repository coordinates, so on a freshly cloned multi-module project that has only been compiled (not installed) it crashes with ArtifactNotFoundException on the app's own jar. First-run adoption blocker. Fix: resolve the current MavenProject from the reactor/workspace (setWorkspaceDiscovery(true) with the session, or feed the project's build output paths directly), or at minimum fail with an actionable message telling the user to run mvn install first. Evidence: /tmp/qea-run-X.log, docs/SECOND-BENCH.md.
<!-- SECTION:DESCRIPTION:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Fix (Fable):
1. Root cause: the mojo's MavenArtifactResolver is built with workspace discovery off, so resolveModel() fetches the current project's own artifact from repositories; a compiled-but-not-installed reactor app cannot be resolved. Fix by enabling workspace discovery in the in-mojo path: research the real bootstrap-maven-resolver API on the 3.33.2.1 sources in ~/.m2 (BootstrapMavenContextConfig setWorkspaceDiscovery / setCurrentProject / LocalWorkspace discovery from the project basedir) and wire it so reactor modules resolve from build outputs. The spike's standalone path (workspace discovery off) must remain OFF for spike/ (different use case).
2. If resolution fails anyway, wrap the error with an actionable message naming the two known remedies (build the module, or mvn install for exotic layouts).
3. Repro WITHOUT touching ~/.m2: /tmp/super-heroes/rest-heroes was never installed; mvn -q compile it, then run the analyze goal there: must fail on current main, succeed after the fix. Do not delete anything from the shared local repository.
4. Regression safety: cd plugin && mvn -q verify green; reinstall; the orchestrator re-runs the registry bench (installed scenario) after.
Bundled second item, TASK-10: split the report summary by quarkusExtension: JSON gains extensions{} and plainJars{} blocks (keep the combined block for compatibility, labeled), Reporter.toText prints the extension-level line first then plain jars; update ReporterTest and any test asserting the old single summary. Execution delegated; Fable verifies both benches.
<!-- SECTION:PLAN:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
