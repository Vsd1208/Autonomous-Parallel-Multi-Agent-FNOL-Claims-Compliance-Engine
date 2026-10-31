#!/usr/bin/env bash
# Runs every demo scenario against a running stack and checks the HTTP status, claim status,
# WHY-panel decision, error code, intake flags and gate flags. Exits non-zero on any mismatch.
#
# Needs: bash, curl, awk, sed, grep. Start the stack with mock vision first:
#   docker compose --env-file .env.demo up -d --build
#
# Each scenario gets a fresh random incident date 1-28 days ago (a scenario can set its own range with
# "daysAgoMin" / "daysAgoMax"), so the script can be re-run without tripping the duplicate check (a
# DUPLICATE_FNOL on a first submission is retried with another date). Most losses are reported within
# days; a much later report is one of the details the fraud anomaly score weighs (scenario 10).
set -uo pipefail

API_URL="${API_URL:-http://localhost:8080}"
# Internal intake needs the adjuster token (.env.demo sets demo-adjuster-token). Policyholders use the portal.
ADJUSTER_TOKEN="${ADJUSTER_TOKEN:-demo-adjuster-token}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FAILURES=0
PASSES=0

days_ago() {
  date -d "-$1 days" +%F 2>/dev/null || date -v-"$1"d +%F
}

# Prints the "payload" object of a scenario file with its incidentDate replaced.
payload_with_date() {
  awk '/"payload": \{/{flag=1; print "{"; next} flag && /^  \}/{print "}"; exit} flag' "$1" \
    | sed -E "s/\"incidentDate\": \"[0-9-]+\"/\"incidentDate\": \"$2\"/"
}

expected() {   # expected <file> <key>  ->  value of "key": value (string or number), or empty
  grep -oE "\"$2\": (\"[^\"]*\"|[0-9]+)" "$1" | head -1 | sed -E 's/^"[^"]+": "?([^"]*)"?$/\1/'
}

json_string() {   # first "key":"value" in a JSON response
  grep -oE "\"$2\":\"[^\"]*\"" <<<"$1" | head -1 | sed -E 's/.*:"([^"]*)"/\1/'
}

post() {   # post <json>  ->  sets HTTP_CODE and BODY
  local out
  out=$(curl -s --max-time 40 -w $'\n%{http_code}' -X POST "$API_URL/api/v1/fnol/intake" \
    -H "Content-Type: application/json" -H "X-Adjuster-Token: $ADJUSTER_TOKEN" -d "$1")
  HTTP_CODE="${out##*$'\n'}"
  BODY="${out%$'\n'*}"
}

check() {   # check <scenario> <what> <expected> <actual>
  if [[ -z "$3" ]]; then return; fi
  if [[ "$3" == "$4" ]]; then
    PASSES=$((PASSES + 1))
  else
    FAILURES=$((FAILURES + 1))
    echo "  FAIL [$1] $2: expected '$3', got '$4'"
  fi
}

# Posts a scenario with a fresh date; on a date collision (409 on first submit) tries another date.
submit_fresh() {
  local file="$1" attempt lo hi
  lo=$(expected "$file" daysAgoMin); lo=${lo:-1}
  hi=$(expected "$file" daysAgoMax); hi=${hi:-28}
  for attempt in 1 2 3 4 5 6 7 8 9 10; do
    DATE=$(days_ago $((lo + RANDOM % (hi - lo + 1))))
    PAYLOAD=$(payload_with_date "$file" "$DATE")
    post "$PAYLOAD"
    if [[ "$HTTP_CODE" != "409" ]]; then return; fi
  done
}

if ! curl -s --max-time 5 "$API_URL/api/v1/health" | grep -q UP; then
  echo "API at $API_URL is not up. Start it with: docker compose --env-file .env.demo up -d --build"
  exit 2
fi

for file in "$DIR"/scenario_*.json; do
  name=$(basename "$file" .json)
  echo "== $name"
  submit_fresh "$file"

  check "$name" "HTTP status" "$(expected "$file" expectedStatus)" "$HTTP_CODE"
  check "$name" "error" "$(expected "$file" expectedError)" "$(json_string "$BODY" error)"
  check "$name" "claim status" "$(expected "$file" expectedClaimStatus)" "$(json_string "$BODY" status)"
  check "$name" "decision" "$(expected "$file" expectedDecision)" "$(json_string "$BODY" decision)"

  flag=$(expected "$file" expectedInputFlag)
  if [[ -n "$flag" ]]; then
    if grep -qE "\"inputFlags\":\[[^]]*\"$flag\"" <<<"$BODY"; then PASSES=$((PASSES + 1));
    else FAILURES=$((FAILURES + 1)); echo "  FAIL [$name] inputFlags: expected $flag"; fi
  fi

  gate=$(expected "$file" expectedGateFlag)
  if [[ -n "$gate" ]]; then
    if grep -qE "\"gateFlags\":\[[^]]*\"$gate\"" <<<"$BODY"; then PASSES=$((PASSES + 1));
    else FAILURES=$((FAILURES + 1)); echo "  FAIL [$name] gateFlags: expected $gate"; fi
  fi

  dup=$(expected "$file" expectedDuplicateStatus)
  if [[ -n "$dup" ]]; then
    post "$PAYLOAD"
    check "$name" "duplicate HTTP status" "$dup" "$HTTP_CODE"
    check "$name" "duplicate error" "$(expected "$file" expectedDuplicateError)" "$(json_string "$BODY" error)"
  fi
done

echo
echo "Checks passed: $PASSES, failed: $FAILURES"
[[ "$FAILURES" -eq 0 ]]
