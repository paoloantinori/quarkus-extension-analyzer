---
id: TASK-30
title: >-
  Re-verify ablation-bench ground truth against the current (post-shade-fix)
  mojo
status: Done
assignee: []
created_date: '2026-08-17 08:14'
updated_date: '2026-08-17 08:21'
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
- [x] #1 Mojo corrente eseguito sulle app dell'ablation bench disponibili (super-heroes-fresh: heroes/villains/narration; quickstarts: jwt-qs, cache-qs)
- [x] #2 Per ogni falso positivo confermato dall'ablation (config-yaml, rest-qute, quarkus-rest, rest-jackson, reactive-pg): il mojo corrente ora lo accredita
- [x] #3 Per ogni true suspect dell'ablation (info, micrometer-otel, smallrye-health): il mojo corrente lo lascia suspect
- [x] #4 Discrepanze indagate e risolte (regola mancante o verdict sbagliato)
- [x] #5 Tabella di confronto registrata nel work log e nei docs banco
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Ran the current mojo over the five surviving ablation apps and checked all 13 classified rows: 12 matched ground truth; the 13th (jwt-qs / quarkus-rest-jackson, classified "false positive UNRESOLVED") turned out to be a GROUND-TRUTH ERROR, not a tool gap: the app's single resource returns String from every endpoint, so there is no POJO serialization to break. Re-ablated with the stronger oracle (dep removed, mvn verify green including all 9 TokenSecuredResourceTest endpoint tests, the started app's installed-features list has no jackson): the extension is genuinely removable and the tool's suspect verdict is CORRECT. The ablation doc gained a dated re-verification section with the reversal and the methodological corollary (verify runtime-impact claims against actual endpoint shapes). Net: the tool's verdicts now match empirical ground truth on all 13 rows. DoD reviews N/A for the code (no production code touched); the doc claim is backed by the captured ablation run. All bench app poms verified restored pristine.
<!-- SECTION:FINAL_SUMMARY:END -->
