
package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.common.ProcessingTimeoutException;
import com.guidewire.fnol.common.ComplianceBlockException;
import com.guidewire.fnol.common.CoverageNotEligibleException;
import com.guidewire.fnol.orchestrator.*;
import com.guidewire.fnol.api.persistence.*;
import com.guidewire.fnol.api.pdf.AuditPdfGenerator;
import com.guidewire.fnol.agents.AuditTrailAgent;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import jakarta.servlet.http.*;
import jakarta.validation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import com.guidewire.fnol.api.persistence.ReserveApprovalEntity;
import com.guidewire.fnol.api.security.Actor;
import com.guidewire.fnol.api.security.ReserveAuthority;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class FNOLController {

    private static final Logger log = LoggerFactory.getLogger(FNOLController.class);

    private final FNOLOrchestrator orchestrator;
    private final ClaimRepository claims;
    private final AuditService auditService;
    private final FailedIntakeRecorder failedIntakes;
    private final DuplicateSubmissionRecorder duplicates;
    private final EvidenceStore evidence;
    private final ClaimDocumentRepository documents;
    private final IdempotencyStore idempotencyStore;
    private final AuditPdfGenerator pdfGenerator;
    private final AuditTrailAgent auditAgent;
    private final ClaimCenterClient claimCenter;
    /** Records each decision next to the AI's view (feedback loop); optional so the controller works without it. */
    private AiGovernanceService governance;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setGovernance(AiGovernanceService governance) {
        this.governance = governance;
    }
    /** Reports new losses to the claims system when its events are configured; optional for the same reason. */
    private ClaimsSystemIntake claimsSystemIntake;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setClaimsSystemIntake(ClaimsSystemIntake claimsSystemIntake) {
        this.claimsSystemIntake = claimsSystemIntake;
    }

    /** Applies decisions recorded in the claims system to the analysis record; optional for the same reason. */
    private ClaimDecisionRecorder decisions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDecisions(ClaimDecisionRecorder decisions) {
        this.decisions = decisions;
    }

    private LiteMode liteMode;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLiteMode(LiteMode liteMode) {
        this.liteMode = liteMode;
    }

    /** Sets the segment and who handles the claim; optional for the same reason. */
    private ClaimAssignmentService assignment;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAssignment(ClaimAssignmentService assignment) {
        this.assignment = assignment;
    }

    /** Holds a reserve above someone's authority for approval; optional for the same reason. */
    private ReserveApprovalService approvals;
    private ReserveAuthority authority;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setApprovals(ReserveApprovalService approvals, ReserveAuthority authority) {
        this.approvals = approvals;
        this.authority = authority;
    }

    /** No person, no role: a service credential gets the limit a role with no entry gets, the adjuster's. */
    private static final class ReserveAuthorityRoles {
        static final String SERVICE = "SERVICE";
    }

    /** True when new losses go to the claims system first and the analysis follows its event. */
    boolean reportsToClaimsSystem() {
        return claimsSystemIntake != null && claimsSystemIntake.enabled();
    }
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    /** Request attribute carrying the REJ-... ID of a failed intake into the error response. */
    static final String ESCALATED_CLAIM_ID = "fnol.escalatedClaimId";

    public FNOLController(
            FNOLOrchestrator orchestrator,
            ClaimRepository claims,
            AuditService auditService,
            FailedIntakeRecorder failedIntakes,
            DuplicateSubmissionRecorder duplicates,
            EvidenceStore evidence,
            ClaimDocumentRepository documents,
            IdempotencyStore idempotencyStore,
            AuditPdfGenerator pdfGenerator,
            AuditTrailAgent auditAgent,
            ClaimCenterClient claimCenter) {
        this.orchestrator      = orchestrator;
        this.claims            = claims;
        this.auditService      = auditService;
        this.failedIntakes     = failedIntakes;
        this.duplicates        = duplicates;
        this.evidence          = evidence;
        this.documents         = documents;
        this.idempotencyStore  = idempotencyStore;
        this.pdfGenerator      = pdfGenerator;
        this.auditAgent        = auditAgent;
        this.claimCenter       = claimCenter;
    }

    /** Statuses in which an adjuster may record a decision. Final decisions are not reviewable again. */
    static final Set<String> REVIEWABLE_STATUSES =
            Set.of("HUMAN_REVIEW", "PENDING_ADVISORY_REVIEW", "NEEDS_MORE_INFO", "INVESTIGATION_REQUIRED");

    /** Adjuster decision → claim lifecycle. A claim is open until a denial or a closure; the work queue says the rest. */
    static final Map<String, String> CLAIMCENTER_STATUS = Map.of(
            "APPROVED_BY_ADJUSTER", "OPEN",
            "REJECTED_BY_ADJUSTER", "CLOSED",
            "NEEDS_MORE_INFO", "OPEN",
            "INVESTIGATION_REQUIRED", "OPEN",
            "CLOSED", "CLOSED");

    /**
     * What a decision posts as the reserve. This is the only way the AI's recommendation reaches the money: an approval
     * posts the adjuster's figure, or the recommendation they approved. Any other decision posts nothing.
     */
    static BigDecimal reserveToPost(String decision, BigDecimal adjustedReserve, ClaimEntity claim) {
        if (!"APPROVED_BY_ADJUSTER".equals(decision)) {
            return null;
        }
        return adjustedReserve == null ? claim.reserve : adjustedReserve;
    }
    static final Set<String> ADJUSTER_DECISIONS = CLAIMCENTER_STATUS.keySet();

    @org.springframework.beans.factory.annotation.Value("${fnol.claim-clock.india.enabled:false}")
    private boolean indiaClock;

    // ── Health ──────────────────────────────────────────────────────────────
    @GetMapping("/health")
    Map<String, String> health() {
        // intake: CLAIMS_SYSTEM (losses are filed in the claims system, analysis follows its event) or DIRECT
        Map<String, String> health = new LinkedHashMap<>(Map.of("status", "UP", "intake", reportsToClaimsSystem() ? ClaimsSystemIntake.CLAIMS_SYSTEM
                : ClaimsSystemIntake.DIRECT, "secondOpinion", liteMode == null ? "CONFIGURED" : liteMode.secondOpinion()));
        if (liteMode != null && liteMode.vision() != null) {
            health.put("vision", liteMode.vision());
        }
        if (indiaClock) {
            health.put("indiaClock", "ON");
        }
        return health;
    }

    // ── Report a loss (agent taking a phone call, partner channel) ────────────
    /**
     * Files a loss the way the organisation runs: in the claims system, which owns the claim; the AI analysis
     * follows its claim.submitted event and is written back onto the claim. Answers at once with the claim number
     * and SUBMITTED. Without claims-system events, it runs the direct intake and answers with that claim's status.
     */
    @PostMapping("/fnol/report")
    ClaimCreateResponse report(@Valid @RequestBody FNOLPayload payload, HttpServletRequest request) {
        if (reportsToClaimsSystem()) {
            return claimsSystemIntake.report(payload);
        }
        FNOLResponse response = intake(payload, request);
        return new ClaimCreateResponse(response.claimId(), response.status());
    }

    // ── FNOL Intake (direct: analyse, then file the claim) ───────────────────
    @PostMapping("/fnol/intake")
    FNOLResponse intake(@Valid @RequestBody FNOLPayload payload, HttpServletRequest request) {

        String policyNumber  = payload.policyNumber();
        LocalDate incidentDate = payload.incidentDate();
        String state         = payload.state();

        // Layer 1 + 2: Duplicate FNOL check (in-memory + DB-backed)
        if (!idempotencyStore.tryAcquire(policyNumber, incidentDate, state)) {
            // Duplicates need human verification: flag and audit the original claim.
            Optional<String> original = duplicates.record(payload);
            original.ifPresent(id -> request.setAttribute(ESCALATED_CLAIM_ID, id));
            throw new DuplicateFNOLException(original
                    .map(id -> "This FNOL has already been submitted as " + id
                            + "; the original claim is flagged for adjuster verification")
                    .orElse("This FNOL has already been submitted and is still being processed"));
        }

        FNOLResponse response;
        try {
            response = orchestrator.process(payload);
        } catch (RuntimeException e) {
            // Nothing was filed in ClaimCenter, so a corrected submission may be retried.
            idempotencyStore.release(policyNumber, incidentDate.toString(), state);
            escalateFailedIntake(payload, e, request);
            throw e;
        }

        // From here on ClaimCenter holds a claim under response.claimId().
        String claimId = response.claimId();
        if (claims.existsById(claimId)) {
            // A restarted ClaimCenter counter can reissue an ID we already stored; saving would
            // silently overwrite that claim. Refuse, and leave the new ClaimCenter claim for reconciliation.
            log.error("[FNOL] CLAIM_ID_CONFLICT: ClaimCenter issued claimId={} which already exists in the database; "
                    + "refusing to overwrite it. The new ClaimCenter claim is orphaned and must be reconciled.", claimId);
            idempotencyStore.release(policyNumber, incidentDate.toString(), state);
            throw new ClaimIdConflictException(
                    "ClaimCenter returned claim ID " + claimId + " which already exists; the existing claim was not overwritten");
        }

        try {
            // Persist claim (includes explanationJson and channel)
            claims.save(ClaimEntity.from(response, payload));

            // Persist audit trail records as a hash chain
            auditService.append(claimId, response.branchB().audit(), AuditService.SYSTEM_ACTOR);
        } catch (RuntimeException e) {
            // Keep the idempotency key held: releasing it would let a retry file a second ClaimCenter claim.
            log.error("[FNOL] Persisting claimId={} failed after ClaimCenter created it; idempotency key kept. "
                    + "Reconcile this orphaned ClaimCenter claim.", claimId, e);
            throw e;
        }
        // Keep the photos the AI assessed so the adjuster can see them (best effort, audited).
        evidence.store(claimId, payload);
        if (assignment != null) {
            assignment.assign(response);
        }
        return response;
    }

    /**
     * D6: a blocked, uncovered, timed-out or otherwise failed intake is saved as a REJ-... claim with
     * an audit event (BLOCKED_COMPLIANCE or HUMAN_REVIEW) instead of disappearing. Only input
     * validation errors (400), which the submitter must fix, are not saved. The HTTP error is unchanged.
     */
    private void escalateFailedIntake(FNOLPayload payload, RuntimeException e, HttpServletRequest request) {
        String claimId;
        if (e instanceof ComplianceBlockException) {
            claimId = failedIntakes.record(payload, "BLOCKED_COMPLIANCE", "COMPLIANCE_BLOCK", e.getMessage());
        } else if (e instanceof CoverageNotEligibleException) {
            claimId = failedIntakes.record(payload, "HUMAN_REVIEW", "EXTERNAL_OR_PROCESSING_ERROR", e.getMessage());
        } else if (e instanceof ProcessingTimeoutException || e instanceof ResourceAccessException) {
            claimId = failedIntakes.record(payload, "HUMAN_REVIEW", "PROCESSING_TIMEOUT",
                    e instanceof ProcessingTimeoutException ? e.getMessage()
                            : "Guidewire integration call timed out or was unreachable");
        } else if (e instanceof IllegalArgumentException) {
            return;   // invalid input: the submitter corrects and resubmits
        } else {
            String reason = e instanceof IllegalStateException && e.getMessage() != null
                    ? e.getMessage()   // our own guardrail messages, no PII
                    : "Unexpected processing error (" + e.getClass().getSimpleName() + ")";
            claimId = failedIntakes.record(payload, "HUMAN_REVIEW", "EXTERNAL_OR_PROCESSING_ERROR", reason);
        }
        if (claimId != null) {
            request.setAttribute(ESCALATED_CLAIM_ID, claimId);
        }
    }

    // ── Claim Status ─────────────────────────────────────────────────────────
    @GetMapping("/fnol/status/{claimId}")
    ResponseEntity<?> status(@PathVariable("claimId") String claimId) {
        Optional<ClaimEntity> record = claims.findById(claimId);
        if (record.isEmpty()) {
            record = reportsToClaimsSystem() ? claimsSystemIntake.recordOf(claimId) : Optional.empty();
        } else if (reportsToClaimsSystem() && !claimId.startsWith("REJ-")) {
            // The claims system owns the claim's place: a decision made there shows before its event has arrived.
            try {
                String queue = claimCenter.getClaim(claimId).workQueue();
                if (queue != null && !ClaimsSystemIntake.ANALYSIS_PENDING.equals(queue)) {
                    record.get().status = queue;   // for this answer only: the record is not saved here
                }
            } catch (RuntimeException e) {
                log.warn("[FNOL] Claims system unavailable; showing the stored status of {}: {}", claimId, e.getMessage());
            }
        }
        return record.<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Full AI analysis (any adjuster's browser) ─────────────────────────────
    @GetMapping("/fnol/{claimId}/analysis")
    ResponseEntity<?> analysis(@PathVariable("claimId") String claimId) {
        return claims.findById(claimId)
                .filter(c -> c.analysisJson != null)
                .<ResponseEntity<?>>map(c -> {
                    try {
                        return ResponseEntity.ok(mapper.readValue(c.analysisJson, Object.class));
                    } catch (Exception e) {
                        return ResponseEntity.status(500).body(Map.of("error", "Failed to parse analysis"));
                    }
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── The claim as ClaimCenter holds it ────────────────────────────────────
    /**
     * What landed in ClaimCenter for this claim: status, exposures with their reserves, the adjuster's activities,
     * notes, documents and review actions. 502 when ClaimCenter cannot be reached.
     */
    @GetMapping("/fnol/{claimId}/claimcenter")
    ResponseEntity<?> claimCenterView(@PathVariable("claimId") String claimId) {
        if (!reportsToClaimsSystem() && !claims.existsById(claimId)) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(claimCenter.getClaim(claimId));
        } catch (HttpClientErrorException.NotFound e) {
            return ResponseEntity.notFound().build();
        } catch (RuntimeException e) {
            log.warn("[FNOL] ClaimCenter view failed for claimId={}: {}", claimId, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "CLAIMCENTER_UNAVAILABLE",
                    "message", "ClaimCenter could not be reached; try again shortly."));
        }
    }

    // ── WHY Panel (Explanation) ───────────────────────────────────────────────
    /**
     * Human Review Point 2 support: adjuster fetches the structured "WHY" advisory
     * independently, at any time after intake. Returns the persisted ExplanationObject JSON.
     */
    @GetMapping("/fnol/{claimId}/explanation")
    ResponseEntity<?> explanation(@PathVariable("claimId") String claimId) {
        return claims.findById(claimId)
                .<ResponseEntity<?>>map(claim -> {
                    if (claim.explanationJson == null) {
                        return ResponseEntity.status(404)
                                .body(Map.of("error", "No explanation available for claim " + claimId));
                    }
                    try {
                        // Return as parsed JSON (not double-encoded string)
                        Object parsed = mapper.readValue(claim.explanationJson, Object.class);
                        return ResponseEntity.ok(parsed);
                    } catch (Exception e) {
                        return ResponseEntity.status(500)
                                .body(Map.of("error", "Failed to parse explanation"));
                    }
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Pushes the adjuster decision to ClaimCenter. A ClaimCenter outage must not fail the review,
     * so failures are logged and the claim is marked FAILED; POST /fnol/{claimId}/claimcenter-sync retries it.
     */
    private String syncDecisionToClaimCenter(ClaimEntity claim, String decision, String adjusterId, String notes,
                                             BigDecimal adjustedReserve) {
        if (claim.claimId.startsWith("REJ-")) {
            return "NOT_FILED";   // failed intake: there is no ClaimCenter claim to update
        }
        try {
            // Updates the pre-populated claim and tracks the review action in ClaimCenter.
            claimCenter.updateClaim(claim.claimId, new ClaimUpdateRequest(CLAIMCENTER_STATUS.get(decision),
                    notes, decision, adjusterId, reserveToPost(decision, adjustedReserve, claim)));
            return "SYNCED";
        } catch (RuntimeException e) {
            log.warn("[FNOL] ClaimCenter update failed for claimId={} after review; cc_sync_status=FAILED: {}",
                    claim.claimId, e.getMessage());
            return "FAILED";
        }
    }

    /** Retries pushing the recorded adjuster decision to ClaimCenter after a FAILED sync. */
    @PostMapping("/fnol/{claimId}/claimcenter-sync")
    @Transactional
    ResponseEntity<?> retryClaimCenterSync(@PathVariable("claimId") String claimId) {
        ClaimEntity claim = claims.findById(claimId).orElse(null);
        if (claim == null) {
            return ResponseEntity.notFound().build();
        }
        if (claim.adjusterDecision == null || !"FAILED".equals(claim.ccSyncStatus)) {
            throw new InvalidStateException("Claim " + claimId + " has ClaimCenter sync status "
                    + claim.ccSyncStatus + "; only a FAILED decision sync can be retried");
        }
        claim.ccSyncStatus = syncDecisionToClaimCenter(claim, claim.adjusterDecision, claim.adjusterId,
                claim.adjusterNotes, claim.adjusterReserve);
        claims.save(claim);
        auditService.append(claimId, List.of(auditAgent.record(claimId, "ClaimCenterSync",
                "decision=" + claim.adjusterDecision + ";retry=true",
                "ccSyncStatus=" + claim.ccSyncStatus, claim.ccSyncStatus)), claim.adjusterId);
        return ResponseEntity.ok(Map.of("claimId", claimId, "ccSyncStatus", claim.ccSyncStatus));
    }

    // ── Adjuster Queue ────────────────────────────────────────────────────────
    @GetMapping("/fnol/queue")
    QueuePage queue(@RequestParam(name = "status", defaultValue = "HUMAN_REVIEW") String status,
                    @RequestParam(name = "page", defaultValue = "0") int page,
                    @RequestParam(name = "size", defaultValue = "20") int size,
                    @RequestParam(name = "segment", required = false) String segment) {
        Set<String> statuses = new TreeSet<>();
        for (String s : status.split(",")) {
            if (!s.isBlank()) statuses.add(s.trim().toUpperCase(Locale.ROOT));
        }
        if (statuses.isEmpty() || page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("status must be non-empty, page >= 0 and size between 1 and 100");
        }
        String source = QueuePage.ENGINE;
        if (reportsToClaimsSystem()) {
            try {
                return queueFromClaimsSystem(statuses, segment, page, size);
            } catch (RuntimeException e) {
                log.warn("[FNOL] Claims system unavailable; showing the queue from the analysis records: {}", e.getMessage());
                source = QueuePage.CLAIMS_SYSTEM_UNAVAILABLE;
            }
        }
        Page<ClaimEntity> result = segment == null || segment.isBlank()
                ? claims.findByStatusInOrderByCreatedAtAsc(statuses, PageRequest.of(page, size))
                : claims.findByStatusInAndSegmentOrderByCreatedAtAsc(statuses, segment, PageRequest.of(page, size));
        return new QueuePage(result.getContent().stream().map(QueueItem::of).toList(),
                page, size, result.getTotalElements(), result.getTotalPages(), source);
    }

    /**
     * The queue as the claims system holds it, with the AI's severity, flags and failure reason from the analysis
     * record. Includes claims with no analysis yet. Failed direct intakes (REJ-...), which the claims system never
     * held, follow its claims, so no claim disappears from a queue.
     */
    private QueuePage queueFromClaimsSystem(Set<String> statuses, String segment, int page, int size) {
        ClaimQueuePage filed = segment == null || segment.isBlank() ? claimCenter.listWorkQueues(statuses, page, size)
                : claimCenter.listWorkQueues(statuses, segment, page, size);
        Map<String, ClaimEntity> analysed = new HashMap<>();
        claims.findAllById(filed.items().stream().map(ClaimQueueEntry::claimId).toList())
                .forEach(c -> analysed.put(c.claimId, c));
        List<QueueItem> items = new ArrayList<>();
        for (ClaimQueueEntry e : filed.items()) {
            ClaimEntity c = analysed.get(e.claimId());
            // Analysed, but the claims system's queue was not updated: the record knows where it really stands.
            String status = c != null && ClaimsSystemIntake.ANALYSIS_PENDING.equals(e.workQueue()) ? c.status : e.workQueue();
            // No reserve is posted until a person decides: until then the queue shows the AI's recommendation.
            items.add(new QueueItem(e.claimId(), status, e.policyNumber(),
                    e.reserve() != null || c == null ? e.reserve() : c.reserve,
                    c == null ? null : c.severityGate, c == null ? null : c.inputFlags,
                    c == null ? null : c.gateFlags, c == null ? null : c.failureReason, e.createdAt(),
                    c != null && c.segment != null ? c.segment : e.segment(),
                    c != null && c.assignedTo != null ? c.assignedTo : e.assignedTo()));
        }
        // Failed direct intakes have no segment, so a segment filter leaves them out; the unfiltered queue always has them.
        List<ClaimEntity> unfiled = segment == null || segment.isBlank()
                ? claims.findByStatusInAndClaimIdStartingWithOrderByCreatedAtAsc(statuses, "REJ-") : List.of();
        long skip = Math.max(0, (long) page * size - filed.total());
        unfiled.stream().skip(skip).limit(size - items.size()).map(QueueItem::of).forEach(items::add);
        long total = filed.total() + unfiled.size();
        return new QueuePage(items, page, size, total, (int) Math.ceil((double) total / size), QueuePage.CLAIMS_SYSTEM);
    }

    // ── Evidence documents ────────────────────────────────────────────────────
    @GetMapping("/fnol/{claimId}/documents")
    ResponseEntity<?> documents(@PathVariable("claimId") String claimId) {
        if (!claims.existsById(claimId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(documents.findByClaimIdOrderByUploadedAtAscNameAsc(claimId));
    }

    /**
     * The stored image. Only JPEG/PNG/WebP/GIF are ever stored; nosniff and a no-script CSP stop the
     * browser from treating the bytes as anything else, and private caching keeps them off shared caches.
     */
    @GetMapping("/fnol/{claimId}/documents/{documentId}/content")
    ResponseEntity<byte[]> documentContent(@PathVariable("claimId") String claimId,
                                           @PathVariable("documentId") String documentId) {
        return documents.findByDocumentIdAndClaimId(documentId, claimId)
                .filter(ClaimDocumentEntity::isViewable)
                .map(doc -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(doc.contentType))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + doc.name + "\"")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", "default-src 'none'; sandbox")
                        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                        .body(doc.content))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Audit Trail (hash chain) ──────────────────────────────────────────────
    @GetMapping("/fnol/{claimId}/audit")
    ResponseEntity<?> audit(@PathVariable("claimId") String claimId) {
        if (!claims.existsById(claimId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(auditService.trail(claimId));
    }

    // ── Audit PDF Export ──────────────────────────────────────────────────────
    /**
     * Compliance export: generates a SHA-256 hashed PDF audit report for a claim,
     * containing claim details, the WHY panel, all audit trail events, and adjuster decision.
     */
    @GetMapping("/fnol/{claimId}/audit/pdf")
    ResponseEntity<byte[]> auditPdf(@PathVariable("claimId") String claimId) {
        return claims.findById(claimId)
                .<ResponseEntity<byte[]>>map(claim -> {
                    AuditService.AuditTrail trail = auditService.trail(claimId);
                    byte[] pdfBytes = pdfGenerator.generate(claim, trail.records(), trail.chainValid());
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"FNOL_Audit_" + claimId + ".pdf\"")
                            .contentType(MediaType.APPLICATION_PDF)
                            .body(pdfBytes);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Adjuster Review (Human Review Points 2 and 3) ─────────────────────────
    @PostMapping("/fnol/{claimId}/review")
    @Transactional
    ResponseEntity<?> review(
            @PathVariable("claimId") String claimId,
            @Valid @RequestBody AdjusterReviewRequest submitted) {

        // A decision is recorded under who is signed in, whatever name the request carries.
        AdjusterReviewRequest request = new AdjusterReviewRequest(submitted.decision(), Actor.idOr(submitted.adjusterId()),
                submitted.notes(), submitted.adjustedReserve(), submitted.overrideReason(), submitted.confirmedDamageType(),
                submitted.finalRepairCost(), submitted.repairCostSource(), submitted.handlingSeconds(), submitted.advisoryRating());
        return apply(claimId, request, true);
    }

    /**
     * Records a decision. With {@code checkAuthority}, a reserve above the person's authority is held for approval instead;
     * an approver's approval comes back here without the check.
     */
    ResponseEntity<?> apply(String claimId, AdjusterReviewRequest request, boolean checkAuthority) {
        String decision = request.decision().trim().toUpperCase(Locale.ROOT);
        if (!ADJUSTER_DECISIONS.contains(decision)) {
            throw new IllegalArgumentException("Unsupported adjuster decision: " + request.decision());
        }
        String costSource = request.repairCostSource() == null || request.repairCostSource().isBlank() ? null
                : request.repairCostSource().trim().toUpperCase(Locale.ROOT);
        if (costSource != null && !AdjusterReviewRequest.REPAIR_COST_SOURCES.contains(costSource)) {
            throw new IllegalArgumentException("repairCostSource must be one of "
                    + new TreeSet<>(AdjusterReviewRequest.REPAIR_COST_SOURCES));
        }
        // Human Review Point 3 "modify findings": an adjusted reserve must say why.
        if (request.adjustedReserve() != null
                && (request.overrideReason() == null || request.overrideReason().isBlank())) {
            throw new IllegalArgumentException("overrideReason is required when adjustedReserve is set");
        }
        // With no table row the recommendation is only the AI's figure, shown for reference: a person must set the reserve.
        if ("APPROVED_BY_ADJUSTER".equals(decision) && request.adjustedReserve() == null) {
            ClaimEntity flagged = claims.findById(claimId).orElse(null);
            if (flagged != null && flagged.gateFlags != null
                    && flagged.gateFlags.contains(GateConsistencyChecker.RESERVE_TABLE_ENTRY_MISSING)) {
                throw new IllegalArgumentException("The initial-reserve table has no row for this claim: set the reserve yourself");
            }
        }
        if (checkAuthority && approvals != null) {
            // Also a claim only the claims system holds, whose analysis did not finish: the limit applies to it just the same.
            ClaimEntity current = recordForDecision(claimId).orElse(null);
            String role = Actor.current().map(Actor::role).orElse(ReserveAuthorityRoles.SERVICE);
            BigDecimal posted = current == null ? null : reserveToPost(decision, request.adjustedReserve(), current);
            if (current != null && REVIEWABLE_STATUSES.contains(current.status) && !authority.allows(role, posted)) {
                if (reportsToClaimsSystem()) {
                    claimsSystemIntake.saveForDecision(current);
                }
                ReserveApprovalEntity held = approvals.request(claimId, decision, posted, current.status, request, role, costSource);
                Map<String, Object> answer = new LinkedHashMap<>();
                answer.put("claimId", claimId);
                answer.put("status", ReserveApprovalService.PENDING_APPROVAL);
                answer.put("approvalId", held.approvalId);
                answer.put("assignedTo", held.assignedTo);
                answer.put("amount", posted);
                answer.put("yourLimit", authority.limitOf(role));
                return ResponseEntity.accepted().body(answer);
            }
        }
        if (reportsToClaimsSystem() && !claimId.startsWith("REJ-")) {
            return reviewInClaimsSystem(claimId, decision, costSource, request);
        }
        ClaimEntity claim = claims.findById(claimId).orElse(null);
        if (claim == null) {
            return ResponseEntity.notFound().build();
        }

        String previousStatus = claim.status;
        Instant reviewedAt = Instant.now();
        int applied = claims.applyReview(claimId, decision, request.adjusterId(), request.notes(),
                request.adjustedReserve(), request.overrideReason(), reviewedAt, REVIEWABLE_STATUSES);
        if (applied == 0) {
            throw new InvalidStateException("Claim " + claimId + " is in status " + previousStatus
                    + "; a decision can only be recorded for " + String.join(", ", new TreeSet<>(REVIEWABLE_STATUSES)));
        }

        // Audit: who decided what; the free-text notes are stored only as a hash.
        auditService.append(claimId, List.of(auditAgent.record(claimId, "AdjusterReview",
                "adjusterId=" + request.adjusterId() + ";decision=" + decision
                        + ";notesSha256=" + AuditTrailAgent.sha256(Objects.toString(request.notes(), ""))
                        + ";aiReserve=" + claim.reserve + ";adjustedReserve=" + request.adjustedReserve()
                        + ";overrideReasonSha256=" + AuditTrailAgent.sha256(Objects.toString(request.overrideReason(), "")),
                "previousStatus=" + previousStatus + ";status=" + decision + ";reviewedAt=" + reviewedAt,
                decision)), request.adjusterId());

        ClaimEntity updated = claims.findById(claimId).orElseThrow();
        updated.ccSyncStatus = syncDecisionToClaimCenter(updated, decision, request.adjusterId(), request.notes(),
                request.adjustedReserve());
        claims.save(updated);
        // Feedback loop: the decision next to what the AI said (metrics and new labelled data).
        if (governance != null) {
            governance.recordDecision(updated, decision, request.adjusterId(), request.adjustedReserve(),
                    request.confirmedDamageType(), request.finalRepairCost(), costSource);
            recordUsage(claimId, request);
        }
        return ResponseEntity.ok(new AdjusterReviewResponse(
                updated.claimId,
                updated.status,
                updated.adjusterDecision,
                updated.adjusterId,
                updated.adjusterNotes,
                updated.reviewedAt,
                updated.adjusterReserve,
                updated.overrideReason
        ));
    }

    private Optional<ClaimEntity> recordForDecision(String claimId) {
        return claims.findById(claimId).or(() -> reportsToClaimsSystem() ? claimsSystemIntake.recordOf(claimId) : Optional.empty());
    }

    /** Timing and usefulness count only from a signed-in person, so scripts and integrations cannot skew the evidence. */
    private void recordUsage(String claimId, AdjusterReviewRequest request) {
        if (governance != null && Actor.current().isPresent()) {
            governance.recordUsage(claimId, request.handlingSeconds(), request.advisoryRating());
        }
    }

    /**
     * The decision is recorded in the claims system first, which owns the claim and announces it (claim.decided). Only
     * then does the analysis record follow. If the claims system cannot take it, nothing is recorded anywhere and the
     * adjuster is told to try again, so the two can never disagree about a decision.
     */
    private ResponseEntity<?> reviewInClaimsSystem(String claimId, String decision, String costSource,
                                                   AdjusterReviewRequest request) {
        ClaimEntity claim = recordForDecision(claimId).orElse(null);
        if (claim == null) {
            return ResponseEntity.notFound().build();
        }
        if (!REVIEWABLE_STATUSES.contains(claim.status)) {
            throw new InvalidStateException("Claim " + claimId + " is in status " + claim.status
                    + "; a decision can only be recorded for " + String.join(", ", new TreeSet<>(REVIEWABLE_STATUSES)));
        }
        claimsSystemIntake.saveForDecision(claim);
        Claim recorded;
        try {
            recorded = claimCenter.recordDecision(claimId, new ClaimUpdateRequest(CLAIMCENTER_STATUS.get(decision),
                    request.notes(), decision, request.adjusterId(),
                    reserveToPost(decision, request.adjustedReserve(), claim), decision, request.overrideReason(), request.confirmedDamageType(), request.finalRepairCost(), costSource));
        } catch (HttpClientErrorException.Conflict | HttpClientErrorException.NotFound e) {
            throw new InvalidStateException("The claims system refused this decision on claim " + claimId
                    + "; refresh the claim to see where it stands");
        } catch (ResourceAccessException | HttpServerErrorException e) {
            log.error("[FNOL] Decision on {} not recorded: the claims system could not be reached: {}", claimId, e.getMessage());
            throw new ClaimsSystemUnavailableException();
        }
        List<ClaimReviewAction> actions = recorded.reviewActions();
        Instant decidedAt = actions == null || actions.isEmpty() ? Instant.now() : actions.get(actions.size() - 1).at();
        decisions.apply(new ClaimDecisionRecorder.Decision(claimId, decision, request.adjusterId(), request.notes(),
                request.adjustedReserve(), request.overrideReason(), request.confirmedDamageType(),
                request.finalRepairCost(), costSource, decidedAt));
        recordUsage(claimId, request);
        ClaimEntity updated = claims.findById(claimId).orElseThrow();
        return ResponseEntity.ok(new AdjusterReviewResponse(updated.claimId, updated.status, updated.adjusterDecision,
                updated.adjusterId, updated.adjusterNotes, updated.reviewedAt, updated.adjusterReserve,
                updated.overrideReason));
    }
}

/** Adjuster queue entry (GET /api/v1/fnol/queue). */
record QueueItem(String claimId, String status, String policyNumber, java.math.BigDecimal reserve,
                 String severityGate, String inputFlags, String gateFlags, String failureReason, Instant createdAt,
                 String segment, String assignedTo) {
    static QueueItem of(ClaimEntity c) {
        return new QueueItem(c.claimId, c.status, c.policyNumber, c.reserve, c.severityGate,
                c.inputFlags, c.gateFlags, c.failureReason, c.createdAt, c.segment, c.assignedTo);
    }
}

record QueuePage(List<QueueItem> items, int page, int size, long totalElements, int totalPages, String source) {
    /** Read from the claims system. */
    static final String CLAIMS_SYSTEM = "CLAIMS_SYSTEM";
    /** Claims-system mode, but it could not be reached: the engine's analysis records, possibly behind. */
    static final String CLAIMS_SYSTEM_UNAVAILABLE = "CLAIMS_SYSTEM_UNAVAILABLE";
    /** Direct intake: the engine's own records. */
    static final String ENGINE = "ENGINE";
}

class DuplicateFNOLException extends RuntimeException {
    public DuplicateFNOLException(String message) { super(message); }
}

class ClaimIdConflictException extends RuntimeException {
    public ClaimIdConflictException(String message) { super(message); }
}

class InvalidStateException extends RuntimeException {
    public InvalidStateException(String message) { super(message); }
}

@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> bad(Exception e, HttpServletRequest r) {
        return validation(e.getMessage(), r);
    }

    /** Names the fields only: Spring's own message lists Java class and method names, and the portal is public. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidFields(MethodArgumentNotValidException e, HttpServletRequest r) {
        String fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).sorted().distinct()
                .collect(java.util.stream.Collectors.joining("; "));
        return validation("Invalid request: " + (fields.isEmpty() ? "check the fields sent" : fields), r);
    }

    /** Missing or malformed JSON body: a client error, not a processing failure. */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(Exception e, HttpServletRequest r) {
        return validation("The request body is missing or is not valid JSON.", r);
    }

    private static ResponseEntity<ApiError> validation(String message, HttpServletRequest r) {
        return ResponseEntity.badRequest().body(
                new ApiError(Instant.now(), 400, "VALIDATION_ERROR", message, r.getRequestURI()));
    }

    @ExceptionHandler(DuplicateFNOLException.class)
    ResponseEntity<ApiError> duplicate(DuplicateFNOLException e, HttpServletRequest r) {
        return ResponseEntity.status(409).body(
                new ApiError(Instant.now(), 409, "DUPLICATE_FNOL", e.getMessage(), r.getRequestURI(), escalatedId(r)));
    }

    @ExceptionHandler(ClaimsSystemUnavailableException.class)
    ResponseEntity<ApiError> claimsSystemDown(ClaimsSystemUnavailableException e, HttpServletRequest r) {
        return ResponseEntity.status(503).body(
                new ApiError(Instant.now(), 503, "CLAIMS_SYSTEM_UNAVAILABLE", e.getMessage(), r.getRequestURI()));
    }

    @ExceptionHandler(ClaimIdConflictException.class)
    ResponseEntity<ApiError> claimIdConflict(ClaimIdConflictException e, HttpServletRequest r) {
        return ResponseEntity.status(409).body(
                new ApiError(Instant.now(), 409, "CLAIM_ID_CONFLICT", e.getMessage(), r.getRequestURI()));
    }

    @ExceptionHandler(InvalidStateException.class)
    ResponseEntity<ApiError> invalidState(InvalidStateException e, HttpServletRequest r) {
        return ResponseEntity.status(409).body(
                new ApiError(Instant.now(), 409, "INVALID_STATE", e.getMessage(), r.getRequestURI()));
    }

    @ExceptionHandler(ComplianceBlockException.class)
    ResponseEntity<ApiError> compliance(ComplianceBlockException e, HttpServletRequest r) {
        return ResponseEntity.status(422).body(
                new ApiError(Instant.now(), 422, "COMPLIANCE_BLOCK", e.getMessage(), r.getRequestURI(), escalatedId(r)));
    }

    @ExceptionHandler(CoverageNotEligibleException.class)
    ResponseEntity<ApiError> coverage(CoverageNotEligibleException e, HttpServletRequest r) {
        return ResponseEntity.status(502).body(
                new ApiError(Instant.now(), 502, "EXTERNAL_OR_PROCESSING_ERROR",
                        e.getMessage() + ". Claim escalated for manual review.", r.getRequestURI(), escalatedId(r)));
    }

    @ExceptionHandler({ProcessingTimeoutException.class, org.springframework.web.client.ResourceAccessException.class})
    ResponseEntity<ApiError> timeout(Exception e, HttpServletRequest r) {
        return ResponseEntity.status(504).body(
                new ApiError(Instant.now(), 504, "PROCESSING_TIMEOUT",
                        "FNOL processing timed out. Claim escalated for manual review.", r.getRequestURI(), escalatedId(r)));
    }

    private static String escalatedId(HttpServletRequest r) {
        return (String) r.getAttribute(FNOLController.ESCALATED_CLAIM_ID);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> server(Exception e, HttpServletRequest r) {
        log.error("[FNOL] Unhandled error on {}", r.getRequestURI(), e);
        String escalated = escalatedId(r);
        return ResponseEntity.status(502).body(
                new ApiError(Instant.now(), 502, "EXTERNAL_OR_PROCESSING_ERROR",
                        escalated != null ? "FNOL processing could not be completed. Claim escalated for manual review."
                                : "FNOL processing could not be completed", r.getRequestURI(), escalated));
    }
}
