
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

    /** Adjuster decision → ClaimCenter status. Approval moves the draft into the standard claim process. */
    static final Map<String, String> CLAIMCENTER_STATUS = Map.of(
            "APPROVED_BY_ADJUSTER", "OPEN",
            "REJECTED_BY_ADJUSTER", "DENIED",
            "NEEDS_MORE_INFO", "PENDING_INFORMATION",
            "INVESTIGATION_REQUIRED", "UNDER_INVESTIGATION",
            "CLOSED", "CLOSED");
    static final Set<String> ADJUSTER_DECISIONS = CLAIMCENTER_STATUS.keySet();

    // ── Health ──────────────────────────────────────────────────────────────
    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }

    // ── FNOL Intake ─────────────────────────────────────────────────────────
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
        evidence.store(claimId, payload.evidenceUris());
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
        return claims.findById(claimId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
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
                    notes, decision, adjusterId, adjustedReserve));
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
                    @RequestParam(name = "size", defaultValue = "20") int size) {
        Set<String> statuses = new TreeSet<>();
        for (String s : status.split(",")) {
            if (!s.isBlank()) statuses.add(s.trim().toUpperCase(Locale.ROOT));
        }
        if (statuses.isEmpty() || page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("status must be non-empty, page >= 0 and size between 1 and 100");
        }
        Page<ClaimEntity> result = claims.findByStatusInOrderByCreatedAtAsc(statuses, PageRequest.of(page, size));
        return new QueuePage(result.getContent().stream().map(QueueItem::of).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
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
            @Valid @RequestBody AdjusterReviewRequest request) {

        String decision = request.decision().trim().toUpperCase(Locale.ROOT);
        if (!ADJUSTER_DECISIONS.contains(decision)) {
            throw new IllegalArgumentException("Unsupported adjuster decision: " + request.decision());
        }
        // Human Review Point 3 "modify findings": an adjusted reserve must say why.
        if (request.adjustedReserve() != null
                && (request.overrideReason() == null || request.overrideReason().isBlank())) {
            throw new IllegalArgumentException("overrideReason is required when adjustedReserve is set");
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
}

/** Adjuster queue entry (GET /api/v1/fnol/queue). */
record QueueItem(String claimId, String status, String policyNumber, java.math.BigDecimal reserve,
                 String severityGate, String inputFlags, String gateFlags, String failureReason, Instant createdAt) {
    static QueueItem of(ClaimEntity c) {
        return new QueueItem(c.claimId, c.status, c.policyNumber, c.reserve, c.severityGate,
                c.inputFlags, c.gateFlags, c.failureReason, c.createdAt);
    }
}

record QueuePage(List<QueueItem> items, int page, int size, long totalElements, int totalPages) {}

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

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ApiError> bad(Exception e, HttpServletRequest r) {
        return ResponseEntity.badRequest().body(
                new ApiError(Instant.now(), 400, "VALIDATION_ERROR", e.getMessage(), r.getRequestURI()));
    }

    @ExceptionHandler(DuplicateFNOLException.class)
    ResponseEntity<ApiError> duplicate(DuplicateFNOLException e, HttpServletRequest r) {
        return ResponseEntity.status(409).body(
                new ApiError(Instant.now(), 409, "DUPLICATE_FNOL", e.getMessage(), r.getRequestURI(), escalatedId(r)));
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
