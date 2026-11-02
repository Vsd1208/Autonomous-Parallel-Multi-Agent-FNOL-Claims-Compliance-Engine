#!/usr/bin/env bash
# Runs every demo scenario against a running stack and checks the HTTP status, claim status,
# WHY-panel decision, error code, intake flags and gate flags. Exits non-zero on any mismatch.
#
# Needs: bash, curl, awk, sed, grep. Start the stack with mock vision first:
#   docker compose --env-file .env.demo up -d --build
#
# Two passes (INTAKE=direct, claims-system or both; default both):
#   direct         POST /fnol/intake: the engine analyses the loss, then files the claim, and answers with the result.
#   claims-system  POST /fnol/report: the loss is filed in the claims system first, which announces it; the engine
#                  analyses it in the background and writes the results back. The script waits for the analysis,
#                  then checks the same outcomes. A failed analysis is not an HTTP error here: the claim is filed
#                  and stays in review ("expectedEventClaimStatus"), and the claims system's claim is checked too.
#                  Then the claims system's side of decisions: the queue is read from it; a decision through the
#                  workspace reaches it; a finished claim refuses another; a decision made directly in it reaches the
#                  engine's feedback loop.
#
# Each scenario gets a fresh random incident date 1-28 days ago (a scenario can set its own range with
# "daysAgoMin" / "daysAgoMax"), so the script can be re-run without tripping the duplicate check (a
# DUPLICATE_FNOL on a first submission is retried with another date). Most losses are reported within
# days; a much later report is one of the details the fraud anomaly score weighs (scenario 10).
set -uo pipefail

API_URL="${API_URL:-http://localhost:8080}"
# The claims system (mock), for checking what was written back onto its claim.
CC_URL="${CC_URL:-http://localhost:9090}"
INTAKE="${INTAKE:-both}"
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

post() {   # post <json> [path]  ->  sets HTTP_CODE and BODY
  local out
  out=$(curl -s --max-time 40 -w $'\n%{http_code}' -X POST "$API_URL${2:-/api/v1/fnol/intake}" \
    -H "Content-Type: application/json" -H "X-Adjuster-Token: $ADJUSTER_TOKEN" -d "$1")
  HTTP_CODE="${out##*$'\n'}"
  BODY="${out%$'\n'*}"
}

api_get() {   # api_get <path>  ->  body (empty on an error status)
  curl -sf --max-time 10 -H "X-Adjuster-Token: $ADJUSTER_TOKEN" "$API_URL$1"
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

has_flag() {   # has_flag <scenario> <field> <flag> <json>: the flag in a JSON array or a comma-separated string
  if [[ -z "$3" ]]; then return; fi
  if grep -qE "\"$2\":(\[[^]]*\"$3\"|\"([^\"]*,)?$3(,[^\"]*)?\")" <<<"$4"; then PASSES=$((PASSES + 1));
  else FAILURES=$((FAILURES + 1)); echo "  FAIL [$1] $2: expected $3"; fi
}

# Posts a scenario with a fresh date; on a date collision (409 on first submit) tries the next date, so every date
# in the range is tried once (from a random start) before giving up.
submit_fresh() {   # submit_fresh <file> [path]
  local file="$1" k lo hi n start
  lo=$(expected "$file" daysAgoMin); lo=${lo:-1}
  hi=$(expected "$file" daysAgoMax); hi=${hi:-28}
  n=$((hi - lo + 1)); start=$((RANDOM % n))
  for ((k = 0; k < n; k++)); do
    DATE=$(days_ago $((lo + (start + k) % n)))
    PAYLOAD=$(payload_with_date "$file" "$DATE")
    post "$PAYLOAD" "${2:-}"
    if [[ "$HTTP_CODE" != "409" ]]; then return; fi
  done
  echo "  Every loss date $lo-$hi days ago is already used on this policy; reset the demo database to re-run."
}

check_duplicate() {   # check_duplicate <scenario> <file> [path]: the same report again is refused
  local dup
  dup=$(expected "$2" expectedDuplicateStatus)
  if [[ -n "$dup" ]]; then
    post "$PAYLOAD" "${3:-}"
    check "$1" "duplicate HTTP status" "$dup" "$HTTP_CODE"
    check "$1" "duplicate error" "$(expected "$2" expectedDuplicateError)" "$(json_string "$BODY" error)"
  fi
}

run_direct() {
  echo "### Direct intake: the engine analyses, then files the claim"
  for file in "$DIR"/scenario_*.json; do
    name=$(basename "$file" .json)
    echo "== $name"
    submit_fresh "$file"

    check "$name" "HTTP status" "$(expected "$file" expectedStatus)" "$HTTP_CODE"
    check "$name" "error" "$(expected "$file" expectedError)" "$(json_string "$BODY" error)"
    check "$name" "claim status" "$(expected "$file" expectedClaimStatus)" "$(json_string "$BODY" status)"
    check "$name" "decision" "$(expected "$file" expectedDecision)" "$(json_string "$BODY" decision)"
    has_flag "$name" inputFlags "$(expected "$file" expectedInputFlag)" "$BODY"
    has_flag "$name" gateFlags "$(expected "$file" expectedGateFlag)" "$BODY"
    check_duplicate "$name" "$file"
  done
}

run_claims_system() {
  echo "### Through the claims system: filed there first, analysed from its event, results written back"
  local file name id status row cc_status want i
  for file in "$DIR"/scenario_*.json; do
    name=$(basename "$file" .json)
    echo "== $name (claims system)"
    submit_fresh "$file" /api/v1/fnol/report
    check "$name" "report HTTP status" "200" "$HTTP_CODE"
    check "$name" "filed status" "SUBMITTED" "$(json_string "$BODY" status)"
    id=$(json_string "$BODY" claimId)
    [[ -z "$id" ]] && continue
    case "$name" in
      scenario_1_*) DECIDE_IN_WORKSPACE="$id" ;;
      scenario_2_*) DECIDE_IN_CLAIMS_SYSTEM="$id" ;;
    esac

    row=""
    for i in $(seq 1 80); do   # up to 40 s: the analysis deadline is 10 s
      row=$(api_get "/api/v1/fnol/status/$id")
      [[ -n "$row" && "$(json_string "$row" status)" != "ANALYSIS_PENDING" ]] && break
      sleep 0.5
    done
    if [[ -z "$row" || "$(json_string "$row" status)" == "ANALYSIS_PENDING" ]]; then
      FAILURES=$((FAILURES + 1)); echo "  FAIL [$name] no analysis for $id after 40 s"; continue
    fi

    want=$(expected "$file" expectedEventClaimStatus)
    check "$name" "claim status" "${want:-$(expected "$file" expectedClaimStatus)}" "$(json_string "$row" status)"
    check "$name" "decision" "$(expected "$file" expectedDecision)" \
      "$(json_string "$(api_get "/api/v1/fnol/$id/explanation")" decision)"
    has_flag "$name" inputFlags "$(expected "$file" expectedInputFlag)" "$row"
    has_flag "$name" gateFlags "$(expected "$file" expectedGateFlag)" "$row"

    # Written back: the claims system's claim has left SUBMITTED (routing set, or held for review).
    cc_status=""
    for i in $(seq 1 20); do
      cc_status=$(json_string "$(curl -s --max-time 5 "$CC_URL/cc/claims/$id")" status)
      [[ -n "$cc_status" && "$cc_status" != "SUBMITTED" ]] && break
      sleep 0.5
    done
    if [[ -n "$cc_status" && "$cc_status" != "SUBMITTED" ]]; then PASSES=$((PASSES + 1));
    else FAILURES=$((FAILURES + 1)); echo "  FAIL [$name] claims system claim $id not updated (status '$cc_status')"; fi

    check_duplicate "$name" "$file" /api/v1/fnol/report
  done
  check_decisions
}

# The claims system owns the queue and the decision; the engine follows.
check_decisions() {
  local fb i code
  echo "== decisions (claims system)"
  check "decisions" "queue source" "CLAIMS_SYSTEM" \
    "$(json_string "$(api_get "/api/v1/fnol/queue?status=HUMAN_REVIEW&size=1")" source)"

  if [[ -n "${DECIDE_IN_WORKSPACE:-}" ]]; then
    post '{"decision":"APPROVED_BY_ADJUSTER","adjusterId":"ADJ-DEMO","notes":"Demo approval"}' \
      "/api/v1/fnol/$DECIDE_IN_WORKSPACE/review"
    check "decisions" "workspace decision HTTP status" "200" "$HTTP_CODE"
    check "decisions" "claims system status after it" "OPEN" \
      "$(json_string "$(curl -s --max-time 5 "$CC_URL/cc/claims/$DECIDE_IN_WORKSPACE")" status)"
    post '{"decision":"REJECTED_BY_ADJUSTER","adjusterId":"ADJ-DEMO"}' "/api/v1/fnol/$DECIDE_IN_WORKSPACE/review"
    check "decisions" "second decision HTTP status" "409" "$HTTP_CODE"
  fi

  if [[ -n "${DECIDE_IN_CLAIMS_SYSTEM:-}" ]]; then
    code=$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' -X PUT "$CC_URL/cc/claims/$DECIDE_IN_CLAIMS_SYSTEM" \
      -H "Content-Type: application/json" \
      -d '{"status":"DENIED","notes":"Not covered","action":"REJECTED_BY_ADJUSTER","adjusterId":"ADJ-IN-CLAIMS-SYSTEM"}')
    check "decisions" "decision in the claims system HTTP status" "200" "$code"
    fb=""
    for i in $(seq 1 40); do   # up to 20 s: its claim.decided event is delivered within a second or so
      fb=$(api_get "/api/v1/ai/feedback" | grep -oE "\"claimId\":\"$DECIDE_IN_CLAIMS_SYSTEM\"")
      [[ -n "$fb" ]] && break
      sleep 0.5
    done
    if [[ -n "$fb" ]]; then PASSES=$((PASSES + 1));
    else FAILURES=$((FAILURES + 1)); echo "  FAIL [decisions] feedback loop did not follow the claims system's decision"; fi
  fi
}

if ! curl -s --max-time 5 "$API_URL/api/v1/health" | grep -q UP; then
  echo "API at $API_URL is not up. Start it with: docker compose --env-file .env.demo up -d --build"
  exit 2
fi

if [[ "$INTAKE" == "direct" || "$INTAKE" == "both" ]]; then
  run_direct
fi
if [[ "$INTAKE" == "claims-system" || "$INTAKE" == "both" ]]; then
  if curl -s --max-time 5 "$API_URL/api/v1/health" | grep -q '"intake":"CLAIMS_SYSTEM"'; then
    echo
    run_claims_system
  else
    echo
    echo "Claims-system pass skipped: the API reports direct intake (set CLAIMS_EVENT_SECRET on both services)."
    [[ "$INTAKE" == "claims-system" ]] && FAILURES=$((FAILURES + 1))
  fi
fi

echo
echo "Checks passed: $PASSES, failed: $FAILURES"
[[ "$FAILURES" -eq 0 ]]
