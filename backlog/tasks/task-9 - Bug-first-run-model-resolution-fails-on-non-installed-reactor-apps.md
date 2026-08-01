---
id: TASK-9
title: 'Bug: first-run model resolution fails on non-installed reactor apps'
status: To Do
assignee: []
created_date: '2026-08-01 17:21'
labels: []
dependencies: []
priority: high
ordinal: 9000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found on the second bench (super-heroes rest-fights): the mojo resolves the current project's ApplicationModel via repository coordinates, so on a freshly cloned multi-module project that has only been compiled (not installed) it crashes with ArtifactNotFoundException on the app's own jar. First-run adoption blocker. Fix: resolve the current MavenProject from the reactor/workspace (setWorkspaceDiscovery(true) with the session, or feed the project's build output paths directly), or at minimum fail with an actionable message telling the user to run mvn install first. Evidence: /tmp/qea-run-X.log, docs/SECOND-BENCH.md.
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
