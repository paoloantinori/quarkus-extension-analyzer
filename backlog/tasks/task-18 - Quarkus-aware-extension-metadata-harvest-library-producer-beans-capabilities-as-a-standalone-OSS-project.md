---
id: TASK-18
title: >-
  Quarkus-aware extension-metadata harvest library (producer beans,
  capabilities) as a standalone OSS project
status: Done
assignee: []
created_date: '2026-08-12 13:20'
updated_date: '2026-08-13 10:39'
labels:
  - spike
dependencies: []
priority: low
type: feature
ordinal: 18000
---

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /simplify on the changed code and apply the cleanups it surfaces
- [ ] #2 Run /code-review at high effort on the final diff and resolve every finding
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Standalone OSS library (separate from the analyzer mojo): a reusable 'Quarkus extension metadata harvest' that extracts, from any extension's runtime+deployment jars WITHOUT running augmentation, the structured facts tooling needs: producer-bean declarations (SyntheticBeanBuildItem.configure(<Type>.class), AdditionalBeanBuildItem('<Type>'), BeanRegistrar), config roots, capabilities, extension-dependencies. Consumer: this analyzer (TASK-8 producer signal), but also IDE plugins, CI checks, and any tool that must answer 'what does extension X provide/consume?'. WHY A SEPARATE PROJECT: research (claudedocs/research_task8-reuse-mechanisms_2026-08-12.md) confirmed no existing dependency-analysis library has CDI/Quarkus producer awareness (autonomousapps, DepClean: bytecode-only, no CDI). A neutral mid-layer library fills that gap for the whole ecosystem, and makes the analyzer a consumer rather than a monolith. TECHNICAL CORE (validated by the TASK-8 prototype): ASM instruction-level extraction of configure()/additional-bean producer declarations from deployment bytecode, which separates 'this extension declares it produces Type' from 'this extension merely references Type' (the vocabulary-attribution wall). SCOPE TO VALIDATE before committing: (a) what fraction of real Quarkus extensions declare producers statically extractable (phase-A census showed producer APIs are ubiquitous, but the configure() arg extraction hit-rate needs measuring across a wide extension corpus); (b) the dynamic-producer tail (BeanRegistrar) size, which needs a curated fallback. NOT STARTED: a spike/decision, not a committed build. Relationship to the analyzer: if the library matures, the analyzer's deploymentvocab package + a future producer-signal both become thin consumers of it. References: TASK-8, docs/SUSPECT-TRIAGE.md, claudedocs/research_task8-reuse-mechanisms_2026-08-12.md.

UPDATE 2026-08-12 (user decision: BOTH architectures, independent projects): TASK-18 is now the SHARED CORE of a 3-artifact structure: (1) this task = quarkus-extension-analyzer-core, the Analyzer + report model + ignore fragments, pure Java + Quarkus bootstrap API (stable ApplicationModel). Consumed by both (2) the mojo (TASK-15) and (3) the extension (TASK-19). The boundary is already clean: Analyzer accepts a resolved ApplicationModel; the resolution machinery lives only in the mojo shell. The producer-extraction prototype (/tmp/QeaProducerProbe.java, ASM instruction-level configure() harvest) validated that the core's signal logic can be extended cleanly, and confirmed the standalone library fills the CDI/Quarkus-producer-awareness gap no existing dependency-analysis library covers. BUILD ORDER: extract core (this task) BEFORE the extension (TASK-19), since both shells consume it. The TASK-8 vocabulary signal (opt-in, commit 054e64d) stays in the core as an experimental signal.
<!-- SECTION:NOTES:END -->
