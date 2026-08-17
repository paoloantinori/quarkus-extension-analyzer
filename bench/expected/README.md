# Bench expected files (TASK-34)

One `.expected` file per bench app: the sorted list of extension-suspect GAs
the mojo must produce on that app at the pinned state. Generated and
verified by `scripts/bench-snapshot.sh --update`; refresh is a deliberate,
documented act (record why in the work log), never a silent one.

## Bench pins

| App | Path | Commit |
|---|---|---|
| rest-heroes, rest-fights | /private/tmp/super-heroes-fresh | a3f2ce1 (platform 3.38.1) |
| apicurio app | /private/tmp/apicurio-registry-fresh | 400a3db |
| quickstarts | /private/tmp/quarkus-quickstarts | 31306c8 (3.38.2) |
| keycloak quarkus/runtime | /private/tmp/keycloak-267 | 6c73e30 = 26.7.0 (Quarkus 3.33.2.1) |

## Provenance of the current files

The first five files below were hand-written from the VERIFIED session runs
(2026-08-17, logs in /tmp/qea-reval: drift-*.log, fights-mojo-final.json,
restclient-mojo-final.json) because the safety classifier blocked shell
execution while they were created. The first `--update` run MUST regenerate
every file and show no diff for these five; security-jwt-quickstart.expected
is created by that run (its full suspect list was never printed this session).

## Procedure

- Every rules-engine change: run `scripts/bench-snapshot.sh` (CI or local);
  drift fails the run.
- Intentional change: `--update`, review the diff, commit with the reason.
- Bench app platform bump: re-pin the commit above, `--update`, expect drift
  in the Quarkus-provided rows only.
