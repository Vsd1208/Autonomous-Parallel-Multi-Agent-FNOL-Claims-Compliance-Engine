package com.guidewire.fnol.api;

import com.guidewire.fnol.agents.AuditTrailAgent;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.api.persistence.InspectionEntity;
import com.guidewire.fnol.api.persistence.InspectionRepository;
import com.guidewire.fnol.common.Models.ClaimUpdateRequest;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Damage inspection (appraisal): when the photos cannot settle a claim (the amount claimed is above the AI
 * estimate, damage may predate this accident, the photo is poor), the adjuster assigns an inspector. The
 * claim waits in INSPECTION_ASSIGNED; the inspector's report returns it to HUMAN_REVIEW with the cost of
 * the damage this accident caused, and the adjuster decides.
 */
@RestController
@RequestMapping("/api/v1")
class InspectionController {

    private static final Logger log = LoggerFactory.getLogger(InspectionController.class);

    static final String INSPECTION_ASSIGNED = "INSPECTION_ASSIGNED";
    static final Set<String> TYPES = Set.of("FIELD", "DESK", "SHOP");
    static final Set<String> RECOMMENDATIONS = Set.of("APPROVE", "REDUCE", "DENY", "INVESTIGATE");
    /** Days the inspector has to report, per inspection type (prototype service levels). */
    static final Map<String, Integer> DAYS_TO_REPORT = Map.of("DESK", 1, "SHOP", 2, "FIELD", 3);

    /** Synthetic inspector roster (prototype data; a real system reads this from the vendor directory). */
    record Inspector(String inspectorId, String name, String specialty, String region) {}

    static final List<Inspector> INSPECTORS = List.of(
            new Inspector("INS-101", "Inspector A (Field appraiser)", "Auto body", "CA"),
            new Inspector("INS-102", "Inspector B (Desk appraiser)", "Auto body", "All states"),
            new Inspector("INS-201", "Inspector C (Property adjuster)", "Home / water damage", "TX"),
            new Inspector("INS-301", "Partner body shop network", "Auto body (shop estimate)", "All states"));

    record AssignRequest(@NotBlank String inspectorId, @NotBlank String inspectionType,
                         @NotBlank @Size(max = 500) String reason, @NotBlank String assignedBy) {}

    record ReportRequest(@NotBlank String inspectorId, @NotNull @PositiveOrZero BigDecimal accidentRelatedAmount,
                         @NotNull Boolean priorDamageFound, @NotBlank @Size(max = 2000) String findings,
                         @NotBlank String recommendation) {}

    private final ClaimRepository claims;
    private final InspectionRepository inspections;
    private final AuditService auditService;
    private final AuditTrailAgent auditAgent;
    private final ClaimCenterClient claimCenter;

    InspectionController(ClaimRepository claims, InspectionRepository inspections, AuditService auditService,
                         AuditTrailAgent auditAgent, ClaimCenterClient claimCenter) {
        this.claims = claims;
        this.inspections = inspections;
        this.auditService = auditService;
        this.auditAgent = auditAgent;
        this.claimCenter = claimCenter;
    }

    @GetMapping("/inspectors")
    List<Inspector> inspectors() {
        return INSPECTORS;
    }

    @GetMapping("/fnol/{claimId}/inspections")
    ResponseEntity<?> list(@PathVariable("claimId") String claimId) {
        if (!claims.existsById(claimId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(inspections.findByClaimIdOrderByAssignedAtAsc(claimId));
    }

    /** Assigns an inspector; only from a status where the adjuster could otherwise decide. */
    @PostMapping("/fnol/{claimId}/inspections")
    @Transactional
    ResponseEntity<?> assign(@PathVariable("claimId") String claimId, @Valid @RequestBody AssignRequest request) {
        String type = request.inspectionType().trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("inspectionType must be one of " + new TreeSet<>(TYPES));
        }
        Inspector inspector = INSPECTORS.stream().filter(i -> i.inspectorId().equals(request.inspectorId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown inspector " + request.inspectorId()));
        ClaimEntity claim = claims.findById(claimId).orElse(null);
        if (claim == null) {
            return ResponseEntity.notFound().build();
        }
        if (claimId.startsWith("REJ-")) {
            throw new InvalidStateException("Claim " + claimId + " was not filed; it cannot be inspected");
        }
        Instant now = Instant.now();
        if (claims.moveStatus(claimId, FNOLController.REVIEWABLE_STATUSES, INSPECTION_ASSIGNED, now) == 0) {
            throw new InvalidStateException("Claim " + claimId + " is in status " + claim.status
                    + "; an inspector can only be assigned while the claim awaits a decision");
        }

        InspectionEntity i = new InspectionEntity();
        i.inspectionId = UUID.randomUUID().toString();
        i.claimId = claimId;
        i.inspectorId = inspector.inspectorId();
        i.inspectorName = inspector.name();
        i.inspectionType = type;
        i.reason = request.reason().trim();
        i.status = "ASSIGNED";
        i.assignedBy = request.assignedBy().trim();
        i.assignedAt = now;
        i.dueBy = LocalDate.now().plusDays(DAYS_TO_REPORT.get(type));
        inspections.save(i);

        audit(claimId, "InspectionAssignment",
                "inspectionId=" + i.inspectionId + ";type=" + type + ";inspector=" + i.inspectorId
                        + ";reasonSha256=" + AuditTrailAgent.sha256(i.reason),
                "previousStatus=" + claim.status + ";status=" + INSPECTION_ASSIGNED + ";dueBy=" + i.dueBy,
                "ASSIGNED", i.assignedBy);
        syncClaimCenter(claimId, "PENDING_INSPECTION", "INSPECTION_ASSIGNED", i.assignedBy,
                "Inspection (" + type + ") assigned to " + i.inspectorName + ", due " + i.dueBy + ": " + i.reason, null);
        log.info("[FNOL] Inspection {} assigned on {} to {} ({}), due {}", i.inspectionId, claimId, i.inspectorId, type, i.dueBy);
        return ResponseEntity.ok(i);
    }

    /** The inspector's report: returns the claim to HUMAN_REVIEW with the inspected, accident-related cost. */
    @PostMapping("/fnol/{claimId}/inspections/{inspectionId}/report")
    @Transactional
    ResponseEntity<?> report(@PathVariable("claimId") String claimId, @PathVariable("inspectionId") String inspectionId,
                             @Valid @RequestBody ReportRequest request) {
        String recommendation = request.recommendation().trim().toUpperCase(Locale.ROOT);
        if (!RECOMMENDATIONS.contains(recommendation)) {
            throw new IllegalArgumentException("recommendation must be one of " + new TreeSet<>(RECOMMENDATIONS));
        }
        InspectionEntity i = inspections.findByInspectionIdAndClaimId(inspectionId, claimId).orElse(null);
        if (i == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"ASSIGNED".equals(i.status)) {
            throw new InvalidStateException("Inspection " + inspectionId + " is " + i.status + "; it cannot be reported");
        }
        if (!i.inspectorId.equals(request.inspectorId())) {
            throw new InvalidStateException("Inspection " + inspectionId + " is assigned to " + i.inspectorId
                    + ", not " + request.inspectorId());
        }
        ClaimEntity claim = claims.findById(claimId).orElseThrow();
        Instant now = Instant.now();
        claims.moveStatus(claimId, Set.of(INSPECTION_ASSIGNED), "HUMAN_REVIEW", now);

        i.status = "COMPLETED";
        i.completedAt = now;
        i.accidentRelatedAmount = request.accidentRelatedAmount();
        i.priorDamageFound = request.priorDamageFound();
        i.findings = request.findings().trim();
        i.recommendation = recommendation;
        i.recommendedPayment = recommendedPayment(claim, request.accidentRelatedAmount());
        inspections.save(i);

        audit(claimId, "InspectionReport",
                "inspectionId=" + inspectionId + ";inspector=" + i.inspectorId
                        + ";findingsSha256=" + AuditTrailAgent.sha256(i.findings),
                "accidentRelatedAmount=" + i.accidentRelatedAmount + ";priorDamageFound=" + i.priorDamageFound
                        + ";recommendation=" + recommendation + ";recommendedPayment=" + i.recommendedPayment
                        + ";status=HUMAN_REVIEW",
                recommendation, i.inspectorId);
        syncClaimCenter(claimId, "DRAFT_PENDING_REVIEW", "INSPECTION_COMPLETED", i.inspectorId,
                "Inspection report: accident-related damage $" + i.accidentRelatedAmount
                        + (Boolean.TRUE.equals(i.priorDamageFound) ? " (earlier damage excluded)" : "")
                        + "; recommendation " + recommendation, null);
        return ResponseEntity.ok(i);
    }

    /** Withdraws an open assignment (wrong inspector, claimant withdrew); the claim goes back to HUMAN_REVIEW. */
    @PostMapping("/fnol/{claimId}/inspections/{inspectionId}/cancel")
    @Transactional
    ResponseEntity<?> cancel(@PathVariable("claimId") String claimId, @PathVariable("inspectionId") String inspectionId,
                             @RequestBody Map<String, String> body) {
        String adjusterId = Objects.toString(body.get("adjusterId"), "").trim();
        if (adjusterId.isEmpty()) {
            throw new IllegalArgumentException("adjusterId is required");
        }
        InspectionEntity i = inspections.findByInspectionIdAndClaimId(inspectionId, claimId).orElse(null);
        if (i == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"ASSIGNED".equals(i.status)) {
            throw new InvalidStateException("Inspection " + inspectionId + " is " + i.status + "; it cannot be cancelled");
        }
        Instant now = Instant.now();
        claims.moveStatus(claimId, Set.of(INSPECTION_ASSIGNED), "HUMAN_REVIEW", now);
        i.status = "CANCELLED";
        i.completedAt = now;
        inspections.save(i);
        audit(claimId, "InspectionCancelled", "inspectionId=" + inspectionId, "status=HUMAN_REVIEW", "CANCELLED", adjusterId);
        syncClaimCenter(claimId, "DRAFT_PENDING_REVIEW", "INSPECTION_CANCELLED", adjusterId,
                "Inspection by " + i.inspectorName + " cancelled", null);
        return ResponseEntity.ok(i);
    }

    /** Same rule as intake: the lower of the claimed and the inspected amount, less the deductible. */
    static BigDecimal recommendedPayment(ClaimEntity claim, BigDecimal accidentRelated) {
        BigDecimal basis = claim.claimedAmount != null ? claim.claimedAmount.min(accidentRelated) : accidentRelated;
        BigDecimal deductible = claim.deductible == null ? BigDecimal.ZERO : claim.deductible;
        return basis.subtract(deductible).max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP);
    }

    private void audit(String claimId, String agent, String input, String output, String status, String actor) {
        auditService.append(claimId, List.of(auditAgent.record(claimId, agent, input, output, status)), actor);
    }

    /** ClaimCenter is told about each step; an outage is logged and marked FAILED, never blocks the step. */
    private void syncClaimCenter(String claimId, String ccStatus, String action, String actor, String notes,
                                 BigDecimal reserve) {
        ClaimEntity claim = claims.findById(claimId).orElseThrow();
        try {
            claimCenter.updateClaim(claimId, new ClaimUpdateRequest(ccStatus, notes, action, actor, reserve));
            claim.ccSyncStatus = "SYNCED";
        } catch (RuntimeException e) {
            log.warn("[FNOL] ClaimCenter update failed for claimId={} ({}); cc_sync_status=FAILED: {}",
                    claimId, action, e.getMessage());
            claim.ccSyncStatus = "FAILED";
        }
        claims.save(claim);
    }
}
