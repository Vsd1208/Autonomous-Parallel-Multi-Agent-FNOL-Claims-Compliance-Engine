# The insurance domain, for non-insurance readers

What this engine does in insurance terms, where it sits in a claim's life, and which class implements each idea. Scope:
first-party **property damage** on personal **auto** and **homeowners** policies in five US states (CA, NY, TX, FL,
IL). Bodily injury, total-loss valuation, liability apportionment and payments are out of scope.

## 1. The FNOL lifecycle and where the engine sits

A claim moves through these stages. The engine covers the first four and supports the fifth; people do the rest.

| Stage | What happens | In this engine |
|---|---|---|
| 1. First notice of loss (FNOL) | The policyholder (or an agent or call-centre colleague on their behalf) reports the loss: what, when, where, photos | Policyholder portal (`PortalController`, `frontend/portal`) or internal intake (`FNOLController.intake`, `frontend/src/pages/NewClaimWizard.tsx`); payload `Models.FNOLPayload` |
| 2. Coverage verification | Is there a policy in force on the date of loss, and does a coverage on it apply to this kind of damage? | `PolicyValidatorAgent` with `CoverageRuleProvider` (`SyntheticCoverageRuleProvider`), policy data from `PolicyCenterClient` |
| 3. Triage and assignment | How serious, how complex, any red flags? Simple claims can go "straight through"; others go to an adjuster's queue | `FNOLOrchestrator`, `IntakeReviewService`, `GateConsistencyChecker`, `ExplanationBuilder`; queue `GET /api/v1/fnol/queue` |
| 4. Reserving | The insurer sets aside its best estimate of what the claim will cost | `ReserveCalculatorAgent`; the reserve sits on a ClaimCenter exposure (`MockClaimCenterClient`) |
| 5. Investigation and evaluation | The adjuster checks the facts: more photos, an inspection or appraisal, a recorded statement, fraud referral | Photo requests (`UploadRequestController`), inspections (`InspectionController`), the WHY panel and AI notes |
| 6. Decision and settlement | Accept (pay) or deny in writing, within the state's deadline | Adjuster decision `POST /fnol/{id}/review`; payment is out of scope |
| 7. Recovery (subrogation) and closure | If someone else caused the loss, the insurer recovers from them or their insurer; the claim is closed | Subrogation flag and recovery-deadline activity (`SubrogationScorerAgent`); decision `CLOSED` |

## 2. Terms and how they map to code

| Term | Meaning | In this codebase |
|---|---|---|
| **Policy, line of business** | The contract. Line of business is the kind of insurance: personal auto (`AUTO`) or homeowners (`HOME`) | `PolicyCenterClient`, policy prefix `POL-AUTO-…` / `POL-HOME-…` |
| **Coverage** | One promise inside the policy, e.g. collision (damage to your car in a crash) or dwelling (damage to your house) | `Models.Coverage`; matched by `CoverageRuleProvider` from the damage type |
| **Limit** | The most the insurer pays under a coverage | `Coverage.limit()`; the reserve is capped at it (`ReserveCalculatorAgent`), and `RESERVE_EXCEEDS_COVERAGE_LIMIT` flags a mismatch |
| **Deductible** | The part of each loss the policyholder pays themselves | `Coverage.deductible()`; subtracted in the reserve |
| **Exclusion** | Damage a policy does not cover. Standard homeowners policies exclude flood (it needs separate flood insurance) | `POSSIBLE_FLOOD_EXCLUSION` in `GateConsistencyChecker` sends the claim to an adjuster; it never denies |
| **Reserve** | Money set aside for the expected cost of the claim; a liability on the insurer's books, not a payment | `ReserveCalculatorAgent`: `min(amount claimed, AI estimate) − deductible`, capped at the limit; the adjuster sets the final reserve |
| **Exposure** | In ClaimCenter, one coverage × one claimant within a claim; reserves are held per exposure | `MockClaimCenterClient` creates one exposure per matched coverage |
| **Severity** | How big the loss is | `StpProperties`: LOW ≤ $5,000, MEDIUM ≤ $20,000, HIGH above (always to a person) |
| **Straight-through processing (STP)** | Handling a simple claim end to end without a person | Six factors in `ExplanationBuilder` plus no flags; by default even STP-eligible claims wait for an adjuster (`fnol.stp.require-advisory-review`, ADR-009) |
| **Leakage** | Money paid that should not have been (over-payment, missed fraud, missed recovery) | The reserve cap at the AI estimate and `CLAIMED_EXCEEDS_AI_ESTIMATE`; the governance page reports the difference kept (`AiGovernanceService`) |
| **Statutory deadlines / unfair claims practices** | State regulations require insurers to acknowledge a claim and accept or deny it within set times | `StatutoryDeadlineAgent`, `CitedDeadlineRuleProvider`, `claim-regulations.json`; [legal-compliance.md](legal-compliance.md) |
| **Proof of loss** | The documents the insurer needs before the decision clock starts | Missing photo views (`INCOMPLETE_PHOTO_SET`) hold the decision clock until they arrive |
| **Acknowledgement letter** | Written confirmation that the claim was received and what happens next | `AcknowledgementLetter` (fixed template, not AI), filed on the ClaimCenter claim |
| **Subrogation** | After paying, the insurer recovers the cost from the party at fault; the right expires after the state's limitation period | `SubrogationScorerAgent`, `DeterministicSubrogationScorer`; activity due on the recovery deadline |
| **Attorney representation** | A represented claimant must be contacted through their lawyer | `ATTORNEY_REPRESENTED` in `IntakeReviewService` |
| **SIU (special investigations unit)** | The insurer's fraud team; adjusters refer suspicious claims to it | Fraud red flags route to an adjuster (`PHOTO_REUSED`, `HIGH_CLAIM_FREQUENCY`, `INCIDENT_NEAR_POLICY_START`, duplicates), and `FraudAnomalyScorer` raises `FRAUD_ANOMALY` when several details are unusual together, with an SIU-referral suggestion; the decision to refer is the adjuster's |
| **Inspection / appraisal** | A field, desk or body-shop inspection that sets the accident-related cost | `InspectionController`; earlier damage is excluded from the reserve |
| **Adjuster** | The licensed person who investigates and decides the claim | Every decision path ends at `POST /fnol/{id}/review`; the AI never pays, denies or closes |

## 3. How a claim decision is made here

1. **Is the claim complete and plausible?** Input guardrails reject malformed input; intake flags (`IntakeReviewService`)
   mark missing photos, a claimant name that does not match the policy, a loss outside or near the start of the policy
   term, frequent recent claims, a reused photo or an attorney.
2. **Is it covered?** `PolicyValidatorAgent` checks the policy is in force on the loss date and finds the coverage for
   the damage type.
3. **How much?** The vision model estimates damage; the trust score says how far to believe it; the reserve takes the
   lower of the claimed amount and the estimate, minus the deductible, within the limit.
4. **What does the law require?** The legal branch removes personal data, computes the acknowledge-by and decide-by
   dates, scores the recovery chance and spots representation.
5. **Does it all agree?** The gate cross-checks (severity against estimate, reserve against limit, damage against line
   of business, photo subject, the two vision models, the flood exclusion, the claimed amount against the estimate) and
   explains the recommendation in six factors.
6. **Who decides?** A licensed adjuster, always by default. The engine recommends and explains.

## 4. Business case (from what the system measures)

This section uses only numbers the repository measures or configures. No industry statistics are quoted, because none
have been checked against a source; the value for a real insurer is unknown until the engine runs on its claims and
the [stakeholder interviews](STAKEHOLDER_INTERVIEW_TEMPLATE.md) are done.

| Lever | What the system shows | Source | Assumption behind the benefit |
|---|---|---|---|
| **Faster first recommendation** | Both branches finish within the orchestrator deadline of 10 s (configured, `FNOL_ORCHESTRATOR_TIMEOUT_SECONDS`), or the claim goes to a person. Gemini's own call took 4.4 s at the median and 34.4 s at p95 on the free tier, so in evaluation 30% of calls exceeded the app's 8 s read timeout. The legal branch runs in parallel, so it adds no waiting | [metrics.md §1](metrics.md#1-gemini-damage-assessment-vehicle) | A recommendation in seconds is useful only if adjusters act on it sooner; a paid Gemini tier is needed for consistent latency |
| **Less manual triage** | On the 50 labelled photos, 29 (58%) could go straight through the vision checks with all checks on, 1 of them wrong. By default every claim still gets a human decision | [metrics.md §3](metrics.md#3-vision-checks-replayed-gate-ablation-vehicle) | Real claims send five photos, not one; coverage, amount and intake checks remove more claims; the insurer chooses whether to switch on unreviewed straight-through processing |
| **Less over-payment (leakage)** | The reserve never exceeds the AI estimate; the AI Governance page totals what claimants asked above the estimate on filed claims (`leakage.amountNotReserved`). Example: demo scenario 8 asks $9,000 against a $4,200 estimate; the reserve is $3,200 after the $1,000 deductible | `AiGovernanceService`, `demo-scenarios/scenario_8_claim_above_estimate.json` | Only a benefit where the estimate is right; repair-cost estimates are **not validated** ([metrics.md](metrics.md#not-measured)), so an adjuster must confirm |
| **Focused fraud referrals** | On synthetic data, the anomaly score flags 5.4% of claims at 40% precision, against 16.4% at 14% precision for the existing fraud rules | [metrics.md §7](metrics.md#7-fraud-anomaly-score-synthetic-data-only) | Synthetic data only; real precision is unknown |
| **Fewer missed deadlines** | Acknowledge-by and decide-by dates from cited rules on every claim; the governance page reports acknowledgement on time | [legal-compliance.md](legal-compliance.md) | The rules need legal review; regulations change |
| **Audit readiness** | Every step is in a SHA-256 hash-chained audit trail with a PDF report; model cards per AI component | `AuditService`, [model-cards.md](model-cards.md) | Value depends on the insurer's regulators and model-risk process |

What would turn this into a real business case: the insurer's own claim volumes, handling cost per claim, current
cycle times and leakage, and a pilot comparing claims handled with and without the engine.
