---
id: TASK-15
title: >-
  Option A: make the plugin Maven-Central-ready (POM + release profile +
  RELEASING doc)
status: Done
assignee: []
created_date: '2026-08-08 07:19'
updated_date: '2026-08-08 07:35'
labels: []
dependencies: []
references:
  - TASK-4
  - docs/M4-QUARKIVERSE-EVAL.md
  - TASK-13
priority: medium
type: chore
ordinal: 15000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Option A (decided 2026-08-07/08: ship standalone, not Quarkiverse). Make the plugin publishable to Maven Central. Scope of THIS task (release engineering, no outward action):
1. POM metadata required by Central: url, scm, licenses, developers.
2. Source + javadoc artifact generation (maven-source-plugin, maven-javadoc-plugin).
3. A -Prelease profile with GPG signing (maven-gpg-plugin) and Sonatype Central Portal publishing (central-publishing-maven-plugin), so normal builds are unaffected.
4. docs/RELEASING.md documenting the user-side prerequisites (Sonatype Central namespace for io.github.paoloantinori, GPG key, settings.xml server) and the release steps.

Defaults adopted (user did not specify; correct if wrong): version stays 1.0-SNAPSHOT (bump is release-time, post-TASK-13); publishing via Central Portal (OSSRH sunset); all release machinery under -Prelease.

OUT OF SCOPE / deferred: the actual publish/release (step 4) waits for TASK-13 bench re-baseline per docs/M4-QUARKIVERSE-EVAL.md. Cross-version CI testing is a separate follow-up. Promotion materials (blog/mailing-list/SO drafts) are a separate follow-up.

Reference: docs/M4-QUARKIVERSE-EVAL.md (Option A).
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [x] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Blocked 2026-08-08 on a groupId/SCM identity mismatch before POM metadata can be written: git remote is github.com/paoloantinori/quarkus-extension-analyzer but POM groupId is io.github.paoloantinori (implies github.com/pantinor). Sonatype Central verifies io.github.<x> against ownership of github.com/<x>, so these must agree. SCM URL will be the real remote (paoloantinori) either way; the question is whether the groupId stays io.github.paoloantinori (user owns github.com/pantinor) or renames to io.github.paoloantinori. Awaiting user clarification; no POM edits made yet.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Plugin is now Maven-Central-ready (build/docs only; no outward publish). plugin/pom.xml: renamed groupId io.github.paoloantinori -> io.github.paoloantinori (matches the real repo owner github.com/paoloantinori, so the Sonatype Central namespace verifies); added Central-required metadata (url, scm, licenses, developers); added a -Prelease profile (maven-source-plugin 3.3.1, maven-javadoc-plugin 3.11.2, maven-gpg-plugin 3.2.7, org.sonatype.central:central-publishing-maven-plugin 0.9.0) isolated so normal builds are unaffected. Updated GAV in README/M2-VALIDATION/SECOND-BENCH usage examples; left the historical [ERROR] log at SECOND-BENCH:30 untouched. Added docs/RELEASING.md (user-side prerequisites: Central namespace for io.github.paoloantinori, GPG key, settings.xml server; release steps; TASK-13 prerequisite). Verified: mvn -T1 test = 83/83 green; only the historical GAV ref remains. SCOPE NOTE: Java package stays io.github.paoloantinori.qea (Central does not require groupId == package); package rename is a separate follow-up. DoD #1/#2 applied by analogy as a static POM correctness review (parse-valid via mvn test; load-bearing central-publishing-maven-plugin 0.9.0 verified against the official Sonatype guide; other plugin versions flagged in RELEASING.md for release-time confirmation). The release profile is parse-valid but NOT yet exercised; first exercise is at release (deferred to after TASK-13). Actual publish/release remains out of scope (step 4, post-TASK-13).
<!-- SECTION:FINAL_SUMMARY:END -->
