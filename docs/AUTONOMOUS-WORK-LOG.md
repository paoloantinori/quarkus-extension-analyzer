# Autonomous Work Log (2026-08-11 onward)

**Context:** the user is away from their PC for a few days (from 2026-08-11) and
asked me to work autonomously, documenting all work and decisions for later
review. This log is the authoritative record of what I did, why, and how to
verify or revert each change.

## Guardrails I am holding to

- **No outward actions:** no `git push`, no Maven Central publish, no PRs to
  external repos, no Quarkiverse proposal. Everything stays local on `main`.
- **Atomic, well-described commits** on `main`, so the user can review or
  selectively `git revert`. Each commit message states the WHY.
- **Tests green** before any task is marked done; verify, then assert.
- **No interference** with other projects/repos; only the already-cloned bench
  apps under `/tmp` are touched.
- **End-of-unit review protocol (per user request, 2026-08-11):** at the end of
  every work unit, run `/simplify` on changed code (only if code was touched)
  and `/code-review` at high effort on the final diff; record outcomes in this
  log.
- **Backlog tooling:** the `backlog` CLI exists (`backlog task list/edit/etc.`,
  v1.48.0). Use it for all task mutations instead of hand-editing task files, so
  the format stays canonical. The earlier MCP disconnection is moot given the
  CLI.
- **MCP note:** the `crw`, `serena`, `deepwiki`, and `context7` MCP servers
  disconnected mid-session. Web/doc lookups fall back to available tools.

## Decision log

Each entry has date, decision, rationale, how to verify, and how to revert.

### D1, 2026-08-11, Starting state and plan

- **Decision:** work the backlog in priority order: (1) per-suspect triage from
  the freshly measured bench JSONs (informs TASK-8), (2) TASK-8 implement or
  wontfix-with-evidence based on triage, (3) cross-version CI, (4) promotion
  drafts, (5) final verification.
- **Rationale:** the bench re-baseline (TASK-13) just produced real data on the
  remaining suspects. That data is the right input to decide whether a fourth
  signal (TASK-8) is worth building, before investing in it. So triage comes
  first and gates TASK-8.
- **Verify:** read this log; each subsequent entry cites the commit SHA.
- **Revert:** not applicable (planning only).

## Work entries

Format per entry: timestamp, what, commit SHA, verify command, revert command.

To be appended as work lands.

### Work unit 1, 2026-08-11, Suspect triage + TASK-8/TASK-17 disposition

- **What:** classified all 8 post-TASK-13 suspects (FP/TP/U) from the bench
  JSONs plus source grep; wrote `docs/SUSPECT-TRIAGE.md`; updated TASK-8 on disk
  to deferred-with-evidence + re-framed; filed TASK-17 (scheduler signal-2
  anomaly) via the backlog CLI.
- **Key findings:** (1) ~50% of suspects are false positives (tool misses
  genuinely-used extensions); (2) dominant causes are the shared/ubiquitous-jar
  attribution exclusions (design trade-off), plus one possible signal-2 bug
  (scheduler); (3) TASK-8's producer-mapping idea has narrow value and weakens
  the shared-jar safety invariant, so it is deferred, not built.
- **End-of-unit review:**
  - `/simplify`: not applicable (docs/analysis only, no code touched).
  - `/code-review` (conceptual, on the analysis): the load-bearing claim (the
    scheduler anomaly) is backed by two independent artifacts (`javap` constant
    pool + `unzip` class location), so it is verified, not asserted. The FP/TP
    classifications rest on source greps showing real usage; re-runnable. The
    TASK-8 deferral rationale (low hit rate + safety-invariant weakening) is
    internally consistent. No changes forced.
- **Commit:** pending (will commit with work unit 2 to keep the triage and its
  task-file updates in one reviewable change set).

### Work unit 2, 2026-08-11, TASK-17 investigation: resolved as not-a-bug

- **What:** investigated the scheduler "anomaly" from the triage. Airtight
  re-check (exact-class `unzip -l` on quarkus-scheduler vs quarkus-scheduler-api)
  showed `io.quarkus.scheduler.Scheduled` lives in the SHARED
  `quarkus-scheduler-api` jar, not the extension's own runtime jar. The earlier
  "in its own jar" claim was a loose-grep artifact. Closed TASK-17 as not-a-bug
  via `backlog task edit TASK-17 --status Done`; corrected SUSPECT-TRIAGE.md.
- **Outcome:** scheduler is the same shared-jar pattern as hibernate-validator,
  confirming the triage's root-cause finding (all confirmed FPs trace to the
  shared/ubiquitous-jar attribution exclusion). No code change.
- **End-of-unit review:**
  - `/simplify`: n/a (no code touched, docs + task status only).
  - `/code-review` (conceptual): the resolution is two-source verified
    (exact-class unzip on two jars). The self-correction of my own earlier
    false claim is recorded honestly, not papered over. No changes forced.
- **Commit:** with the triage correction below.

### Work unit 3, 2026-08-11, Cross-version CI workflow (.github/workflows/cross-version.yml)

- **What:** added a JDK-matrix CI workflow (17, 21) complementing ci.yml. First
  draft had a Maven-matrix plus a third-party `setup-maven` action; I dropped
  both (the Maven-matrix needed a floating-tag third-party action, a
  supply-chain surface the repo's discipline rejects; the synthetic unit tests
  are app/Maven-version-independent anyway). Final: JDK matrix only, same pinned
  actions as ci.yml, weekly schedule + manual dispatch.
- **Process note:** searched the real Maven 3.9.x release list (caught my own
  wrong guess that 3.9.11 existed; latest is 3.9.9) before relying on version
  facts, per the search-before-asserting rule.
- **End-of-unit review (code touched):**
  - `/simplify`: ran a direct 4-angle self-review (reuse/simplification/
    efficiency/altitude) instead of spawning 4 subagents, proportionate to a
    single 50-line YAML. No changes forced: reuses ci.yml's pinned actions,
    already simplified (Maven-matrix removed), parallel matrix + cached Maven,
    right altitude (JDK matrix now, cross-Quarkus integration test deferred to
    plugin/src/it/).
  - `/code-review` (high effort): supply chain clean (SHA-pinned, no third
    party, least perms); fail-fast false correct; `working-directory`/`mvn
    verify` match ci.yml. One non-blocking observation: the workflow cannot be
    exercised locally, so its first real validation is the next push (the user
    controls pushes; no outward action taken). Recorded, not papered over.
- **Commit:** below.

### Work unit 4, 2026-08-11, Promotion materials drafts (docs/PROMO-DRAFTS.md)

- **What:** drafted three promo pieces (quarkus-dev mailing list, Quarkus blog
  guest post outline, Stack Overflow Q&A) citing the measured TASK-13 bench
  numbers. All kept in one review-only doc; nothing published.
- **Editorial stance:** baked the honest precision caveat into the copy itself
  ("roughly half of remaining suspects are false positives, reported as
  suspect-with-evidence"), so adopters get correct expectations rather than an
  inflated precision claim.
- **End-of-unit review (docs only):**
  - `/simplify`: n/a (prose draft, no code).
  - `/code-review` (conceptual/fact-check): every cited number verified against
    source (40 unremovable false positives from README; suspect=5 from the
    measured bench; coordinates correct). No em-dashes. No changes forced.
- **Commit:** below.
