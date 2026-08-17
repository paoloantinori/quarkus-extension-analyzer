---
id: TASK-30
title: >-
  Re-verify ablation-bench ground truth against the current (post-shade-fix)
  mojo
status: To Do
assignee: []
created_date: '2026-08-17 08:14'
labels: []
dependencies: []
priority: high
type: task
ordinal: 26000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
The shade fix (TASK-28) revived ConfigRootProbe's @ConfigRoot fallback in the mojo (dead since TASK-20) and the annotation-consumer rules now run in the mojo too: every historical mojo report is not comparable with current output. The ablation bench (docs/ABLATION-BENCH.md) established empirical ground truth by removing deps and observing build/runtime; its 9 false positives and 5 true suspects should now line up EXACTLY with the current mojo's verdicts. Verify that they do: run the current mojo over the ablation apps and check each classified dependency. Any ablation-confirmed false positive the current mojo still flags suspect is a missing rule; any true suspect it credits is a false credit. Note: rest-heroes/villains/narration must come from super-heroes-fresh (the old workspace is damaged); grpc-locations has no application.yml in the fresh clone (config moved), so its config-yaml case is superseded - record that.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Mojo corrente eseguito sulle app dell'ablation bench disponibili (super-heroes-fresh: heroes/villains/narration; quickstarts: jwt-qs, cache-qs)
- [ ] #2 Per ogni falso positivo confermato dall'ablation (config-yaml, rest-qute, quarkus-rest, rest-jackson, reactive-pg): il mojo corrente ora lo accredita
- [ ] #3 Per ogni true suspect dell'ablation (info, micrometer-otel, smallrye-health): il mojo corrente lo lascia suspect
- [ ] #4 Discrepanze indagate e risolte (regola mancante o verdict sbagliato)
- [ ] #5 Tabella di confronto registrata nel work log e nei docs banco
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->
