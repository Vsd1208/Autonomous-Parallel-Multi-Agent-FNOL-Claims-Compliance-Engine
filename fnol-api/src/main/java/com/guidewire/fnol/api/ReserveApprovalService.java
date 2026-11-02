package com.guidewire.fnol.api;

import com.guidewire.fnol.agents.AuditTrailAgent;
import com.guidewire.fnol.api.persistence.AppUserEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.api.persistence.ReserveApprovalEntity;
import com.guidewire.fnol.api.persistence.ReserveApprovalRepository;
import com.guidewire.fnol.api.security.Actor;
import com.guidewire.fnol.api.security.ReserveAuthority;
import com.guidewire.fnol.common.Models.AdjusterReviewRequest;
import com.guidewire.fnol.common.Models.CcActivity;
import com.guidewire.fnol.common.Models.ClaimUpdateRequest;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A decision whose reserve is above the person's authority is not rejected: it waits as PENDING_APPROVAL, with nothing posted,
 * for someone with enough authority. Their approval completes it. A person can never approve their own request. Modelled on
 * ClaimCenter's authority-limit concept, not verified against a tenant.
 */
@Service
public class ReserveApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ReserveApprovalService.class);
    static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    static final String PENDING = "PENDING";
    static final String APPROVED = "APPROVED";
    static final String DECLINED = "DECLINED";

    private final ReserveApprovalRepository approvals;
    private final ClaimRepository claims;
    private final ReserveAuthority authority;
    private final AuditService audit;
    private final AuditTrailAgent auditAgent;
    private final ClaimCenterClient claimCenter;

    public ReserveApprovalService(ReserveApprovalRepository approvals, ClaimRepository claims, ReserveAuthority authority,
                                  AuditService audit, AuditTrailAgent auditAgent, ClaimCenterClient claimCenter) {
        this.approvals = approvals;
        this.claims = claims;
        this.authority = authority;
        this.audit = audit;
        this.auditAgent = auditAgent;
        this.claimCenter = claimCenter;
    }

    /** The decision is held; nothing is posted. Runs in the caller's transaction. */
    ReserveApprovalEntity request(String claimId, String decision, BigDecimal amount, String previousStatus,
                                  AdjusterReviewRequest request, String role, String costSource) {
        Instant now = Instant.now();
        if (claims.moveStatus(claimId, Set.of(previousStatus), PENDING_APPROVAL, now) == 0) {
            throw new InvalidStateException("Claim " + claimId + " changed while the decision was being recorded; refresh it");
        }
        ReserveApprovalEntity a = new ReserveApprovalEntity();
        a.approvalId = UUID.randomUUID().toString();
        a.claimId = claimId;
        a.decision = decision;
        a.amount = amount;
        a.previousStatus = previousStatus;
        a.requestedBy = request.adjusterId();
        a.requestedRole = role;
        a.requestedAt = now;
        a.assignedTo = approverFor(amount, request.adjusterId());
        a.notes = request.notes();
        a.adjustedReserve = request.adjustedReserve();
        a.overrideReason = request.overrideReason();
        a.confirmedDamageType = request.confirmedDamageType();
        a.finalRepairCost = request.finalRepairCost();
        a.repairCostSource = costSource;
        a.handlingSeconds = request.handlingSeconds();
        a.advisoryRating = request.advisoryRating();
        a.status = PENDING;
        approvals.save(a);
        audit.append(claimId, List.of(auditAgent.record(claimId, "ReserveApprovalRequested",
                "requestedBy=" + a.requestedBy + ";role=" + role + ";decision=" + decision + ";amount=" + amount,
                "status=" + PENDING_APPROVAL + ";assignedTo=" + a.assignedTo + ";nothingPosted=true", PENDING)), a.requestedBy);
        sync(claimId, a.requestedBy, "RESERVE_APPROVAL_REQUESTED", "Reserve of $" + amount + " awaits approval", PENDING_APPROVAL,
                new CcActivity(null, "Approve reserve of $" + amount,
                        a.requestedBy + " (" + role + ") decided " + decision + " with a reserve above their authority. "
                                + "Nothing is posted until this is approved.", "high", LocalDate.now().plusDays(1),
                        a.assignedTo != null ? a.assignedTo : "Supervisors", null, null));
        log.info("[Authority] {} on claim {} held for approval by {}: ${}", decision, claimId, a.assignedTo, amount);
        return a;
    }

    /** Who can act on this approval, or why not; null when this person may. */
    String refusal(ReserveApprovalEntity a, Actor actor) {
        if (actor == null) {
            return "An approval needs a signed-in person; a service credential cannot approve";
        }
        if (actor.id().equals(a.requestedBy)) {
            return "You cannot approve your own reserve";
        }
        if (!authority.allows(actor.role(), a.amount)) {
            return "This reserve of $" + a.amount + " is above your authority";
        }
        return null;
    }

    /** A decline: nothing is posted, and the claim goes back to the person who asked. */
    void decline(ReserveApprovalEntity a, Actor actor, String note) {
        Instant now = Instant.now();
        claims.moveStatus(a.claimId, Set.of(PENDING_APPROVAL), a.previousStatus, now);
        close(a, DECLINED, actor, note, now);
        audit.append(a.claimId, List.of(auditAgent.record(a.claimId, "ReserveApprovalDeclined",
                "approvalId=" + a.approvalId + ";amount=" + a.amount + ";noteSha256=" + AuditTrailAgent.sha256(Objects.toString(note, "")),
                "status=" + a.previousStatus + ";decidedBy=" + actor.id(), DECLINED)), actor.id());
        sync(a.claimId, actor.id(), "RESERVE_APPROVAL_DECLINED", "Reserve of $" + a.amount + " not approved", a.previousStatus,
                new CcActivity(null, "Reserve of $" + a.amount + " was not approved",
                        actor.id() + " did not approve it" + (note == null || note.isBlank() ? "." : ": " + note), "high",
                        LocalDate.now().plusDays(1), a.requestedBy, null, null));
    }

    /** Approved: audited here, and the caller then applies the held decision through the normal path. */
    void approve(ReserveApprovalEntity a, Actor actor, String note) {
        Instant now = Instant.now();
        claims.moveStatus(a.claimId, Set.of(PENDING_APPROVAL), a.previousStatus, now);
        close(a, APPROVED, actor, note, now);
        audit.append(a.claimId, List.of(auditAgent.record(a.claimId, "ReserveApprovalGranted",
                "approvalId=" + a.approvalId + ";requestedBy=" + a.requestedBy + ";amount=" + a.amount,
                "decidedBy=" + actor.id() + ";role=" + actor.role(), APPROVED)), actor.id());
    }

    private void close(ReserveApprovalEntity a, String status, Actor actor, String note, Instant now) {
        a.status = status;
        a.decidedBy = actor.id();
        a.decidedAt = now;
        a.decisionNote = note;
        approvals.save(a);
    }

    /** The eligible person with the fewest waiting approvals; lowest authority breaks the tie. */
    private String approverFor(BigDecimal amount, String except) {
        return authority.approversFor(amount, except).stream()
                .min(Comparator.comparingLong((AppUserEntity u) -> approvals.countByAssignedToAndStatus(u.username, PENDING)))
                .map(u -> u.username).orElse(null);
    }

    /** The claims system is told, best effort: a held decision posts nothing either way. */
    private void sync(String claimId, String actor, String action, String notes, String queue, CcActivity activity) {
        if (claimId.startsWith("REJ-")) return;
        try {
            claimCenter.updateClaim(claimId, new ClaimUpdateRequest("OPEN", notes, action, actor, null, queue));
            claimCenter.addActivity(claimId, activity);
        } catch (RuntimeException e) {
            log.warn("[Authority] Claims system not updated for {} ({}): {}", claimId, action, e.getMessage());
        }
    }
}
