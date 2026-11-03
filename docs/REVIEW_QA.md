# Review questions and answers

Honest answers to the hard questions a reviewer or a ClaimCenter engineer is likely to ask, each linked to its evidence. Numbers come from
[metrics.md](metrics.md). Where the answer is "we don't know yet", it says so and says what would find out.

## The four we expect first

### Why should the reserve trust a dollar estimate with no validation behind it?

It no longer does. Since ADR-015 the reserve starts from a table by line, coverage and severity, and the AI's figure can move it only within a
bound, and not at all when a vision check doubts the reading or the figure is far outside the published range.

The table is **the slot for the insurer's own average reserves**. Today it holds cited public figures and labelled synthetic values, and an insurer replaces them without touching a model. We do not claim it is more accurate: no true repair costs exist here, and comparing two estimates with each other measures neither. Each decision stores the real cost and its evidence, so real invoices could settle this; there are none yet. The AI's influence is limited and explained on the claim. What remains weak is the severity label the table is read by, whose accuracy is not measured.

### Why is severity no longer from the AI's dollars?

Because those dollars have no validation, and they mostly restate the AI's own label. On the 66 recorded answers the dollar gate never reached HIGH, although Gemini labelled 18 of them SEVERE. Severity now comes from the label, and the more severe reading stands when the second opinion's extent differs or no label exists; a table reserve above $20,000 is HIGH ([ADR-016](architecture-decisions.md), [metrics §3](metrics.md#3-vision-checks-replayed-gate-ablation-vehicle)).

This costs people-work: of 32 answers passing the vision checks, 10 are now HIGH. One of them is the wrong answer that used to slip through, but that is in sample, and severity has no ground truth, so we cannot say the new readings are right. Label and extent disagree on 34 of 66 answers, partly because extent depends on how close the photo was taken. An adjuster's view would tell us whether that is too cautious; we don't know yet.

### Your trust score is fitted and tested on the same 66 photos. Why is that acceptable?

On its own it would not be, so we measure it apart from that. The weights in the app were fitted on all 66, and on those answers its AUC is 0.86. Fitting the
weights and smoothing inside each training fold gives AUC **0.79 (0.64-0.90)**, Brier **0.113 (0.074-0.158)** and calibration error **0.063 (0.030-0.153)**;
Gemini's own confidence gives 0.57, 0.150 and 0.090. Choosing the features inside each fold too gives 0.84, 0.108 and 0.041, but that procedure mostly
picks a feature set our shipping rules refuse, so it isn't what runs.

Limits: 12 wrong answers is few. The calibration range overlaps Gemini's own, so we can claim better separation more firmly than better calibration. ±0.05 on the
AUC would take about 474 answers. It is also the same 66 for the headline: "11 → 1" is in sample, and with thresholds chosen out of sample it is about 11 → 3
([metrics §2-3](metrics.md#2-trust-score-calibrated-ai-confidence-vehicle-claims)). By default a person still confirms every claim, which is why this level
of evidence is tolerable for a prototype. It would not be enough to turn advisory review off.

### Where exactly would this break against a real ClaimCenter tenant?

We have never run against one. From the public documentation ([guidewire-integration.md](guidewire-integration.md#where-a-real-deployment-would-differ-most)), in order of expected trouble:

1. **Writing on an open claim.** `PATCH` is documented for drafts. Our estimate and `workQueue` writes on an open claim would need another home, and `workQueue` is not a ClaimCenter field.
2. **`claim.decided` does not exist as one event.** It bundles reserve changes, activity completion and closing, and carries cost evidence with no standard home.
3. **Exposures** need a coverage, claimant and incident; our mock is looser, and its incident names are only labels.
4. **Event payloads.** Real events carry a claim graph with personal data, so redaction must apply on receipt. Our events are thin. Our names, signature scheme and adapter payload shape are this project's or assumed.
5. **Submission can fail validation** and leave a draft; the mock checks only policy number and loss date.
6. **Ordering.** App Events are safe-ordered by the primary object and our mock isn't, so a real tenant might behave better here, but that path is untested.
7. **Authority, segmentation and assignment** are modelled on the idea. A real tenant has its own, and most of ours would be removed.

Access to a sandbox tenant, or an hour with an implementer, would turn this list into facts ([request](guidewire-sandbox-request.md)).

### Who designed the reconciliation gate, and what would you change if the vision model's error rate doubled?

**Who:** _for the team to answer._ See the `Owner:` line in [MODULE_GUIDE.md](MODULE_GUIDE.md#1-reconciliation-gate-and-routing).

**The design:** every factor must pass and no flag may be raised, so independent checks stack, and any one of them can only send a claim to a person, never deny or pay. The thresholds are configuration: `fnol.stp.confidence` (0.85), `min-probability-margin` (0.30), `second-opinion-min-probability` (0.10), `claimed-amount-tolerance` (0.10), `reserve-ceiling` ($25,000), the severity bands, and `require-advisory-review` (on).

**If the error rate doubled:**

1. First, notice. Today nothing would detect it automatically: the governance page tracks drift in how sure and consistent the AI's answers are, and adjuster agreement, which only helps if adjusters' corrections are recorded. We would re-run `evaluate.py` on a labelled set.
2. Keep advisory review on (the default), so a person still decides every claim. This is the protection that does not depend on the error rate.
3. Refit the trust score on the new answers. Its calibration is tied to the rate it was fitted on, so it would be overconfident until refitted. The shipping rules decide whether the refit goes in.
4. Re-choose the margin and agreement thresholds out of sample with `trust_score_strict.py`. We have not measured what values a doubled rate would need, and would not guess; tightening them sends more right answers to adjusters, which is a cost for the business owner to weigh.

## Others a ClaimCenter engineer might ask

### Why an external engine rather than Gosu rules inside ClaimCenter?

Public material presents App Events and Integration Gateway as the way to move this kind of logic outside InsuranceSuite, and the models need Python and hardware that do not belong in the core. Also, we could not compile or test Gosu, so we did not ship any. Whether an insurer would prefer in-core logic for parts of it, we don't know yet; an implementer could tell us.

### What happens if your engine goes down?

Claims are still filed, listed under Awaiting AI Analysis, and analysed when it returns; a one-off check did so 14 s after restart. Tested in `ClaimEventFlowTest` and `ClaimEventExtensionTest`. The regulatory clock runs from the time on the claim's `claim.submitted` event, so an outage does not delay it, though the engine's work is then that much closer to the deadline. Whether that event's time is the legal date of notice on a real tenant, we don't know yet; an insurance-law specialist and an implementer would need to confirm it.

### What if ClaimCenter goes down mid-decision?

The decision is refused with a 503 and nothing is recorded anywhere (`FNOLController.reviewInClaimsSystem`, `ClaimsSystemDecisionTest`). Direct intake that fails is saved as a `REJ-...` claim for a person. Writing results back can partly fail; the claim is then marked for retry.

### Does the AI ever pay, deny or close a claim?

No path pays or denies. The one way money gets posted without a person is STP with `require-advisory-review` turned off, an explicit business decision (ADR-009), which is off by default. Note that claims are open from submission rather than drafts until decided (ADR-013), to match documented ClaimCenter behaviour: the human gate is who posts the reserve.

### How do you keep personal data out?

Text is masked before storage, and a guardrail stops the claim if raw PII would remain. Audit hashes cover only the sanitized intake. Faces and plates are blurred before photos go to an external model, and if that fails the photo is not sent. The plate detector misses many plate styles and recall is unknown, because no ground-truth count exists ([metrics §5](metrics.md#5-privacy-redaction)). Also, original photos stay in the insurer's store and do contain this data.

### Is it biased?

We don't know yet. The governance page computes a four-fifths check from the claims the system holds, which today are demo data. Real claims and legally allowed attributes would be needed to say anything.

### Does it save adjusters time?

We don't know yet. No study has been run; [adjuster-study.md](adjuster-study.md) is the plan, with results empty. No claims professional has been interviewed either ([template](STAKEHOLDER_INTERVIEW_TEMPLATE.md)). Any time-saving claim would be unsupported.

### Is the fraud score any good?

On synthetic data only, where it recovers patterns we put there: AUC 0.92 and 44.4% precision at a 5% referral budget ([metrics §7](metrics.md#7-fraud-anomaly-score-synthetic-data-only)). On real claims the performance is unknown. It only routes to a person and suggests a referral; it never denies.

### What does replay show and not show?

It shows real recorded output from Gemini and our second-opinion model going through the real trust score and checks, with no model or key: a confident wrong answer stopped by the second opinion, for instance. It does not show live analysis of new photos: any other photo gets a placeholder and goes to a person. The trust score's weights were fitted on these same answers, so a score shown in replay is in sample ([demo-scenarios/replay](../demo-scenarios/replay/README.md)).

### What can the drafting agent never do?

It cannot write a decision, reserve, status or assignment, because it has no tool that writes anything; asking for one falls back to the template. It never sees a photo or description text, and it is off by default. Nothing it drafts reaches the claims system until a person accepts it, and a service credential cannot accept. What we cannot promise is that its guard catches every rewording, as it uses English patterns, or that its drafts are good: **quality is not measured**. One live call confirmed the format. See [ADR-017](architecture-decisions.md).

### How verified are the Indian rules?

We opened and read the official text on irdai.gov.in: Master Circular IRDAI/PP&GR/CIR/MISC/117/9/2024 of 5 September 2024, Part C, V.4. Each rule cites its clause. That is all: **no lawyer has reviewed it**, and it is off by default. Our reading of the decision deadline is one a lawyer should confirm, "fifteen days" is assumed to be calendar days, and our search for later amendments was not exhaustive. Rupee amounts are not handled: a person sets the reserve on such a claim. See [legal-compliance.md](legal-compliance.md#india-off-by-default-fnolclaim-clockindiaenabled).
