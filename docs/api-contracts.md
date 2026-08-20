# API Contracts

Platform APIs:

- `GET /api/v1/health`
- `POST /api/v1/fnol/intake`
- `GET /api/v1/fnol/status/{claimId}`

Mock Guidewire APIs:

- `GET /pc/policies/{policyNumber}`
- `GET /pc/policies/{policyNumber}/coverages`
- `GET /pc/policies/{policyNumber}/history`
- `POST /cc/claims`
- `GET /cc/claims/{claimId}`

Errors use `ApiError` and do not expose stack traces, secrets, PII, or database internals.
