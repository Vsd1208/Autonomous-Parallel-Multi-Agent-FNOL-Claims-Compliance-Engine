package com.guidewire.fnol.api;

import com.guidewire.fnol.agents.AuditTrailAgent;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Brings the engine's analysis record up to date with a decision the claims system has recorded: the status, the audit
 * trail and the AI's feedback loop. It reaches here twice for a decision made in the workspace (directly, and by its
 * claim.decided event) and once for a decision made in the claims system itself; the decision's time, set by the
 * claims system, makes it count once. The claims system has already decided, so the engine follows and cannot refuse.
 */
@Component
class ClaimDecisionRecorder {

    record Decision(String claimId, String decision, String adjusterId, String notes, BigDecimal adjustedReserve,
                    String overrideReason, String confirmedDamageType, BigDecimal finalRepairCost,
                    String repairCostSource, Instant decidedAt) {}

    private final ClaimRepository claims;
    private final AuditService auditService;
    private final AuditTrailAgent auditAgent;
    private AiGovernanceService governance;

    ClaimDecisionRecorder(ClaimRepository claims, AuditService auditService, AuditTrailAgent auditAgent) {
        this.claims = claims;
        this.auditService = auditService;
        this.auditAgent = auditAgent;
    }

    @Autowired(required = false)
    void setGovernance(AiGovernanceService governance) {
        this.governance = governance;
    }

    /** @return true if applied, false if this decision or a later one is already on the record */
    @Transactional
    public boolean apply(Decision d) {
        String previousStatus = claims.findById(d.claimId()).map(c -> c.status).orElse(null);
        int applied = claims.applyClaimsSystemDecision(d.claimId(), d.decision(), d.adjusterId(), d.notes(),
                d.adjustedReserve(), d.overrideReason(), d.decidedAt());
        if (applied == 0) {
            return false;
        }
        ClaimEntity claim = claims.findById(d.claimId()).orElseThrow();
        BigDecimal aiReserve = claim.reserve;
        // Audit: who decided what; the free-text notes are stored only as a hash.
        auditService.append(d.claimId(), List.of(auditAgent.record(d.claimId(), "AdjusterReview",
                "adjusterId=" + d.adjusterId() + ";decision=" + d.decision() + ";recordedIn=claims-system"
                        + ";notesSha256=" + AuditTrailAgent.sha256(Objects.toString(d.notes(), ""))
                        + ";aiReserve=" + aiReserve + ";adjustedReserve=" + d.adjustedReserve()
                        + ";overrideReasonSha256=" + AuditTrailAgent.sha256(Objects.toString(d.overrideReason(), "")),
                "previousStatus=" + previousStatus + ";status=" + d.decision() + ";reviewedAt=" + d.decidedAt(),
                d.decision())), d.adjusterId());
        // Feedback loop: the decision next to what the AI said (metrics and new labelled data).
        if (governance != null) {
            governance.recordDecision(claim, d.decision(), d.adjusterId(), d.adjustedReserve(),
                    d.confirmedDamageType(), d.finalRepairCost(), d.repairCostSource());
        }
        return true;
    }
}
