# Module guide

For the people who will own and explain each part of this project. Each section says what the part does and why, where the
code is, how data moves through it, how it fails safely, and three questions you are likely to be asked, with honest answers.

Line references were checked on 2 Nov 2026 on branch `selection-final`; if a line has moved, search for the name. Every
number comes from [metrics.md](metrics.md), which is the source of truth. Fill in the `Owner:` lines yourselves.

---

## 1. Reconciliation gate and routing

**Owner:**

### What it does and why

Two branches analyse a claim in parallel: operational (photos, coverage, reserve) and legal (PII, deadlines, subrogation). The
gate merges them, cross-checks them, builds the six-factor WHY panel and picks a work queue. It exists because no single AI
figure can be trusted: Gemini reported about 0.9 confidence on nearly every answer, right or wrong (AUC 0.57). So a claim needs
every factor to pass and no flag raised to be eligible for straight-through, and even then, by default, it waits for a person.

### Key classes and files

| What | Where |
|---|---|
| Fan-out and the single overall deadline | [FNOLOrchestrator.java:219-233](../fnol-orchestrator/src/main/java/com/guidewire/fnol/orchestrator/FNOLOrchestrator.java#L219-L233) |
| The gate | [FNOLOrchestrator.java:542](../fnol-orchestrator/src/main/java/com/guidewire/fnol/orchestrator/FNOLOrchestrator.java#L542) |
| The routing decision | [FNOLOrchestrator.java:600-606](../fnol-orchestrator/src/main/java/com/guidewire/fnol/orchestrator/FNOLOrchestrator.java#L600-L606) |
| Consistency flags | [GateConsistencyChecker.java:95](../fnol-orchestrator/src/main/java/com/guidewire/fnol/orchestrator/GateConsistencyChecker.java#L95) |
| Intake flags | [IntakeReviewService.java:50](../fnol-orchestrator/src/main/java/com/guidewire/fnol/orchestrator/IntakeReviewService.java#L50) |
| The six factors and the severity gate | [ExplanationBuilder.java:39-90](../fnol-orchestrator/src/main/java/com/guidewire/fnol/orchestrator/ExplanationBuilder.java#L39-L90) |
| Thresholds (`fnol.stp.*`) | [StpProperties.java](../fnol-orchestrator/src/main/java/com/guidewire/fnol/orchestrator/StpProperties.java) |
| Tests | `GateConsistencyCheckerTest`, `ExplanationBuilderTest`, `FNOLOrchestratorTest`, `FailurePathAndAuditTest` |

### Data flow

```text
intake -> input guardrail -> policy and history -> intake flags
       -> Branch A (vision, second opinion, trust score, coverage, reserve)  \
       -> Branch B (PII, deadlines, subrogation)                             /  -> gate:
          output guardrail -> consistency flags + fraud score -> WHY panel -> route -> write to claim -> audit
```

Routing: `STRAIGHT_THROUGH` needs all six factors passing, severity not HIGH, and no flag. The result is
`PENDING_ADVISORY_REVIEW` by default (a person confirms), `STRAIGHT_THROUGH_PROCESSING` only if `fnol.stp.require-advisory-review`
is turned off, and `HUMAN_REVIEW` otherwise.

### How it fails safely

- A flag sends a claim to a person; it never blocks intake or denies anything
  ([GateConsistencyChecker.java:15-18](../fnol-orchestrator/src/main/java/com/guidewire/fnol/orchestrator/GateConsistencyChecker.java#L15-L18)).
- A branch that times out or throws cancels the other and the intake is saved as a `REJ-...` claim in a queue
  ([FNOLController.java:246-250](../fnol-api/src/main/java/com/guidewire/fnol/api/FNOLController.java#L246-L250)).
- A missing second opinion does not stop a claim, but it also means `MODEL_DISAGREEMENT` did not run
  ([GateConsistencyChecker.java:140-144](../fnol-orchestrator/src/main/java/com/guidewire/fnol/orchestrator/GateConsistencyChecker.java#L140-L144)).
  **So this path is less safe than the others**: with advisory review off, a claim could pass without that check.

### Likely questions

1. **"Why not one model with one score?"** The evidence is that a single self-reported score does not separate right from wrong.
   The checks catch different failures: of 11 wrong answers that pass on confidence, the margin check stops 9 and the second
   opinion 1 more. That count is in sample; with thresholds chosen out of sample it is about 3 left, not 1
   ([metrics §3](metrics.md#3-vision-checks-replayed-gate-ablation-vehicle)).
2. **"What share of claims go straight through?"** We do not know for real claims. On the 66 replayed answers, 36 pass the vision checks,
   and coverage, intake flags and the severity gate come on top. Nothing has run on real claims, and by default a person still
   confirms every one.
3. **"Does the severity gate depend on the AI's dollar figure?"** Yes. The reserve moved to a table in ADR-015, but the severity
   gate and the fraud score still read the AI's estimate. That is a known open gap, and the dollar figure has no validation.

---

## 2. Legal branch and claim clock

**Owner:**

### What it does and why

Branch B masks personal data before anything is stored, works out regulatory deadlines from a cited rule set, and scores whether
another party might be liable. It runs alongside Branch A so that the legal work adds no waiting. The deadlines are data rather
than code so that each one can carry its citation, and so that a state with no verified rule gets no invented date.

### Key classes and files

| What | Where |
|---|---|
| The branch | [FNOLOrchestrator.java:517-535](../fnol-orchestrator/src/main/java/com/guidewire/fnol/orchestrator/FNOLOrchestrator.java#L517-L535) |
| PII masking and its hard stop | [PiiRedactionAgent.java](../fnol-agents/src/main/java/com/guidewire/fnol/agents/PiiRedactionAgent.java), [PIIGuardrail.java:18](../fnol-guardrails/src/main/java/com/guidewire/fnol/guardrails/PIIGuardrail.java#L18) |
| Rules: CA, FL, IL, NY, TX | [claim-regulations.json](../fnol-agents/src/main/resources/claim-regulations.json) |
| Date calculation | [CitedDeadlineRuleProvider.java:66-116](../fnol-agents/src/main/java/com/guidewire/fnol/agents/CitedDeadlineRuleProvider.java#L66-L116) |
| Where the clock starts | [StatutoryDeadlineAgent.java:26-30](../fnol-agents/src/main/java/com/guidewire/fnol/agents/StatutoryDeadlineAgent.java#L26-L30) |
| Restart when photos arrive | [UploadRequestController.java:248-252](../fnol-api/src/main/java/com/guidewire/fnol/api/UploadRequestController.java#L248-L252) |
| Subrogation | [DeterministicSubrogationScorer.java](../fnol-agents/src/main/java/com/guidewire/fnol/agents/DeterministicSubrogationScorer.java) |
| Letter | [AcknowledgementLetter.java](../fnol-orchestrator/src/main/java/com/guidewire/fnol/orchestrator/AcknowledgementLetter.java) |
| Tests | `CitedRegulationsTest`, `CoverageAndDeadlineRulesTest`, `ReserveAndSubrogationTest` |

### Data flow

```text
payload -> PII masking -> guardrail (stop if raw PII remains)
        -> state + line + notice date + proof-of-loss date -> acknowledge-by, decide-by, recover-by, each with a citation
        -> liability points, finished at the gate once Branch A's severity is known
```

Photos missing means the proof of loss is not complete, so the decision clock has not started; it starts when the requested items
arrive. Acknowledgement always runs from notice.

### How it fails safely

- If raw PII would remain, the guardrail throws and the claim is saved as a blocked intake, with the PII not stored.
- Audit records hash the sanitized intake only
  ([FNOLOrchestrator.java:728-730](../fnol-orchestrator/src/main/java/com/guidewire/fnol/orchestrator/FNOLOrchestrator.java#L728-L730)).
- A state with no rule gets no dates and a note to set them manually
  ([CitedDeadlineRuleProvider.java:70-75](../fnol-agents/src/main/java/com/guidewire/fnol/agents/CitedDeadlineRuleProvider.java#L70-L75)).
  A rule with no fixed period, such as Illinois, says so.

### Likely questions

1. **"Have these rules been checked by a lawyer?"** No. Each carries its citation and how it was verified (some regulation or statute
   text, some only from secondary sources quoting the regulation). The rule set's own disclaimer says an insurance-law specialist must
   confirm each one. See [legal-compliance.md](legal-compliance.md).
2. **"Do the dates handle holidays and late analysis?"** Not fully. Business days skip weekends but not public holidays
   ([CitedDeadlineRuleProvider.java:122](../fnol-agents/src/main/java/com/guidewire/fnol/agents/CitedDeadlineRuleProvider.java#L122)).
   And notice is taken as the day of analysis: a claim filed while the engine was down and analysed later starts its clock late,
   which is an open defect ([StatutoryDeadlineAgent.java:27](../fnol-agents/src/main/java/com/guidewire/fnol/agents/StatutoryDeadlineAgent.java#L27)).
3. **"Would this work outside the US?"** Not as it stands. The seam is `DeadlineRuleProvider`, and adding another jurisdiction means reading
   its official text and adding cited rules. No Indian rule is loaded.

---

## 3. Console, roles, authority and assignment flow

**Owner:**

### What it does and why

The console is where a person decides. People sign in with their own accounts so the audit chain names a real person. Each role has
a limit on the reserve it can post. Claims are segmented by rules and assigned to the least busy person in a group, so the AI's
analysis arrives in someone's work rather than a shared pile. The amounts and rules are synthetic prototype values.

### Key classes and files

| What | Where |
|---|---|
| Sign-in and roles | [AuthService.java:44-46](../fnol-api/src/main/java/com/guidewire/fnol/api/security/AuthService.java#L44-L46), [AdjusterTokenFilter.java](../fnol-api/src/main/java/com/guidewire/fnol/api/security/AdjusterTokenFilter.java) |
| Limits per role | [ReserveAuthority.java:39](../fnol-api/src/main/java/com/guidewire/fnol/api/security/ReserveAuthority.java#L39) |
| Decision, and the authority check | [FNOLController.java:533-566](../fnol-api/src/main/java/com/guidewire/fnol/api/FNOLController.java#L533-L566) |
| Held decisions | [ReserveApprovalService.java:59](../fnol-api/src/main/java/com/guidewire/fnol/api/ReserveApprovalService.java#L59), self-approval refused at [:102](../fnol-api/src/main/java/com/guidewire/fnol/api/ReserveApprovalService.java#L102) |
| Segment rules | [segmentation-rules.json](../fnol-api/src/main/resources/segmentation-rules.json), [ClaimSegmenter.java:48](../fnol-api/src/main/java/com/guidewire/fnol/api/ClaimSegmenter.java#L48) |
| Assignment | [ClaimAssignmentService.java:80](../fnol-api/src/main/java/com/guidewire/fnol/api/ClaimAssignmentService.java#L80) |
| Screens | [Desktop.tsx](../frontend/src/pages/Desktop.tsx) (queues), [ClaimFile.tsx:186](../frontend/src/pages/ClaimFile.tsx#L186) (approval banner) |
| Tests | `ReserveAuthorityTest`, `SegmentationAndAssignmentTest`, `ClaimsSystemDecisionTest` |

### Data flow

```text
analysis saved -> segment (first rule that matches) -> group -> least open claims, then least recently assigned -> written to the claim, audited

person decides -> reserve within their limit? -- yes --> claims system first -> claim.decided -> engine's record, audit, feedback
                                               -- no ---> PENDING_APPROVAL, nothing posted -> approver with enough authority
                                                            approve -> the same decision path    decline -> back to the requester
```

### How it fails safely

- If assignment fails, the claim stays in its queue, unassigned; assignment never posts money or moves a claim
  ([ClaimAssignmentService.java:74-76](../fnol-api/src/main/java/com/guidewire/fnol/api/ClaimAssignmentService.java#L74-L76)).
- If the claims system is down, a decision is refused (503) and nothing is recorded anywhere
  ([FNOLController.java:625](../fnol-api/src/main/java/com/guidewire/fnol/api/FNOLController.java#L625)).
- A service credential can never approve, and nobody can approve their own reserve
  ([ReserveApprovalService.java:98-108](../fnol-api/src/main/java/com/guidewire/fnol/api/ReserveApprovalService.java#L98-L108)).
- Refusing to decide costs nothing: only posting money needs authority.

### Likely questions

1. **"Is this how ClaimCenter's authority limits and assignment work?"** It is modelled on the concept, and not verified. We did not
   read a public Guidewire page on either, so we cite none. A real tenant would use its own configuration, and this logic would mostly
   go away. See [guidewire-integration.md](guidewire-integration.md#reserve-authority-limits).
2. **"What if nobody with authority is available?"** The approval waits, with an activity for the group `Supervisors`. It has no expiry or
   escalation yet, so it could wait forever; that is a known gap, as is reassigning by hand.
3. **"Could assignment be gamed or scale badly?"** It is `synchronized` and counts each person's claims on every assignment, which is fine for a
   demo and would not hold across several server instances. Skills, location and leave are not considered. The line of business
   is read from the policy number prefix
   ([ClaimAssignmentService.java:71](../fnol-api/src/main/java/com/guidewire/fnol/api/ClaimAssignmentService.java#L71)), which a real system
   would replace with the policy record.

---

## 4. Vision evaluation, trust score and second-opinion model

**Owner:**

### What it does and why

Gemini reads the photos. We then measure how far to believe it, on labelled public photos. The second-opinion model is trained in this
project, runs on our own hardware, and gives an independent reading. The trust score combines signals that do separate right from wrong
into one calibrated confidence. No figure here comes from real claims, and none validates a dollar amount.

### Key classes and files

| What | Where |
|---|---|
| Gemini call | [GeminiVisionProvider.java](../fnol-agents/src/main/java/com/guidewire/fnol/agents/GeminiVisionProvider.java) |
| Second opinion: client and service | [SecondOpinionAgent.java](../fnol-agents/src/main/java/com/guidewire/fnol/agents/SecondOpinionAgent.java), [fnol-vision-ml/app.py](../fnol-vision-ml/app.py) |
| Trust score in the app | [TrustScorer.java:49-53](../fnol-agents/src/main/java/com/guidewire/fnol/agents/TrustScorer.java#L49-L53), [trust-score.json](../fnol-agents/src/main/resources/trust-score.json) |
| Waits, then scoring | [FNOLOrchestrator.java:440-456](../fnol-orchestrator/src/main/java/com/guidewire/fnol/orchestrator/FNOLOrchestrator.java#L440-L456) |
| Blurring before any external call | [PhotoRedactor.java](../fnol-agents/src/main/java/com/guidewire/fnol/agents/PhotoRedactor.java) |
| Evaluation | [tools/vision-eval/](../tools/vision-eval/): `evaluate.py`, `calibrate.py`, `gate_ablation.py`, `trust_score.py`, `trust_score_strict.py` |
| Model training | [tools/second-opinion/](../tools/second-opinion/): `benchmark_encoders.py`, `build_model.py`, `compare_gemini.py` |
| Tests | `TrustScorerTest`, `SecondOpinionAgentTest`, `OnPremiseVisionTest`, `GeminiVisionProviderTest`, `LiteModeTest` |

### Data flow

```text
photos -> blur faces and plates -> Gemini -> area, severity, estimate, probabilities
       -> second opinion (waits up to 2.5 s) -> trust score from margin, agreement, named area -> replaces confidence
       -> gate checks margin (0.30) and agreement (0.10)

labelled photos -> evaluate.py -> recorded answers -> trust_score*.py, gate_ablation.py (no API calls) -> metrics.md
```

### How it fails safely

- With `fnol.redaction.required=true`, a photo that cannot be blurred is not sent, and the claim goes to a person
  ([PhotoRedactor.java:18-24](../fnol-agents/src/main/java/com/guidewire/fnol/agents/PhotoRedactor.java#L18-L24)). Lite mode refuses to start with
  Gemini on ([LiteMode.java:17](../fnol-api/src/main/java/com/guidewire/fnol/api/LiteMode.java#L17)).
- If Gemini cannot answer, the on-premise reading stands in, flagged `ON_PREMISE_VISION`, which always goes to an adjuster.
- The trust score applies only when its file matches the app's prompt, model and thinking budget on at least 50 images
  ([TrustScorer.java:28-31](../fnol-agents/src/main/java/com/guidewire/fnol/agents/TrustScorer.java#L28-L31)). **The fallback is Gemini's raw confidence, which is
  uninformative**: changing the prompt quietly removes the calibrated score, and the margin and second-opinion checks are then what remains.
- Refits write to the app only if the shipping rules pass, including no negative weights.

### Likely questions

1. **"Your trust score is fitted and tested on the same 66 photos?"** The weights in the app are. Measured with weights and smoothing fitted inside each fold,
   AUC is 0.79 (0.64-0.90), Brier 0.113 (0.074-0.158), calibration error 0.063 (0.030-0.153). The in-sample AUC is 0.86, and that gap is
   why we label them. The feature list itself was chosen with these answers in view; choosing it inside each fold as well gives 0.84, within
   the same range ([metrics §2](metrics.md#2-trust-score-calibrated-ai-confidence-vehicle-claims)).
2. **"Does the model estimate cost?"** No. It reads area, extent and property cause of loss. No public dataset we found pairs photos with
   repair costs. Each decision now stores the real cost with its evidence, so learning is possible once real claims exist; there are none yet.
3. **"How does it do on photos unlike the training set?"** Largely unknown. On six NHTSA crash-test close-ups, it got 4 of 6, too few to conclude
   anything. The property prompt for Gemini has not been measured at all.
