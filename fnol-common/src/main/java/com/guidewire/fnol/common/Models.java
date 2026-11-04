
package com.guidewire.fnol.common;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class Models {
    private Models() {}

    public record FNOLPayload(
            @NotBlank String policyNumber,
            @NotNull LocalDate incidentDate,
            @NotBlank String state,
            @NotBlank String description,
            String claimantName,
            String claimantDob,
            String claimantSsn,
            List<String> evidenceUris,
            String channel,         // intake source: WEB | MOBILE | PHONE | API (optional)
            String claimantPhone,   // optional; PII — never persisted or logged
            String claimantEmail,   // optional; PII — never persisted or logged
            @PositiveOrZero BigDecimal claimedAmount, // what the claimant asks for; optional for API clients
            List<String> evidenceAngles               // optional, one per evidence URI: FRONT, REAR, LEFT, RIGHT,
                                                      // CLOSE_UP, ODOMETER_VIN, OVERVIEW (home), OTHER
    ) {
        /** Payload without claimant contact details or a claimed amount. */
        public FNOLPayload(String policyNumber, LocalDate incidentDate, String state, String description,
                           String claimantName, String claimantDob, String claimantSsn,
                           List<String> evidenceUris, String channel) {
            this(policyNumber, incidentDate, state, description, claimantName, claimantDob, claimantSsn,
                    evidenceUris, channel, null, null, null);
        }

        /** Payload without a claimed amount. */
        public FNOLPayload(String policyNumber, LocalDate incidentDate, String state, String description,
                           String claimantName, String claimantDob, String claimantSsn,
                           List<String> evidenceUris, String channel, String claimantPhone, String claimantEmail) {
            this(policyNumber, incidentDate, state, description, claimantName, claimantDob, claimantSsn,
                    evidenceUris, channel, claimantPhone, claimantEmail, null);
        }

        /** Payload without photo angles. */
        public FNOLPayload(String policyNumber, LocalDate incidentDate, String state, String description,
                           String claimantName, String claimantDob, String claimantSsn,
                           List<String> evidenceUris, String channel, String claimantPhone, String claimantEmail,
                           BigDecimal claimedAmount) {
            this(policyNumber, incidentDate, state, description, claimantName, claimantDob, claimantSsn,
                    evidenceUris, channel, claimantPhone, claimantEmail, claimedAmount, null);
        }

        /** The angle of evidence item {@code index} (upper case), or OTHER when not given. */
        public String angleOf(int index) {
            if (evidenceAngles == null || index >= evidenceAngles.size() || evidenceAngles.get(index) == null
                    || evidenceAngles.get(index).isBlank()) {
                return "OTHER";
            }
            return evidenceAngles.get(index).trim().toUpperCase(java.util.Locale.ROOT);
        }
    }

    public record Coverage(
            String type,
            BigDecimal limit,
            BigDecimal deductible
    ) {}

    public record Policy(
            String policyNumber,
            String policyType,
            String status,
            LocalDate effectiveDate,
            LocalDate expirationDate,
            String insuredName,
            String state,
            String riskDescription,
            List<Coverage> coverages,
            String jurisdiction,        // which claim rules apply; null means US
            String currency,            // null means USD
            LocalDate cancellationDate, // null when not cancelled
            List<Endorsement> endorsements
    ) {
        public Policy(String policyNumber, String policyType, String status, LocalDate effectiveDate, LocalDate expirationDate,
                      String insuredName, String state, String riskDescription, List<Coverage> coverages) {
            this(policyNumber, policyType, status, effectiveDate, expirationDate, insuredName, state, riskDescription, coverages, null, null, null, null);
        }

        public Policy(String policyNumber, String policyType, String status, LocalDate effectiveDate, LocalDate expirationDate,
                      String insuredName, String state, String riskDescription, List<Coverage> coverages, String jurisdiction, String currency) {
            this(policyNumber, policyType, status, effectiveDate, expirationDate, insuredName, state, riskDescription, coverages, jurisdiction, currency, null, null);
        }
    }

    /** A change to a coverage from a given date. */
    public record Endorsement(LocalDate effectiveDate, String change, String coverageType, BigDecimal limit, BigDecimal deductible) {
        public static final String ADD = "ADD";
        public static final String REMOVE = "REMOVE";
    }

    public record ClaimHistory(
            String claimId,
            LocalDate lossDate,
            String status,
            BigDecimal paidAmount,
            String description
    ) {}

    public record Claim(
            String claimId,
            String status,
            String policyNumber,
            LocalDate incidentDate,
            String state,
            BigDecimal estimatedDamage,
            BigDecimal reserve,
            String notes,
            List<ClaimAttachment> attachments,
            List<ClaimReviewAction> reviewActions,
            List<CcExposure> exposures,
            List<CcActivity> activities,
            List<CcNote> claimNotes,
            String workQueue,         // the adjuster work queue the claim sits in (see ClaimPatch.workQueue)
            String claimNumber,       // what people quote: a draft number, then the open claim number
            String segment,           // from the segmentation rules, e.g. AUTO_COMPLEX
            String assignedTo,        // the person handling it
            String assignmentGroup    // the group's queue
    ) {
        public Claim(String claimId, String status, String policyNumber, LocalDate incidentDate, String state,
                     BigDecimal estimatedDamage, BigDecimal reserve, String notes, List<ClaimAttachment> attachments,
                     List<ClaimReviewAction> reviewActions, List<CcExposure> exposures, List<CcActivity> activities,
                     List<CcNote> claimNotes, String workQueue, String claimNumber) {
            this(claimId, status, policyNumber, incidentDate, state, estimatedDamage, reserve, notes, attachments,
                    reviewActions, exposures, activities, claimNotes, workQueue, claimNumber, null, null, null);
        }

        public Claim(String claimId, String status, String policyNumber, LocalDate incidentDate, String state,
                     BigDecimal estimatedDamage, BigDecimal reserve, String notes, List<ClaimAttachment> attachments,
                     List<ClaimReviewAction> reviewActions, List<CcExposure> exposures, List<CcActivity> activities,
                     List<CcNote> claimNotes, String workQueue) {
            this(claimId, status, policyNumber, incidentDate, state, estimatedDamage, reserve, notes, attachments,
                    reviewActions, exposures, activities, claimNotes, workQueue, null);
        }

        public Claim(String claimId, String status, String policyNumber, LocalDate incidentDate, String state,
                     BigDecimal estimatedDamage, BigDecimal reserve, String notes, List<ClaimAttachment> attachments,
                     List<ClaimReviewAction> reviewActions, List<CcExposure> exposures, List<CcActivity> activities,
                     List<CcNote> claimNotes) {
            this(claimId, status, policyNumber, incidentDate, state, estimatedDamage, reserve, notes, attachments,
                    reviewActions, exposures, activities, claimNotes, null);
        }

        public Claim(String claimId, String status, String policyNumber, LocalDate incidentDate, String state,
                     BigDecimal estimatedDamage, BigDecimal reserve) {
            this(claimId, status, policyNumber, incidentDate, state, estimatedDamage, reserve, null, List.of(), List.of());
        }

        public Claim(String claimId, String status, String policyNumber, LocalDate incidentDate, String state,
                     BigDecimal estimatedDamage, BigDecimal reserve, String notes, List<ClaimAttachment> attachments,
                     List<ClaimReviewAction> reviewActions) {
            this(claimId, status, policyNumber, incidentDate, state, estimatedDamage, reserve, notes, attachments,
                    reviewActions, List.of(), List.of(), List.of());
        }
    }

    /*
     * ClaimCenter objects the FNOL engine creates on a claim, shaped after ClaimCenter's entities (and the Cloud API
     * claim resources: exposures, activities, notes, documents). The ids, status and timestamps are set by
     * ClaimCenter; the engine sends them as null.
     */

    /**
     * What is claimed under which coverage, for which claimant and incident. ClaimCenter holds reserves on exposures,
     * not on the claim. The reserve stays empty until a person's decision sets it: the AI only recommends one.
     */
    public record CcExposure(
            String exposureId,
            String exposureType,      // e.g. VehicleDamage, Dwelling
            String coverageType,      // the policy coverage that pays, e.g. COLLISION, DWELLING
            BigDecimal reserve,       // open reserve on the exposure (cost type claimcost); empty until a person decides
            String costType,          // claimcost
            String status,            // open | closed
            String incident,          // what happened to what, e.g. VehicleIncident; required
            String claimant           // who claims; required
    ) {
        public CcExposure(String exposureId, String exposureType, String coverageType, BigDecimal reserve,
                          String costType, String status) {
            this(exposureId, exposureType, coverageType, reserve, costType, status, null, null);
        }
    }

    /** A task for a person, e.g. "Review AI flag: claimed amount above estimate", due by the statutory deadline. */
    public record CcActivity(
            String activityId,
            String subject,
            String description,
            String priority,          // urgent | high | normal
            LocalDate dueDate,
            String assignedTo,        // user or queue
            String status,            // open | complete
            String completedBy
    ) {}

    /** A claim note, e.g. the AI advisory's explanation. */
    public record CcNote(
            String noteId,
            String topic,             // e.g. general, AI advisory
            String subject,
            String body,
            Instant createdAt
    ) {}

    /**
     * ClaimCenter update after an adjuster decision (PUT /cc/claims/{id}). Each update is also
     * tracked as a review action on the claim.
     */
    public record ClaimUpdateRequest(
            String status,            // claim lifecycle: OPEN or CLOSED; empty leaves it unchanged
            String notes,
            String action,            // the adjuster decision that caused the update
            String adjusterId,
            BigDecimal adjustedReserve, // set when the adjuster changed the AI-recommended reserve
            String workQueue,           // the work queue after this step; empty: the decision, or derived from status
            // The rest of a decision, carried on its claim.decided event so the AI's feedback loop can learn from it
            String overrideReason,
            String confirmedDamageType,
            BigDecimal finalRepairCost,
            String repairCostSource
    ) {
        public ClaimUpdateRequest(String status, String notes) {
            this(status, notes, null, null, null);
        }

        public ClaimUpdateRequest(String status, String notes, String action, String adjusterId,
                                  BigDecimal adjustedReserve) {
            this(status, notes, action, adjusterId, adjustedReserve, null);
        }

        public ClaimUpdateRequest(String status, String notes, String action, String adjusterId,
                                  BigDecimal adjustedReserve, String workQueue) {
            this(status, notes, action, adjusterId, adjustedReserve, workQueue, null, null, null, null);
        }
    }

    /** One tracked review action on a ClaimCenter claim. */
    public record ClaimReviewAction(
            Instant at,
            String action,
            String status,
            String adjusterId,
            String notes,
            BigDecimal adjustedReserve
    ) {}

    /** A document attached to a ClaimCenter claim (POST /cc/claims/{id}/attachments). */
    public record ClaimAttachment(
            String name,
            String contentType,
            Object body          // JSON document
    ) {}

    public record DamageAssessment(
            String damageType,
            String severity,
            BigDecimal estimatedDamage,
            BigDecimal confidence,
            String photoQuality,                          // "CLEAR" | "BLURRY" | "PARTIAL" | "UNUSABLE" | "UNKNOWN"
            Map<String, BigDecimal> damageTypeProbabilities, // e.g. {"FRONT_BUMPER": 0.87, "SIDE_PANEL": 0.09, ...}
            BigDecimal rawConfidence,                     // the model's own confidence when `confidence` is calibrated
            String photoSubject,                          // what the photos show: "VEHICLE" | "BUILDING" | "OTHER_PROPERTY" | "NONE"; null = not reported
            PhotoPrivacy privacy,                         // what left the system for the external model; null = nothing was sent
            String visionSource                           // which model answered: "<model> prompt <sha16> thinking <n>"; null = mock or fallback
    ) {
        /** In visionSource, between the model that answered and the one asked for, when the provider substituted one. */
        public static final String INSTEAD_OF = " instead of ";

        /** The provider answered with a model other than the one asked for: nothing fitted on the requested model applies. */
        public boolean servedBySubstituteModel() {
            return visionSource != null && visionSource.contains(INSTEAD_OF);
        }

        public DamageAssessment(String damageType, String severity, BigDecimal estimatedDamage, BigDecimal confidence,
                                String photoQuality, Map<String, BigDecimal> damageTypeProbabilities) {
            this(damageType, severity, estimatedDamage, confidence, photoQuality, damageTypeProbabilities, null, null, null);
        }

        public DamageAssessment(String damageType, String severity, BigDecimal estimatedDamage, BigDecimal confidence,
                                String photoQuality, Map<String, BigDecimal> damageTypeProbabilities,
                                BigDecimal rawConfidence) {
            this(damageType, severity, estimatedDamage, confidence, photoQuality, damageTypeProbabilities, rawConfidence, null, null);
        }

        public DamageAssessment(String damageType, String severity, BigDecimal estimatedDamage, BigDecimal confidence,
                                String photoQuality, Map<String, BigDecimal> damageTypeProbabilities,
                                BigDecimal rawConfidence, String photoSubject) {
            this(damageType, severity, estimatedDamage, confidence, photoQuality, damageTypeProbabilities, rawConfidence,
                    photoSubject, null);
        }

        public DamageAssessment(String damageType, String severity, BigDecimal estimatedDamage, BigDecimal confidence,
                                String photoQuality, Map<String, BigDecimal> damageTypeProbabilities,
                                BigDecimal rawConfidence, String photoSubject, PhotoPrivacy privacy) {
            this(damageType, severity, estimatedDamage, confidence, photoQuality, damageTypeProbabilities, rawConfidence,
                    photoSubject, privacy, null);
        }

        /** The same assessment with the photo subject the vision model reported. */
        public DamageAssessment withPhotoSubject(String subject) {
            return new DamageAssessment(damageType, severity, estimatedDamage, confidence, photoQuality,
                    damageTypeProbabilities, rawConfidence, subject, privacy, visionSource);
        }

        /** The same assessment with a record of what was sent to the external model. */
        public DamageAssessment withPrivacy(PhotoPrivacy p) {
            return new DamageAssessment(damageType, severity, estimatedDamage, confidence, photoQuality,
                    damageTypeProbabilities, rawConfidence, photoSubject, p, visionSource);
        }

        /** The same assessment with a different repair estimate and the severity band that goes with it. */
        public DamageAssessment withEstimate(BigDecimal estimate, String newSeverity) {
            return new DamageAssessment(damageType, newSeverity, estimate, confidence, photoQuality,
                    damageTypeProbabilities, rawConfidence, photoSubject, privacy, visionSource);
        }

        /** The same assessment marked with the model, prompt and settings that produced it (for feedback refits). */
        public DamageAssessment withVisionSource(String source) {
            return new DamageAssessment(damageType, severity, estimatedDamage, confidence, photoQuality,
                    damageTypeProbabilities, rawConfidence, photoSubject, privacy, source);
        }

        /**
         * Backward-compatible factory used by internal stubs and tests that do not
         * supply Gemini vision metadata. Defaults photoQuality to "UNKNOWN" and
         * probability map to empty.
         */
        public static DamageAssessment of(String damageType, String severity,
                                          BigDecimal estimatedDamage, BigDecimal confidence) {
            return new DamageAssessment(damageType, severity, estimatedDamage, confidence,
                    "UNKNOWN", Map.of());
        }
    }

    /** How a reserve was reached, for the WHY panel and the console: base row, AI adjustment, then the caps. */
    public record ReserveBreakdown(
            String method,              // AVERAGE_TABLE or AI_ESTIMATE
            boolean tableEntryMissing,  // no row for this claim: a person must set the reserve
            String baseRow,             // e.g. AUTO / COLLISION / MODERATE
            BigDecimal baseReserve,
            String baseProvenance,      // PUBLISHED or SYNTHETIC
            String baseSource,
            boolean adjustmentAllowed,
            String adjustmentReason,    // why the AI could, or could not, move the base
            BigDecimal adjustmentMax,   // e.g. 0.40 for +/-40%
            BigDecimal supportedAmount, // the base after any adjustment, before the caps
            List<String> capsApplied
    ) {}

    public record ReserveResult(
            BigDecimal estimatedDamage,
            BigDecimal factor,
            BigDecimal recommendedReserve,
            BigDecimal confidence,      // 0-1: vision confidence weighted by photo quality
            String confidenceBasis,
            BigDecimal claimedAmount,   // what the claimant asked for (null when not stated)
            BigDecimal settlementBasis, // the lower of the claimed amount and the AI estimate
            BigDecimal deductible,      // subtracted from the basis; the reserve is what the insurer owes
            BigDecimal coverageLimit,   // the reserve is capped here
            ReserveBreakdown breakdown  // how the figure was reached; null on results built by hand
    ) {
        public ReserveResult(BigDecimal estimatedDamage, BigDecimal factor, BigDecimal recommendedReserve, BigDecimal confidence,
                             String confidenceBasis, BigDecimal claimedAmount, BigDecimal settlementBasis,
                             BigDecimal deductible, BigDecimal coverageLimit) {
            this(estimatedDamage, factor, recommendedReserve, confidence, confidenceBasis, claimedAmount, settlementBasis,
                    deductible, coverageLimit, null);
        }

        public ReserveResult(BigDecimal estimatedDamage, BigDecimal factor, BigDecimal recommendedReserve) {
            this(estimatedDamage, factor, recommendedReserve, null, null);
        }

        public ReserveResult(BigDecimal estimatedDamage, BigDecimal factor, BigDecimal recommendedReserve,
                             BigDecimal confidence, String confidenceBasis) {
            this(estimatedDamage, factor, recommendedReserve, confidence, confidenceBasis,
                    null, estimatedDamage, null, null);
        }
    }

    public record PIIResult(
            String redactedDescription,
            List<String> detectedTypes,
            boolean piiDetected,
            BigDecimal redactionConfidence,   // null for rules, which have no calibrated confidence
            List<PiiDetection> detections,
            String validation                 // the re-scan after masking: VALIDATED or VALIDATION_FAILED; null on older records
    ) {
        public static final String VALIDATED = "VALIDATED";
        public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

        public PIIResult(String redactedDescription, List<String> detectedTypes, boolean piiDetected, BigDecimal redactionConfidence) {
            this(redactedDescription, detectedTypes, piiDetected, redactionConfidence, List.of(), null);
        }
    }

    /** What one detector found. Confidence is null unless a detector really computes one. */
    public record PiiDetection(String category, String detector, int count, BigDecimal confidence, boolean redacted) {}

    /** One step of a claim clock: what is due, by when, from what, and on what authority. */
    public record ClockMilestone(String action, LocalDate dueBy, String startsFrom, String period, String citation, String appliesWhen,
                                 String verification) {}

    public record DeadlineResult(
            String state,
            LocalDate deadline,         // kept for existing clients; equals acknowledgeBy
            String ruleSource,
            String disclaimer,
            LocalDate acknowledgeBy,
            LocalDate decisionBy,       // null when no fixed period applies, or the clock has not started
            String urgencyFlag,         // OK | WARNING (acknowledgeBy within 3 days) | CRITICAL (past due)
            String acknowledgeCitation, // the regulation behind acknowledgeBy, e.g. "Cal. Code Regs. tit. 10, § 2695.5(e)"
            String decisionCitation,
            String decisionNote,        // how decisionBy was set, or why there is none
            String rulesVersion,        // version of the rule set used (claim-regulations.json)
            LocalDate recoverBy,        // last day to start a recovery action against an at-fault party (subrogation)
            String recoveryCitation,
            List<ClockMilestone> milestones   // steps a jurisdiction has beyond acknowledge and decide; empty for the US states
    ) {
        public DeadlineResult(String state, LocalDate deadline, String ruleSource, String disclaimer, LocalDate acknowledgeBy, LocalDate decisionBy,
                              String urgencyFlag, String acknowledgeCitation, String decisionCitation, String decisionNote, String rulesVersion,
                              LocalDate recoverBy, String recoveryCitation) {
            this(state, deadline, ruleSource, disclaimer, acknowledgeBy, decisionBy, urgencyFlag, acknowledgeCitation, decisionCitation,
                    decisionNote, rulesVersion, recoverBy, recoveryCitation, List.of());
        }

        public DeadlineResult(String state, LocalDate deadline, String ruleSource, String disclaimer,
                              LocalDate acknowledgeBy, LocalDate decisionBy, String urgencyFlag) {
            this(state, deadline, ruleSource, disclaimer, acknowledgeBy, decisionBy, urgencyFlag,
                    null, null, null, null, null, null);
        }
    }

    public record SubrogationResult(
            int score,
            String recommendedAction,          // INVESTIGATE | MONITOR; PENDING_SEVERITY in Branch B
            Map<String, Integer> factors,
            List<String> complianceNotes,      // liability and recovery notes for the adjuster (prototype guidance)
            Map<String, String> thirdParty     // what the description says about the other party: plate, insurer, police report
    ) {
        public SubrogationResult(int score, String recommendedAction, Map<String, Integer> factors) {
            this(score, recommendedAction, factors, List.of(), Map.of());
        }

        public SubrogationResult(int score, String recommendedAction, Map<String, Integer> factors,
                                 List<String> complianceNotes) {
            this(score, recommendedAction, factors, complianceNotes, Map.of());
        }
    }

    public record PolicyValidationResult(
            String policyNumber,
            boolean policyFound,
            boolean policyActive,
            boolean relevantCoverageFound,
            boolean covered,
            String coverageType,
            BigDecimal coverageLimit,
            BigDecimal deductible,
            List<String> reasoning,
            String coverageState        // COVERED, NOT_COVERED, NOT_IN_FORCE or UNVERIFIED
    ) {
        public static final String COVERED = "COVERED";
        public static final String NOT_COVERED = "NOT_COVERED";
        public static final String NOT_IN_FORCE = "NOT_IN_FORCE";
        /** The data cannot settle it. Goes to a person. */
        public static final String UNVERIFIED = "UNVERIFIED";

        public PolicyValidationResult(String policyNumber, boolean policyFound, boolean policyActive, boolean relevantCoverageFound, boolean covered,
                                      String coverageType, BigDecimal coverageLimit, BigDecimal deductible, List<String> reasoning) {
            this(policyNumber, policyFound, policyActive, relevantCoverageFound, covered, coverageType, coverageLimit, deductible, reasoning,
                    covered ? COVERED : NOT_COVERED);
        }
    }

    public record AuditRecord(
            String eventId,
            String claimId,
            String agentName,
            Instant timestamp,
            String inputHash,
            String outputHash,
            String status,
            String modelVersion,        // the model or prompt behind this step, where one was used
            String rulesetVersion       // the rule set behind this step, where one was used
    ) {
        public AuditRecord(String eventId, String claimId, String agentName, Instant timestamp, String inputHash, String outputHash, String status) {
            this(eventId, claimId, agentName, timestamp, inputHash, outputHash, status, null, null);
        }

        public AuditRecord withVersions(String model, String ruleset) {
            return new AuditRecord(eventId, claimId, agentName, timestamp, inputHash, outputHash, status, model, ruleset);
        }
    }

    /**
     * What happened to the claim photos before they went to the external vision model: how many were sent, how many
     * were withheld (e.g. the odometer / VIN photo), and how many faces and licence plates were blurred first.
     */
    public record PhotoPrivacy(int photosSent, int photosWithheld, int facesBlurred, int platesBlurred, boolean redacted) {}

    public record BranchAResult(
            DamageAssessment damageAssessment,
            PolicyValidationResult coverage,
            ReserveResult reserve,
            SecondOpinion secondOpinion,       // null when the second-opinion model is off, unreachable or had no photo
            RepairCostRange repairCost         // null for home claims and areas without a cost range
    ) {
        public BranchAResult(DamageAssessment damageAssessment, PolicyValidationResult coverage, ReserveResult reserve) {
            this(damageAssessment, coverage, reserve, null, null);
        }

        public BranchAResult(DamageAssessment damageAssessment, PolicyValidationResult coverage, ReserveResult reserve,
                             SecondOpinion secondOpinion) {
            this(damageAssessment, coverage, reserve, secondOpinion, null);
        }
    }

    /**
     * What repairing this damaged area usually costs, for the visible extent the on-premise model saw
     * (repair-cost-ranges.json). basis PUBLISHED: published US figures with their sources, mapped to the extent band by
     * this project (an assumption); LEARNED: fitted on this insurer's inspected and adjuster-confirmed costs
     * (tools/cost-eval/fit_costs.py). The estimate is the range's geometric mean (PUBLISHED) or the fitted value.
     */
    public record RepairCostRange(
            String area,
            String extentBand,                 // SMALL | MEDIUM | LARGE, or null when the extent was not measured
            BigDecimal low,
            BigDecimal high,
            BigDecimal estimate,
            String basis,                      // PUBLISHED | LEARNED
            String mapping,                    // which published figures apply and why, or how many claims it learned from
            List<String> sources               // URLs of the published figures; empty for LEARNED
    ) {}

    /**
     * A second, independently trained vision model's view of the same photos (fnol-vision-ml): probabilities for the
     * damaged area (AUTO) or cause of loss (HOME), and for what the photos show. Used to catch Gemini's confident
     * mistakes (gate flag MODEL_DISAGREEMENT); it never decides on its own.
     */
    /**
     * Visible damage extent from the on-premise model: the share of the photo covered by damage, banded into thirds of
     * the labelled photos (SMALL / MEDIUM / LARGE). It also depends on how close the photo was taken; it is not a cost.
     */
    public record DamageExtent(String band, BigDecimal share) {}

    public record SecondOpinion(
            String model,
            Map<String, BigDecimal> damageProbabilities,
            Map<String, BigDecimal> subjectProbabilities,
            DamageExtent extent,                          // AUTO only, null when the model has no extent head
            // HOME only: RESIDENTIAL / COMMERCIAL / OTHER_PROPERTY, trained on reviewed FEMA photos; null otherwise
            Map<String, BigDecimal> propertyTypeProbabilities
    ) {
        public SecondOpinion(String model, Map<String, BigDecimal> damageProbabilities,
                             Map<String, BigDecimal> subjectProbabilities) {
            this(model, damageProbabilities, subjectProbabilities, null, null);
        }

        public SecondOpinion(String model, Map<String, BigDecimal> damageProbabilities,
                             Map<String, BigDecimal> subjectProbabilities, DamageExtent extent) {
            this(model, damageProbabilities, subjectProbabilities, extent, null);
        }

        /** Probability the second opinion gives a damage type, or null when the type is not one of its classes. */
        public BigDecimal probabilityOf(String damageType) {
            return damageProbabilities == null ? null : damageProbabilities.get(damageType);
        }
    }

    public record BranchBResult(
            PIIResult pii,
            DeadlineResult deadline,
            SubrogationResult subrogation,
            List<AuditRecord> audit
    ) {}

    public record FNOLResponse(
            String claimId,
            String status,
            BranchAResult branchA,
            BranchBResult branchB,
            PolicyHistoryContext policyContext,
            ExplanationObject explanation,
            String severityGate,
            Boolean eligibleForAdvisoryPrefill,
            Boolean requiresHumanValidation,   // true when any factor fails, HIGH risk, or any input flag
            List<String> notes,
            List<String> inputFlags,           // Human Review Point 1 flags, e.g. MISSING_EVIDENCE
            String ccSyncStatus,               // SYNCED | FAILED: were the advisory and evidence attached in ClaimCenter
            List<String> gateFlags,            // reconciliation-gate consistency flags, e.g. RESERVE_EXCEEDS_COVERAGE_LIMIT
            FraudAnomaly fraudAnomaly,         // advisory anomaly score with its top contributors; null when off
            Abstention abstention              // whether the AI declined to give an answer worth relying on, and why
    ) {
        public FNOLResponse(String claimId, String status, BranchAResult branchA, BranchBResult branchB,
                            PolicyHistoryContext policyContext, ExplanationObject explanation, String severityGate,
                            Boolean eligibleForAdvisoryPrefill, Boolean requiresHumanValidation, List<String> notes,
                            List<String> inputFlags, String ccSyncStatus, List<String> gateFlags, FraudAnomaly fraudAnomaly) {
            this(claimId, status, branchA, branchB, policyContext, explanation, severityGate, eligibleForAdvisoryPrefill,
                    requiresHumanValidation, notes, inputFlags, ccSyncStatus, gateFlags, fraudAnomaly, null);
        }

        public FNOLResponse(String claimId, String status, BranchAResult branchA, BranchBResult branchB,
                            PolicyHistoryContext policyContext, ExplanationObject explanation, String severityGate,
                            Boolean eligibleForAdvisoryPrefill, Boolean requiresHumanValidation, List<String> notes,
                            List<String> inputFlags, String ccSyncStatus, List<String> gateFlags) {
            this(claimId, status, branchA, branchB, policyContext, explanation, severityGate, eligibleForAdvisoryPrefill,
                    requiresHumanValidation, notes, inputFlags, ccSyncStatus, gateFlags, null, null);
        }
    }

    /** The AI has no answer worth relying on: the claim goes to a person. Never a rejection, and nothing against the claim. */
    public record Abstention(boolean abstained, List<String> reasons) {}

    /** One feature's share of the fraud anomaly score: its value and the points it added. */
    public record FraudContribution(
            String feature,
            String label,
            String value,
            BigDecimal points
    ) {}

    /**
     * Advisory fraud anomaly score: how unusual the claim's intake features are against a reference profile.
     * At or above the threshold the gate raises FRAUD_ANOMALY, which routes the claim to an adjuster and suggests an
     * SIU referral. It is not a finding of fraud and never denies a claim.
     */
    public record FraudAnomaly(
            BigDecimal score,
            BigDecimal threshold,
            BigDecimal percentile,     // share of reference claims (%) less unusual than this one; null without a reference
            boolean anomalous,
            List<FraudContribution> topContributors,
            String basis               // what the parameters were fitted on (synthetic data; see docs/model-cards.md)
    ) {}

    public record ApiError(
            Instant timestamp,
            int status,
            String error,
            String message,
            String path,
            String claimId     // set when a failed intake was saved for human attention (REJ-...)
    ) {
        public ApiError(Instant timestamp, int status, String error, String message, String path) {
            this(timestamp, status, error, message, path, null);
        }
    }

    /**
     * The only view of an intake that may be hashed into the audit trail: no claimant name,
     * DOB, SSN or raw description, so audit hashes can never be matched against raw PII.
     */
    public record SanitizedIntake(
            String policyNumber,
            String state,
            LocalDate incidentDate,
            String redactedDescription
    ) {
        public static SanitizedIntake of(FNOLPayload p, String redactedDescription) {
            return new SanitizedIntake(p.policyNumber(), p.state(), p.incidentDate(), redactedDescription);
        }
    }

    /**
     * Human Review Point 3. {@code adjustedReserve} lets the adjuster modify the AI finding; it
     * requires an {@code overrideReason}.
     */
    public record AdjusterReviewRequest(
            @NotBlank String decision,
            @NotBlank String adjusterId,
            String notes,
            @PositiveOrZero BigDecimal adjustedReserve,
            @Size(max = 500) String overrideReason,
            @Size(max = 40) String confirmedDamageType,  // optional: the damaged area / cause the adjuster confirmed
            // optional: the repair cost the adjuster settled on (body-shop invoice or estimate), before the deductible;
            // with an inspection, its accident-related amount is used when this is empty. Teaches the cost model.
            @PositiveOrZero BigDecimal finalRepairCost,
            // what finalRepairCost rests on: INVOICE, BODY_SHOP_ESTIMATE or INSPECTION (evidence: the cost model learns
            // from these) or ADJUSTER_JUDGEMENT (recorded, never learned from); empty with a cost = ADJUSTER_JUDGEMENT
            @Size(max = 20) String repairCostSource,
            // Evidence from people using the workspace (kept only for signed-in people, never for scripts)
            @jakarta.validation.constraints.Min(0) Integer handlingSeconds,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(5) Integer advisoryRating
    ) {
        public AdjusterReviewRequest(String decision, String adjusterId, String notes, BigDecimal adjustedReserve,
                                     String overrideReason, String confirmedDamageType, BigDecimal finalRepairCost,
                                     String repairCostSource) {
            this(decision, adjusterId, notes, adjustedReserve, overrideReason, confirmedDamageType, finalRepairCost,
                    repairCostSource, null, null);
        }

        /**
         * Sources backed by a document, an inspection or an investigation; only these teach the repair-cost model.
         * INVESTIGATION (the investigator's reported cost on a claim sent to investigation) is set by the system.
         */
        public static final Set<String> EVIDENCE_SOURCES =
                Set.of("INVOICE", "BODY_SHOP_ESTIMATE", "INSPECTION", "INVESTIGATION");
        public static final Set<String> REPAIR_COST_SOURCES =
                Set.of("INVOICE", "BODY_SHOP_ESTIMATE", "INSPECTION", "ADJUSTER_JUDGEMENT");

        public AdjusterReviewRequest(String decision, String adjusterId, String notes) {
            this(decision, adjusterId, notes, null, null, null, null, null);
        }

        public AdjusterReviewRequest(String decision, String adjusterId, String notes, BigDecimal adjustedReserve,
                                     String overrideReason) {
            this(decision, adjusterId, notes, adjustedReserve, overrideReason, null, null, null);
        }

        public AdjusterReviewRequest(String decision, String adjusterId, String notes, BigDecimal adjustedReserve,
                                     String overrideReason, String confirmedDamageType) {
            this(decision, adjusterId, notes, adjustedReserve, overrideReason, confirmedDamageType, null, null);
        }
    }

    public record AdjusterReviewResponse(
            String claimId,
            String status,
            String adjusterDecision,
            String adjusterId,
            String adjusterNotes,
            Instant reviewedAt,
            BigDecimal adjustedReserve,
            String overrideReason
    ) {}

    public record ClaimCreateRequest(
            String policyNumber,
            LocalDate incidentDate,
            String state,
            BigDecimal estimatedDamage,
            BigDecimal reserve,
            String initialStatus,     // OPEN: a filed claim is open; a person's decision is what closes or settles it
            String workQueue          // the adjuster work queue the AI routed it to (see ClaimPatch.workQueue)
    ) {
        public ClaimCreateRequest(String policyNumber, LocalDate incidentDate, String state,
                                  BigDecimal estimatedDamage, BigDecimal reserve) {
            this(policyNumber, incidentDate, state, estimatedDamage, reserve, null);
        }

        public ClaimCreateRequest(String policyNumber, LocalDate incidentDate, String state,
                                  BigDecimal estimatedDamage, BigDecimal reserve, String initialStatus) {
            this(policyNumber, incidentDate, state, estimatedDamage, reserve, initialStatus, null);
        }
    }

    /**
     * A partial update of a claim the claims system already holds (PATCH): what the AI extension records on it.
     * Null fields are left unchanged.
     */
    public record ClaimPatch(
            String status,
            BigDecimal estimatedDamage,
            BigDecimal reserve,
            // Extension field on the claim: the adjuster work queue the AI routed it to, e.g. HUMAN_REVIEW. The claim's
            // status says where it is in its lifecycle; this says whose desk it is on, so queues read from the claim.
            String workQueue,
            String segment,
            String assignedTo,
            String assignmentGroup
    ) {
        public ClaimPatch(String status, BigDecimal estimatedDamage, BigDecimal reserve) {
            this(status, estimatedDamage, reserve, null);
        }

        public ClaimPatch(String status, BigDecimal estimatedDamage, BigDecimal reserve, String workQueue) {
            this(status, estimatedDamage, reserve, workQueue, null, null, null);
        }
    }

    /** One claim in a claims-system work-queue listing. */
    public record ClaimQueueEntry(String claimId, String status, String workQueue, String policyNumber,
                                  BigDecimal reserve, Instant createdAt, String segment, String assignedTo) {
        public ClaimQueueEntry(String claimId, String status, String workQueue, String policyNumber,
                               BigDecimal reserve, Instant createdAt) {
            this(claimId, status, workQueue, policyNumber, reserve, createdAt, null, null);
        }
    }

    /** A page of a claims-system work-queue listing, oldest first. */
    public record ClaimQueuePage(List<ClaimQueueEntry> items, long total) {}

    /** A claims-system event as delivered to subscribers (modelled on CloudEvents; field names as in that spec). */
    public record ClaimEvent(
            String id,
            String type,              // e.g. claim.submitted
            String source,
            Instant time,
            String subject,           // the claim number
            Map<String, Object> data
    ) {}

    public record ClaimCreateResponse(
            String claimId,
            String status,
            String claimNumber
    ) {
        public ClaimCreateResponse(String claimId, String status) {
            this(claimId, status, claimId);
        }
    }

    public record PolicyHistoryContext(
            int totalPriorClaims,
            int claimsLast6Months,
            int claimsLast12Months,
            LocalDate lastClaimDate,
            String lastClaimType,
            BigDecimal lastClaimAmount,
            int openClaimsCount,
            String claimFrequencyRisk
    ) {}

    public record ExplanationFactor(
            String factor,
            String value,
            String status,
            String detail
    ) {}

    public record ExplanationObject(
            String decision,
            String summary,
            List<ExplanationFactor> factors,
            String recommendedAction,
            String severityGate
    ) {}
}
