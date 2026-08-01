---
id: TASK-10
title: Split the JSON/text summary into extensions vs plain jars
status: In Progress
assignee: []
created_date: '2026-08-01 17:21'
updated_date: '2026-08-01 19:14'
labels: []
dependencies: []
ordinal: 10000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
The report summary block aggregates extensions and plain jars into one count (usedBytecode/usedConfig/usedCapability/suspect/total), which misled the second-bench triage into reporting 16 suspects when the extension-level truth was 4. Split into two blocks (extensions{}, plainJars{}) keeping the combined total for compatibility, and make Reporter.toText print the extension-level line first.
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
