# Agent Architecture & Provider Design

## Overview
Most evaluations follow the `FNOLAgent<I, O>` contract (`O execute(I input)`). Each agent has one responsibility, typed input and output records, and is testable without a Spring context. Anything that would differ between a prototype and production (vision model, deadline rules, coverage rules, subrogation weights) sits behind a provider interface.

| Agent | Where it runs | Provider | Output |
|---|---|---|---|
| `VisionDamageAgent` | Branch A | `VisionProvider`: `GeminiVisionProvider` or `MockVisionProvider` | `DamageAssessment` |
| `PolicyValidatorAgent` | Branch A | `PolicyCenterClient`, `CoverageRuleProvider` | `PolicyValidationResult` |
| `ReserveCalculatorAgent` | Branch A | — | `ReserveResult` |
| `PiiRedactionAgent` | Branch B | — | `PIIResult` |
| `StatutoryDeadlineAgent` | Branch B | `DeadlineRuleProvider` | `DeadlineResult` |
| `SubrogationScorerAgent` | Branch B (liability + compliance notes), finalised at the gate with Branch A's severity | `SubrogationScorer` | `SubrogationResult` |
| `AuditTrailAgent` | Gate, failed intakes, reviews | — | `AuditRecord` |

Before the fan-out, `ClaimHistoryEnricher` builds the claim-history context and `IntakeReviewService` (Human Review Point 1, in `fnol-orchestrator`) raises intake flags.

---

## Branch A — Operational Loss Triage

### `VisionDamageAgent`
Extracts damage type, severity, estimated repair cost, confidence, photo quality and per-type probabilities from the first evidence URI.

**`GeminiVisionProvider`** (active when `gemini.vision.enabled=true`, the default)
- Calls Google Gemini (`gemini.vision.model`, default `gemini-2.5-flash`) with a structured-JSON prompt; strips markdown fences from the reply.
- Uses its own `RestTemplate` bean (`geminiRestTemplate`, injected by qualifier): 3 s connect, `gemini.vision.read-timeout-seconds` (default 8) read — below the 10 s orchestrator deadline so the fallback can still be returned.
- Sends the key in the `x-goog-api-key` header, never in the URL; logs only scheme and host of evidence URIs.
- Loads evidence through **`EvidenceImageLoader`**, which accepts only `data:image/(jpeg|png|webp|gif);base64` (≤ 5 MB) and `https://` URLs whose host is in `fnol.evidence.allowed-hosts` (default empty = no downloads), resolve only to public addresses (no loopback, link-local such as 169.254.169.254, private, carrier-grade NAT or unique-local ranges), answer 200 without redirect, send `image/*` and ≤ 5 MB. `http:`, `file:` and `mock:` URIs are refused.
- **Fallback** (confidence 0.60, which always routes to a human): no evidence (`NO_IMAGE`), refused evidence (`EVIDENCE_REJECTED`), no API key (`NO_API_KEY`), Gemini error (`API_ERROR`) or unparseable reply (`PARSE_ERROR`).

**`MockVisionProvider`** (active when `gemini.vision.enabled=false`; tests and `.env.demo`)
Keywords in the first evidence URI: `blurry` → confidence 0.61; `severe` → MULTIPLE, $24,000, SEVERE; `water` → WATER_DAMAGE, $4,200; `slow` → sleeps 12 s (exercises the orchestrator deadline; interruptible); anything else → FRONT_BUMPER, MODERATE, $4,200, confidence 0.91. No evidence → the 0.60 fallback.

### `PolicyValidatorAgent`
Reads the policy and coverages from PolicyCenter. Covered = policy found, status `ACTIVE`, today within the policy term, and a coverage accepted by the line-of-business rule from **`CoverageRuleProvider`**. `SyntheticCoverageRuleProvider` (prototype values): AUTO → COLLISION or COMPREHENSIVE; HOME → DWELLING or PERSONAL_PROPERTY; other lines → nothing qualifies. The matched rule and coverage are written into `reasoning`. `ValidationGuardrail` turns "not covered" into `CoverageNotEligibleException` (HTTP 502, saved as a REJ- claim).

### `ReserveCalculatorAgent`
`recommendedReserve = estimatedDamage × 1.15`, `BigDecimal`, `HALF_UP` to whole dollars. `confidence` = vision confidence × photo-quality weight (CLEAR 1.00, PARTIAL 0.85, BLURRY 0.70, UNUSABLE 0.50, unknown 0.80), with the calculation in `confidenceBasis`.

---

## Branch B — Legal & Regulatory Compliance

### `PiiRedactionAgent`
Regex redaction of SSN (`***-**-****`), DOB (`**/**/****`), MRN (`[REDACTED_MRN]`) and literal occurrences of the claimant's name, phone and email in the description. Redaction confidence is a fixed 0.95. `PIIGuardrail` then blocks (HTTP 422) if confidence < 0.90, a raw SSN pattern remains, or an MRN was detected.

### `StatutoryDeadlineAgent`
`SyntheticDeadlineRuleProvider` returns `acknowledgeBy` (incident + CA 30 / NY 35 / TX 15 / FL 20 / IL 30 days; also returned as the legacy `deadline` field), `decisionBy` (incident + CA 70 / NY 65 / TX 45 / FL 90 / IL 75 days) and `urgencyFlag` against today: `CRITICAL` past `acknowledgeBy`, `WARNING` within 3 days, else `OK`. Every value is labelled **PROTOTYPE PLACEHOLDER** in `disclaimer`; Compliance must confirm real state rules before production use.

---

### `SubrogationScorerAgent` (Branch B step B3)
`DeterministicSubrogationScorer.assessLiability` scores third-party liability from the description — third-party mention ("other driver", "rear-ended") 35 else 10; "police" 20 else 5 — and writes compliance notes (preserve evidence and identify the other insurer; record or request the police report; or "recovery unlikely"). Its action is `PENDING_SEVERITY`.

## Reconciliation gate

### Subrogation finalisation
`applySeverity` adds Branch A's severity points — MINOR 5 / MODERATE 15 / SEVERE 25 (other values 15) — caps the score at 100, sets `INVESTIGATE` at 70 or more (else `MONITOR`) and adds a referral note. A rear-end claim with a police report and MODERATE damage scores 70, so it goes to a human for subrogation.

### `GateConsistencyChecker`
Cross-checks the branches and the policy: `SEVERITY_ESTIMATE_MISMATCH` (severity label vs. estimate band: MINOR < $2,000, MODERATE ≤ $10,000, else SEVERE), `RESERVE_EXCEEDS_COVERAGE_LIMIT`, `DAMAGE_TYPE_LINE_MISMATCH` (vehicle damage on a HOME policy, water damage on AUTO), `CONFIDENCE_PHOTO_QUALITY_MISMATCH` (confidence ≥ 0.85 on a BLURRY/UNUSABLE photo). Flags block STP and add an explanation note.

### `AuditTrailAgent`
SHA-256 (hex) of `String.valueOf(input)` and of the output. Callers pass a `SanitizedIntake` or agent outputs, never the raw payload. `AuditService` (in `fnol-api`) chains the records — see `docs/database.md`.

### `ExplanationBuilder` and routing
Six factors, thresholds from `fnol.stp.*` (`StpProperties`): AI confidence ≥ 0.85; coverage active and matched; PII absent, or redacted with confidence ≥ 0.90; reserve ≤ $25,000; claim-frequency risk LOW or MEDIUM; subrogation score < 70. Severity: LOW ≤ $5,000, MEDIUM ≤ $20,000, else HIGH. STP requires LOW or MEDIUM severity, all six factors passing and no intake or gate flag; HIGH always goes to a human. `ClaimTypeValidator` maps vision damage types to claim types (WINDSHIELD → GLASS_ONLY; FRONT/REAR_BUMPER, SIDE_PANEL → MINOR_COLLISION) for `eligibleForAdvisoryPrefill` (informational; it does not change the route).
