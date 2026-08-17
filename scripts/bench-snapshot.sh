#!/bin/bash
# TASK-34: machine-checked bench baselines. Runs the mojo over the pinned bench apps and
# diffs each app's extension-suspect list against the committed EXPECTED file. Any drift
# (a rules-engine change that alters real-app verdicts) is an explicit non-zero exit.
#
# Usage:
#   scripts/bench-snapshot.sh                # compare against bench/expected/*.expected
#   scripts/bench-snapshot.sh --update       # rewrite the EXPECTED files (deliberate refresh)
#
# Bench pins (update deliberately; see docs/EXTENSION-USAGE.md "Bench caveat"):
#   super-heroes-fresh     a3f2ce1 (platform 3.38.1)
#   apicurio-registry-fresh 400a3db
#   quarkus-quickstarts    31306c8 (3.38.2)
set -u
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EXPECTED_DIR="$SCRIPT_DIR/../bench/expected"
PLUGIN_GA="io.github.paoloantinori:quarkus-extension-analyzer-maven-plugin:1.0-SNAPSHOT:analyze"
UPDATE=false
[ "${1:-}" = "--update" ] && UPDATE=true

apps=(
  "/private/tmp/super-heroes-fresh/rest-heroes|rest-heroes"
  "/private/tmp/super-heroes-fresh/rest-fights|rest-fights"
  "/private/tmp/quarkus-quickstarts/resteasy-client-quickstart|resteasy-client-quickstart"
  "/private/tmp/quarkus-quickstarts/cache-quickstart|cache-quickstart"
  "/private/tmp/quarkus-quickstarts/security-jwt-quickstart|security-jwt-quickstart"
  "/private/tmp/apicurio-registry-fresh/app|apicurio-app"
)

# Apicurio needs its own module deps installed into the local repo first (one-time cost,
# skipped when the marker artifact is already present).
if ! ls ~/.m2/repository/io/apicurio/apicurio-registry-common/*/*.jar >/dev/null 2>&1; then
  echo "== pre-installing apicurio module deps (one time)"
  (cd /private/tmp/apicurio-registry-fresh && mvn -q install -pl common,config-index/definitions,config-index/runtime,config-index/deployment -Dmaven.test.skip=true -DskipTests) || true
fi

FAILED=0
for entry in "${apps[@]}"; do
  dir="${entry%%|*}"
  name="${entry##*|}"
  if [ ! -d "$dir" ]; then
    echo "MISSING bench app: $dir (clone it per docs/EXTENSION-USAGE.md)"
    FAILED=1
    continue
  fi
  echo "== $name"
  out="$(cd "$dir" && mvn -q compile "$PLUGIN_GA" -Dqea.reportFile=/tmp/qea-bench-$name.json 2>&1)"
  rc=$?
  if [ $rc -ne 0 ]; then
    echo "   analyze FAILED (rc=$rc):"
    echo "$out" | tail -5
    FAILED=1
    continue
  fi
  # extension-suspect GA list, sorted (the durable baseline content)
  actual="$(python3 -c "
import json
r = json.load(open('/tmp/qea-bench-$name.json'))
sus = sorted(d['ga'] for d in r['dependencies'] if d['verdict']=='suspect' and d.get('quarkusExtension'))
print('\n'.join(sus))
")"
  expected_file="$EXPECTED_DIR/$name.expected"
  if $UPDATE; then
    mkdir -p "$EXPECTED_DIR"
    printf '%s\n' "$actual" > "$expected_file"
    echo "   UPDATED $(basename "$expected_file") ($(echo "$actual" | grep -c . || true) suspects)"
    continue
  fi
  if [ ! -f "$expected_file" ]; then
    echo "   NO EXPECTED FILE: run with --update to create it"
    FAILED=1
    continue
  fi
  if [ "$actual" = "$(cat "$expected_file")" ]; then
    echo "   OK ($(echo "$actual" | grep -c . || true) suspects)"
  else
    echo "   DRIFT:"
    diff "$expected_file" <(printf '%s\n' "$actual") | sed 's/^/     /'
    FAILED=1
  fi
done

if $UPDATE; then
  echo "EXPECTED files refreshed; review the diff before committing."
  exit 0
fi
if [ $FAILED -ne 0 ]; then
  echo "BENCH SNAPSHOT: DRIFT OR FAILURE (see above). If the change is intentional, refresh with --update and record why in the work log."
  exit 1
fi
echo "BENCH SNAPSHOT: all apps at baseline."
