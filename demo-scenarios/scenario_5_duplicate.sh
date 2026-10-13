
#!/bin/bash

URL="http://localhost:8080/api/v1/fnol/intake"

PAYLOAD='{
  "policyNumber": "POL-AUTO-112233",
  "incidentDate": "2026-09-27",
  "state": "CA",
  "description": "Vehicle involved in a collision. Front bumper damaged.",
  "claimantName": "Demo User",
  "claimantDob": "01/01/1990",
  "claimantSsn": "123-45-6789",
  "evidenceUris": [
    "https://example.com/normal-damage.jpg"
  ]
}'

echo "First submission:"
curl -s -w "\nHTTP Status: %{http_code}\n" \
  -X POST "$URL" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD"

echo ""
echo "Second submission (duplicate):"
curl -s -w "\nHTTP Status: %{http_code}\n" \
  -X POST "$URL" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD"