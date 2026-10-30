#!/usr/bin/env bash
# Submits the same FNOL twice: the first is accepted (200), the second is a duplicate (409).
# Uses a fresh incident date each run so the script can be repeated.
set -uo pipefail

URL="${API_URL:-http://localhost:8080}/api/v1/fnol/intake"
# Internal intake needs the adjuster token (.env.demo sets demo-adjuster-token).
TOKEN="${ADJUSTER_TOKEN:-demo-adjuster-token}"
DATE=$(date -d "-$((31 + RANDOM % 120)) days" +%F 2>/dev/null || date -v-"$((31 + RANDOM % 120))"d +%F)

PAYLOAD='{
  "policyNumber": "POL-AUTO-112233",
  "incidentDate": "'"$DATE"'",
  "state": "CA",
  "description": "Vehicle involved in a collision. Front bumper damaged.",
  "claimantName": "Avery Johnson",
  "claimantDob": "01/01/1990",
  "claimantSsn": "123-45-6789",
  "evidenceUris": ["mock://photo/normal-damage.jpg"],
  "channel": "API"
}'

echo "First submission (incident date $DATE):"
curl -s -o /dev/null -w "HTTP Status: %{http_code}\n" -X POST "$URL" -H "Content-Type: application/json" -H "X-Adjuster-Token: $TOKEN" -d "$PAYLOAD"

echo "Second submission (duplicate):"
curl -s -w "\nHTTP Status: %{http_code}\n" -X POST "$URL" -H "Content-Type: application/json" -H "X-Adjuster-Token: $TOKEN" -d "$PAYLOAD"
