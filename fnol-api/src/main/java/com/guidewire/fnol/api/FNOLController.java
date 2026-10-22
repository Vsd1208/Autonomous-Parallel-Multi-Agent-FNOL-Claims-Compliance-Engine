
package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.common.ProcessingTimeoutException;
import com.guidewire.fnol.common.ComplianceBlockException;
import com.guidewire.fnol.orchestrator.*;
import com.guidewire.fnol.api.persistence.*;
import com.guidewire.fnol.api.pdf.AuditPdfGenerator;

import jakarta.servlet.http.*;
import jakarta.validation.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1")
public class FNOLController {

    private final FNOLOrchestrator orchestrator;
    private final ClaimRepository claims;
    private final AuditRepository audits;
    private final IdempotencyStore idempotencyStore;
    private final AuditPdfGenerator pdfGenerator;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public FNOLController(
            FNOLOrchestrator orchestrator,
            ClaimRepository claims,
            AuditRepository audits,
            IdempotencyStore idempotencyStore,
            AuditPdfGenerator pdfGenerator) {
        this.orchestrator      = orchestrator;
        this.claims            = claims;
        this.audits            = audits;
        this.idempotencyStore  = idempotencyStore;
        this.pdfGenerator      = pdfGenerator;
    }

    // ── Health ──────────────────────────────────────────────────────────────
    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }

    // ── FNOL Intake ─────────────────────────────────────────────────────────
    @PostMapping("/fnol/intake")
    FNOLResponse intake(@Valid @RequestBody FNOLPayload payload) {

        String policyNumber  = payload.policyNumber();
        LocalDate incidentDate = payload.incidentDate();
        String state         = payload.state();

        // Layer 1 + 2: Duplicate FNOL check (in-memory + DB-backed)
        if (!idempotencyStore.tryAcquire(policyNumber, incidentDate, state)) {
            throw new DuplicateFNOLException("This FNOL has already been submitted");
        }

        try {
            FNOLResponse response = orchestrator.process(payload);

            // Persist claim (includes explanationJson and channel)
            claims.save(ClaimEntity.from(response, payload));

            // Persist audit trail records
            response.branchB().audit()
                    .forEach(a -> audits.save(AuditEntity.from(a)));

            return response;
        } catch (Exception e) {
            idempotencyStore.release(policyNumber, incidentDate.toString(), state);
            throw e;
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

    // ── Audit PDF Export ──────────────────────────────────────────────────────
    /**
     * Compliance export: generates a SHA-256 hashed PDF audit report for a claim,
     * containing claim details, the WHY panel, all audit trail events, and adjuster decision.
     */
    @GetMapping("/fnol/{claimId}/audit/pdf")
    ResponseEntity<byte[]> auditPdf(@PathVariable("claimId") String claimId) {
        return claims.findById(claimId)
                .<ResponseEntity<byte[]>>map(claim -> {
                    List<AuditEntity> auditRecords = audits.findByClaimId(claimId);
                    byte[] pdfBytes = pdfGenerator.generate(claim, auditRecords);
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"FNOL_Audit_" + claimId + ".pdf\"")
                            .contentType(MediaType.APPLICATION_PDF)
                            .body(pdfBytes);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Adjuster Review (Human Review Point 3) ────────────────────────────────
    @PostMapping("/fnol/{claimId}/review")
    ResponseEntity<?> review(
            @PathVariable("claimId") String claimId,
            @Valid @RequestBody AdjusterReviewRequest request) {

        return claims.findById(claimId)
                .<ResponseEntity<?>>map(claim -> {
                    String decision = request.decision().trim().toUpperCase(Locale.ROOT);
                    Set<String> allowed = Set.of(
                            "APPROVED_BY_ADJUSTER",
                            "REJECTED_BY_ADJUSTER",
                            "NEEDS_MORE_INFO",
                            "CLOSED"
                    );
                    if (!allowed.contains(decision)) {
                        throw new IllegalArgumentException(
                                "Unsupported adjuster decision: " + request.decision());
                    }

                    claim.adjusterDecision = decision;
                    claim.adjusterId       = request.adjusterId();
                    claim.adjusterNotes    = request.notes();
                    claim.reviewedAt       = Instant.now();
                    claim.updatedAt        = claim.reviewedAt;
                    claim.status           = decision;
                    claims.save(claim);

                    return ResponseEntity.ok(new AdjusterReviewResponse(
                            claim.claimId,
                            claim.status,
                            claim.adjusterDecision,
                            claim.adjusterId,
                            claim.adjusterNotes,
                            claim.reviewedAt
                    ));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

class DuplicateFNOLException extends RuntimeException {
    public DuplicateFNOLException(String message) { super(message); }
}

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ApiError> bad(Exception e, HttpServletRequest r) {
        return ResponseEntity.badRequest().body(
                new ApiError(Instant.now(), 400, "VALIDATION_ERROR", e.getMessage(), r.getRequestURI()));
    }

    @ExceptionHandler(DuplicateFNOLException.class)
    ResponseEntity<ApiError> duplicate(DuplicateFNOLException e, HttpServletRequest r) {
        return ResponseEntity.status(409).body(
                new ApiError(Instant.now(), 409, "DUPLICATE_FNOL", e.getMessage(), r.getRequestURI()));
    }

    @ExceptionHandler(ComplianceBlockException.class)
    ResponseEntity<ApiError> compliance(ComplianceBlockException e, HttpServletRequest r) {
        return ResponseEntity.status(422).body(
                new ApiError(Instant.now(), 422, "COMPLIANCE_BLOCK", e.getMessage(), r.getRequestURI()));
    }

    @ExceptionHandler({ProcessingTimeoutException.class, org.springframework.web.client.ResourceAccessException.class})
    ResponseEntity<ApiError> timeout(Exception e, HttpServletRequest r) {
        return ResponseEntity.status(504).body(
                new ApiError(Instant.now(), 504, "PROCESSING_TIMEOUT",
                        "FNOL processing timed out. Claim escalated for manual review.", r.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> server(Exception e, HttpServletRequest r) {
        e.printStackTrace();
        return ResponseEntity.status(502).body(
                new ApiError(Instant.now(), 502, "EXTERNAL_OR_PROCESSING_ERROR",
                        "FNOL processing could not be completed", r.getRequestURI()));
    }
}
