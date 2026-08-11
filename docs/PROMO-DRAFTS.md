# Promotion drafts (for review; do NOT publish as-is)

These are drafts the maintainer can edit and post. They cite the measured
TASK-13 bench numbers and the honest precision picture from the suspect triage
(docs/SUSPECT-TRIAGE.md). Everything here is the maintainer's to send; nothing
has been published.

Key facts to keep consistent across all drafts (from docs/M2-VALIDATION.md,
docs/SECOND-BENCH.md, docs/_bench-runs/, 2026-08-11):

- The tool: `quarkus-extension-analyzer`, a Maven plugin that classifies each
  declared Quarkus extension as used-bytecode / used-config / used-capability /
  suspect, unlike bytecode-only analyzers (maven-dependency-plugin, DepClean)
  that produce large false-positive lists on Quarkus apps.
- Coordinates: `io.github.paoloantinori:quarkus-extension-analyzer-maven-plugin`
  (1.0-SNAPSHOT until first release).
- Validation: two real apps, Apicurio Registry `app` (~24 extensions) and
  Quarkus super-heroes `rest-fights` (~23 extensions). On Apicurio, the standard
  `dependency:analyze` scores ~40 false positives; this tool reports 5 extension
  suspects.
- Honest precision note: of those suspects, roughly half are false positives the
  tool still misses (extensions used via annotation/injection whose types live
  in shared jars). The tool reports them as "suspect" with an evidence trail
  rather than silently hiding the gap, and explicitly composes with
  maven-dependency-plugin and DepClean (generates their ignore-list fragments)
  instead of replacing them.

## 1. quarkus-dev mailing list (short, technical)

Subject: A Maven plugin for Quarkus-aware dependency-usage analysis

Hi all,

I'd like to share a small Maven plugin that classifies declared Quarkus
extensions as used or suspect by combining three signals a bytecode-only
analyzer can't see: config-root matching, bytecode reference, and capability
requirements. It's report-only and, opt-in, generates ignore-list fragments
compatible with maven-dependency-plugin and DepClean, so it composes with
existing tooling instead of replacing it.

Motivation: dependency:analyze on a real Quarkus app drowns in false "unused
declared" warnings because extensions wired at augmentation time have zero
bytecode references. On Apicurio Registry's app module (~24 extensions) the
standard report gives ~40 unremovable false positives; this plugin narrows that
to 5 extension suspects with an evidence trail each.

It's validated on two apps (Apicurio Registry app, Quarkus super-heroes
rest-fights), Apache-2.0, with a design doc. Not a Quarkiverse extension: it's
build tooling, so it ships standalone. Honest limitation: roughly half the
remaining suspects are extensions used via shared jars (e.g. validation,
scheduling) that the tool deliberately won't credit to avoid ambiguity; it
reports those as suspect-with-evidence rather than guessing.

Coordinates and usage:
mvn io.github.paoloantinori:quarkus-extension-analyzer-maven-plugin:1.0-SNAPSHOT:analyze

Feedback very welcome, especially on the shared-jar attribution trade-off.

## 2. Quarkus blog guest post (longer, narrative)

Working title: "Why dependency:analyze lies to Quarkus developers, and a plugin
to fix it."

Outline (fill prose before sending):

- The problem, with the Apicurio Registry numbers: ~40 false positives on one
  module, several artifacts in both unused-declared and used-undeclared lists.
- Why it happens: Quarkus extensions are wired at build augmentation; the
  bytecode-only premise of standard analyzers is wrong for them.
- The three signals: config roots (e.g. db-kind selects a JDBC driver),
  bytecode (incl. member annotations, the TASK-12 widening), and capabilities.
- Compose, don't replace: report-only plus generated ignore fragments for
  maven-dependency-plugin and DepClean.
- Honest limits: the shared-jar attribution trade-off (scheduler, validation),
  shown as suspect-with-evidence; the tool never silently upgrades a verdict on
  ambiguous evidence.
- Try it: coordinates + the one-line usage; link to the design doc and the two
  bench reports.

## 3. Stack Overflow (Q&A style, for the `[quarkus]` tag)

Title: "How do I avoid false 'unused declared dependency' warnings in a Quarkus
project?"

Body (draft): Standard `maven-dependency-plugin:analyze` reports many Quarkus
extensions as unused because they are wired at build time and have no bytecode
references. `quarkus-extension-analyzer` is a Maven plugin that classifies each
declared extension using config-root matching, bytecode reference, and
capability requirements, so load-bearing extensions (JDBC drivers selected by
`db-kind`, health/metrics/otel toggled by config, etc.) are correctly seen as
used. It's report-only and can emit ignore-list fragments for
maven-dependency-plugin and DepClean. Run:

mvn io.github.paoloantinori:quarkus-extension-analyzer-maven-plugin:1.0-SNAPSHOT:analyze

(Answer your own question with this once the plugin is released to Central; link
the repo and the design doc.)

## Notes for the maintainer

- Replace `1.0-SNAPSHOT` with the real release version once published to Central.
- The ~40-false-positives and 5-suspects numbers are measured (Apicurio app,
  2026-08-11); recheck against the latest bench if you re-run before posting.
- The "roughly half of suspects are false positives" line is deliberately
  honest; do not soften it into an inflated precision claim, that would mislead
  adopters and undercut trust.
