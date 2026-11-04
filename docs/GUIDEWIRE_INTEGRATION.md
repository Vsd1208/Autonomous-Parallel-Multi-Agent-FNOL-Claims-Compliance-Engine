# Guidewire integration status

**Nothing here has run against a real Guidewire tenant.** This project has none ([the request to send](guidewire-sandbox-request.md)). How the design sits on ClaimCenter, and the mock's contract, are in [guidewire-integration.md](guidewire-integration.md). This page says what exists, and what each part rests on.

| Term | Meaning |
|---|---|
| **Mock** | A working stand-in in this repository, `fnol-mock-guidewire`. The demo runs on it |
| **Simulator** | Responses written by this project in `GuidewireSimulatorContractTest`. A pass shows the client handles them, not that a tenant answers this way |
| **Documented** | The path appears in Guidewire's public documentation |
| **Assumed** | Field names and shapes this project expects, unconfirmed |

## Selecting mock or cloud

Domain code sees only two interfaces, `ClaimCenterClient` and `PolicyCenterClient`. An implementation is chosen by Spring profile, and nothing switches on its own:

| Profile | ClaimCenter | PolicyCenter |
|---|---|---|
| none (default) | Mock over HTTP | Mock over HTTP |
| `guidewire-cloud-claims` | `GuidewireCloudClaimCenterClient` | unchanged |
| `guidewire-cloud-policy` | unchanged | `GuidewireCloudPolicyCenterClient` |

A cloud client with missing settings stops startup, naming them. `FNOL_ENVIRONMENT=production` refuses to start on either mock.

## ClaimCenter operations

| Operation | Mock | Cloud client | Rests on |
|---|---|---|---|
| Create draft, submit | Yes | Yes | Documented; body assumed |
| Get claim | Yes | Yes | Documented; fields assumed |
| Add activity | Yes | Yes | Documented; fields assumed |
| Add note | Yes | Yes | Path and fields assumed |
| Retrieve FNOL | Yes | **Refuses** | No mapping |
| Update claim, status | Yes | **Refuses** | No mapping |
| Record a decision | Yes | **Refuses** | No mapping: the design question below |
| Create or read exposure | Yes | **Refuses** | Needs incident and claimant records this client does not create |
| Upload a document | Yes | **Refuses** | No mapping |
| Work queue listing | Yes | **Refuses** | No mapping |

Refusing is deliberate: `UnsupportedOperationException`, not a guess. **So the cloud claims client cannot yet carry the full flow.**

## PolicyCenter operations

| Operation | Mock | Cloud client | Rests on |
|---|---|---|---|
| Find a policy: status, dates, product, jurisdiction, cancellation, endorsements | Yes | Yes | `POST /policy/v1/search/policies` documented; **fields assumed** |
| Coverages, limits, deductibles | Yes | Only with `guidewire.cloud.coverages-path` set; refuses otherwise | **No path found in public documentation**: read the tenant's `swagger.json`. Answering "no coverage" instead would be wrong |
| Prior claims | Yes | Yes | `POST /claim/v1/search/claims-v2` documented; fields assumed |

- A tenant's product codes are its own, so `guidewire.cloud.line-codes` maps them (`PersonalAuto:AUTO`). An unmapped product is unknown, never guessed.
- A prior claim with no loss date fails, rather than undercount claim frequency.
- Absent endorsements count as none. **That could overstate coverage if a tenant keeps them elsewhere; check in the sandbox.**
- Two policies for one number is an error, with no guess between them.

## Authentication

`GuidewireTokenProvider`: OAuth2 client credentials. The request shape is assumed.

| Behaviour | Tested in the simulator |
|---|---|
| Reuses a token until 30 s before expiry, then replaces it | Yes |
| A rejected token is replaced and the call retried once; rejected twice, it stops | Yes |
| Token endpoint failing gives a clear failure that the inbox retries | Yes |
| Secrets and tokens never reach logs or messages | Yes, with captured output |
| Missing configuration stops startup | Yes |

Not done: certificate-based credentials, token sharing across instances (each holds its own), and scopes, which depend on the tenant.

## Failures

| Tenant answers | Client does |
|---|---|
| 429 with a short `Retry-After` (at most 2 s) | Waits once, retries |
| 429 again, or long, missing or in date form | `GuidewireUnavailableException`, `RATE_LIMITED`, without waiting |
| 5xx, or no connection | `GuidewireUnavailableException`, **no retry here**, because a write may have happened. The inbox decides |
| Other 4xx | Passed on unchanged, so a duplicate's 409 and a 404 keep their meaning |
| Unknown fields | Ignored; a later release's additions do not break it |
| Malformed date | Fails, rather than read as "no date" |

Each call carries `X-Correlation-ID`. Whether a tenant records this header is unknown.

## App Events (design; not connected)

The engine takes signed events from the mock today. For real App Events:

| Concern | Today | For a tenant |
|---|---|---|
| Authentication | HMAC of the exact body; refused unsigned | **Replace** with what the tenant's webhook provides |
| Schema validation | Malformed is refused before anything is stored | **Replace** the parser; the real format has not been seen |
| Idempotency | Unique event ID, content hash | Keep. Confirm the real event's ID is stable across deliveries |
| Versioning | Unknown types are ignored | Add a version check once the format is known. **Not built** |
| Inbox, retry, dead letter | Built and tested on PostgreSQL | Keep ([details](DISTRIBUTED_PROCESSING.md)) |

## Data mapping

**Every Guidewire field here is assumed until a sandbox confirms it.** The first column is this project's model.

| This project | ClaimCenter concept | Direction | Status |
|---|---|---|---|
| Claim: `claimId`, `claimNumber` | Claim: an internal ID and a claim number are different things | Read, write | Documented distinction; fields assumed |
| Loss date, description | Claim's loss date, description | Write | Assumed |
| Damage assessment | Incident (vehicle or property), with damage description | Write | **Not built.** Currently a note and a document |
| Exposure: coverage, line | Exposure, needing an incident and a claimant | Write | Mock only |
| Coverage: type, limit, deductible | Policy's coverages | Read | Path unknown |
| Claimant | Contact, with a role on the claim | Read | **Not built in the cloud client.** Claimant details stay out of events |
| Review flags | Activity | Write | Client built, fields assumed |
| AI advisory | Note | Write | Client built, fields assumed |
| WHY panel, evidence list, letter | Document | Write | Mock only |
| Recommended reserve | Reserve on an exposure, by cost type. **Only a person's decision posts one** | Write | Mock only. Authority limits must be checked on a tenant |
| Routing: work queue | Assignment: group or queue, and user | Write | Mock only. Real assignment rules differ |
| Adjuster's decision | **Open:** where a decision lives in ClaimCenter | Both | Design question for the sandbox |

## Tests

| Test | Against | Shows |
|---|---|---|
| `GuidewireSimulatorContractTest`, `GuidewireCloudClaimCenterClientTest` | Simulator | Client behaviour: errors, authentication, rate limits, tolerance of new fields |
| `GuidewireClientContractTest` | The mock, on a random port | What the engine relies on, in every build. Can point at another server by environment variable |
| `tools/guidewire-check/check_sandbox.py` | **A real tenant, once one exists** | What a tenant says to each assumption. **Never run** |

## First day in a sandbox

1. Run `check_sandbox.py`, and correct this page and the clients wherever they differ.
2. Read `swagger.json`, and set the coverages path and line codes.
3. Settle where a decision and a reserve authority live: the human gate depends on it.
4. Map the operations that refuse today.
5. Subscribe to real App Events and replace the parser.
