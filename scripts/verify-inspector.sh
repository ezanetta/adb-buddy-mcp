#!/usr/bin/env bash
# Verifies the built server with the MCP Inspector CLI.
#
#   ./gradlew shadowJar && scripts/verify-inspector.sh            # protocol checks only
#   scripts/verify-inspector.sh --device                           # also calls tools on a connected device
#
# Checks tools/list and prompts/list under each protocol era the Inspector supports, then
# (with --device) a few tools/call requests. Needs Node.js (npx) and adb.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/build/libs/adb-buddy.jar"
INSPECTOR="@modelcontextprotocol/inspector@2.11.0"
EXPECTED_TOOLS=14
failures=0

[[ -f "$JAR" ]] || { echo "Missing $JAR; run ./gradlew shadowJar first." >&2; exit 1; }

# Preflight: the server exits at once if it cannot start (e.g. adb not found). Show why, instead of
# letting every Inspector check below fail with an opaque "error".
if ! startup="$(java -jar "$JAR" </dev/null 2>&1)"; then
  echo "Server failed to start:" >&2
  echo "$startup" >&2
  exit 1
fi

inspect() {
  npx -y "$INSPECTOR" --cli java -jar "$JAR" -q --format json "$@" 2>/dev/null
}

check() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    printf '  ok    %-44s %s\n' "$name" "$actual"
  else
    printf '  FAIL  %-44s expected %s, got %s\n' "$name" "$expected" "$actual"
    failures=$((failures + 1))
  fi
}

count() { python3 -c "import json,sys; r=json.load(sys.stdin); print(len(r.get('result', r)['$1']))" 2>/dev/null || echo error; }
text() { python3 -c "import json,sys; r=json.load(sys.stdin); r=r.get('result', r); print(('ERROR ' if r.get('isError') else '') + r['content'][0]['text'].splitlines()[0])" 2>/dev/null || echo error; }

# legacy: initialize handshake (2024-11-05 .. 2025-11-25). auto: tries server/discover, falls back.
for era in legacy auto; do
  echo "protocol era: $era"
  check "tools/list" "$EXPECTED_TOOLS" "$(inspect --protocol-era "$era" --method tools/list | count tools)"
  check "prompts/list" "1" "$(inspect --protocol-era "$era" --method prompts/list | count prompts)"
done

# modern: stateless 2026-07-28 (server/discover). kotlin-sdk 0.15.0 does not implement it yet
# (modelcontextprotocol/kotlin-sdk#985), so the expected result is a clean negotiation failure.
# When this check starts failing, the SDK gained support: flip it to the checks above.
echo "protocol era: modern (2026-07-28, not supported by kotlin-sdk 0.15.0)"
modern="$(npx -y "$INSPECTOR" --cli java -jar "$JAR" -q --protocol-era modern --method tools/list 2>&1 | grep -c 'did not offer pinned protocol version 2026-07-28')"
check "negotiation refused, no crash" "1" "$modern"

echo "schema portability (--strict)"
if npx -y "$INSPECTOR" --cli java -jar "$JAR" -q --method tools/list --strict >/dev/null 2>&1; then
  check "tools/list --strict" "pass" "pass"
else
  check "tools/list --strict" "pass" "exit $?"
fi

if [[ "${1:-}" == "--device" ]]; then
  echo "tools/call against a connected device"
  check "list_devices" "1" "$(inspect --method tools/call --tool-name list_devices | text | grep -c 'device(s):')"
  check "get_current_activity" "1" "$(inspect --method tools/call --tool-name get_current_activity | text | grep -c '^Foreground on')"
  check "dump_ui_hierarchy" "1" "$(inspect --method tools/call --tool-name dump_ui_hierarchy | text | grep -c '^Screen ')"
  check "tap off screen is a tool error" "1" \
    "$(inspect --method tools/call --tool-name tap --tool-arg x=99999 --tool-arg y=0 | text | grep -c '^ERROR')"
fi

if (( failures > 0 )); then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all checks passed"
