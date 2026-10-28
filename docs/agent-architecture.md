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
Extracts damage type, severity, estimated repair cost, confidence, photo quality and per-type probabilities from the claim's photos. The orchestrator passes the policy's line of business: AUTO claims are assessed by damaged vehicle area (FRONT_BUMPER, REAR_BUMPER, WINDSHIELD, SIDE_PANEL, ROOF, MULTIPLE), HOME claims by cause of loss (WATER_DAMAGE, FLOOD, FIRE_SMOKE, WIND_STORM, FALLEN_TREE, MULTIPLE).

**`GeminiVisionProvider`** (active when `gemini.vision.enabled=true`, the default)
- Calls Google Gemini (`gemini.vision.model`, default `gemini-2.5-flash`) with a structured-JSON prompt; strips markdown fences from the reply. HOME claims get a separate property prompt (`PROPERTY_ASSESSMENT_PROMPT`) that tells flood (rising surface water) apart from water released inside the house. Both prompts first ask what the photos actually show (`photoSubject`: VEHICLE, BUILDING, OTHER_PROPERTY, NONE), whatever the policy says, so a photo of the wrong kind of property is caught. The calibration map is applied to vehicle answers only.
- Uses its own `RestTemplate` bean (`geminiRestTemplate`, injected by qualifier): 3 s connect, `gemini.vision.read-timeout-seconds` (default 8) read — below the 10 s orchestrator deadline so the fallback can still be returned.
- Sends the key in the `x-goog-api-key` header, never in the URL; logs only scheme and host of evidence URIs.
- Loads evidence through **`EvidenceImageLoader`**, which accepts only `data:image/(jpeg|png|webp|gif);base64` (≤ 5 MB) and `https://` URLs whose host is in `fnol.evidence.allowed-hosts` (default empty = no downloads), resolve only to public addresses (no loopback, link-local such as 169.254.169.254, private, carrier-grade NAT or unique-local ranges), answer 200 without redirect, send `image/*` and ≤ 5 MB. `http:`, `file:` and `mock:` URIs are refused.
- Requests JSON output with thinking off (`gemini.vision.thinking-budget`, default 0) so calls stay inside the read timeout.
- **Confidence calibration** (`VisionConfidenceCalibrator`): when `vision-calibration.json` is on the classpath and matches the configured model and thinking budget, Gemini's self-reported confidence is replaced by the share of correct answers measured on labelled photos (`tools/vision-eval`), keyed on the gap between Gemini's two highest damage-type probabilities (its self-reported confidence does not distinguish right from wrong answers); the raw value is kept in `rawConfidence` and shown in the WHY panel.
- **Fallback** (confidence 0.60, which always routes to a human): no evidence (`NO_IMAGE`), refused evidence (`EVIDENCE_REJECTED`), no API key (`NO_API_KEY`), Gemini error (`API_ERROR`) or unparseable reply (`PARSE_ERROR`).

**`MockVisionProvider`** (active when `gemini.vision.enabled=false`; tests and `.env.demo`)
Keywords in the first evidence URI: `blurry` → confidence 0.61; `severe` → MULTIPLE, $24,000, SEVERE; `water` → WATER_DAMAGE, $4,200; `flood` → FLOOD, $38,000, SEVERE; `slow` → sleeps 12 s (exercises the orchestrator deadline; interruptible); anything else → FRONT_BUMPER, MODERATE, $4,200, confidence 0.91 (WATER_DAMAGE on a HOME policy); `photoSubject` always matches the answer (VEHICLE or BUILDING). No evidence → the 0.60 fallback.

### `SecondOpinionAgent`
Asks the second-opinion vision model (`fnol-vision-ml`, `fnol.second-opinion.url`) about the same photos, loaded through
the same `EvidenceImageLoader` checks, in parallel with Gemini. The model is CLIP ViT-B/32 with a logistic-regression head
trained on CC0 car-damage photos (damaged area) and zero-shot heads for the photo subject and the home cause of loss
(`tools/second-opinion`). Branch A waits at most 2.5 s for it after Gemini and coverage are done; off, unreachable, slow
or no uploaded photo gives `secondOpinion: null` and the claim is handled as without it. When it gives Gemini's damage
type less than `fnol.stp.second-opinion-min-probability` (0.15) the gate raises `MODEL_DISAGREEMENT`.

### `TrustScorer`
Replaces Gemini's self-reported confidence (nearly constant, AUC about 0.5) with a calibrated trust score, the
probability that the damaged area is right, from the probability margin and the second opinion's agreement
(logistic regression from `tools/vision-eval/trust_score.py`; a margin-only model when the second opinion is missing).
Applied in Branch A after the second opinion arrives, for AUTO claims only, and only when `trust-score.json` matches the
app's vehicle prompt, model and thinking budget and was measured on at least 50 images; Gemini's value stays in
`rawConfidence`. The STP confidence factor and the WHY panel then use the trust score.

### `PolicyValidatorAgent`
Reads the policy and coverages from PolicyCenter. Covered = policy found, status `ACTIVE`, today within the policy term, and a coverage accepted by the line-of-business rule from **`CoverageRuleProvider`**. `SyntheticCoverageRuleProvider` (prototype values): AUTO → COLLISION or COMPREHENSIVE; HOME → DWELLING or PERSONAL_PROPERTY; other lines → nothing qualifies. The matched rule and coverage are written into `reasoning`. `ValidationGuardrail` turns "not covered" into `CoverageNotEligibleException` (HTTP 502, saved as a REJ- claim).

### `ReserveCalculatorAgent`
`recommendedReserve` is what the insurer owes: `basis = min(claimedAmount, estimatedDamage)` (the AI estimate when no amount was claimed), then `max(basis − deductible, 0)`, capped at the coverage limit; `BigDecimal`, `HALF_UP` to whole dollars, no loading. It is never more than the claimant asked for or the photos support. `confidence` = vision confidence × photo-quality weight (CLEAR 1.00, PARTIAL 0.85, BLURRY 0.70, UNUSABLE 0.50, unknown 0.80), with the calculation in `confidenceBasis`.

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
Cross-checks the branches and the policy: `SEVERITY_ESTIMATE_MISMATCH` (severity label vs. estimate band: MINOR < $2,000, MODERATE ≤ $10,000, else SEVERE), `RESERVE_EXCEEDS_COVERAGE_LIMIT`, `DAMAGE_TYPE_LINE_MISMATCH` (vehicle damage on a HOME policy, property damage on AUTO), `MODEL_DISAGREEMENT` (the second-opinion model gives Gemini's damage type less than 0.15; on labelled car photos it caught the one confident wrong answer no other check stops, `tools/second-opinion/README.md`), `PHOTO_SUBJECT_MISMATCH` (`photoSubject` is not what the line insures: VEHICLE for AUTO, BUILDING for HOME; e.g. a house on an auto policy, a car or a boat on a home policy, or no damaged property; on a HOME policy, OTHER_PROPERTY such as a fence asks the adjuster to check other-structures or personal-property cover), `POSSIBLE_FLOOD_EXCLUSION` (FLOOD on a HOME policy: standard homeowners policies exclude flood, so an adjuster confirms the cause of loss; the AI never denies), `CONFIDENCE_PHOTO_QUALITY_MISMATCH` (confidence ≥ 0.85 on a BLURRY/UNUSABLE photo), `AMBIGUOUS_DAMAGE_TYPE` (gap between the two highest damage-type probabilities below `fnol.stp.min-probability-margin`, default 0.30; on labelled photos every wrong answer that passed the confidence factor was below it, see `tools/vision-eval/README.md`), `CLAIMED_EXCEEDS_AI_ESTIMATE` (amount claimed more than `fnol.stp.claimed-amount-tolerance`, default 10%, above the AI estimate: the adjuster requests more photos, investigates, or approves at the AI estimate). `RESERVE_EXCEEDS_COVERAGE_LIMIT` now means the loss after the deductible is above the limit (the reserve itself is capped). Flags block STP and add an explanation note.

### `AuditTrailAgent`
SHA-256 (hex) of `String.valueOf(input)` and of the output. Callers pass a `SanitizedIntake` or agent outputs, never the raw payload. `AuditService` (in `fnol-api`) chains the records — see `docs/database.md`.

### `ExplanationBuilder` and routing
Six factors, thresholds from `fnol.stp.*` (`StpProperties`): AI confidence ≥ 0.85; coverage active and matched; PII absent, or redacted with confidence ≥ 0.90; reserve ≤ $25,000; claim-frequency risk LOW or MEDIUM; subrogation score < 70. Severity: LOW ≤ $5,000, MEDIUM ≤ $20,000, else HIGH. STP requires LOW or MEDIUM severity, all six factors passing and no intake or gate flag; HIGH always goes to a human. `ClaimTypeValidator` maps vision damage types to claim types (WINDSHIELD → GLASS_ONLY; FRONT/REAR_BUMPER, SIDE_PANEL → MINOR_COLLISION) for `eligibleForAdvisoryPrefill` (informational; it does not change the route).
