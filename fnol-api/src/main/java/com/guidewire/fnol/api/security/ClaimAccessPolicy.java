package com.guidewire.fnol.api.security;

import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.api.persistence.InspectionRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Who may do what on which claim, in one place. The caller comes from the authenticated session, never from a request field. A claim
 * the caller may not see answers 404, the same as one that does not exist, so nothing reveals what exists.
 */
@Component
public class ClaimAccessPolicy {

    public enum Permission {
        /** The photos and basic facts an inspector needs. */
        CLAIM_BASIC,
        /** The AI analysis, reserve, fraud signal, audit trail and the rest of the claim file. */
        CLAIM_INTERNAL,
        /** Workflow changes other than a decision, such as requesting photos. */
        CLAIM_WORK,
        /** Recording a decision: a signed-in person only. */
        CLAIM_DECIDE,
        /** The governance page. */
        GOVERNANCE,
        /** Operating the system, such as replaying failed events: a signed-in supervisor only. */
        ADMINISTER
    }

    /** The caller may not see this claim. Answered as 404. */
    public static class ClaimNotVisibleException extends RuntimeException {
        public ClaimNotVisibleException() {
            super("Claim not found");
        }
    }

    /** The caller's role may not do this at all. Answered as 403. */
    public static class PermissionDeniedException extends RuntimeException {
        public PermissionDeniedException(String message) {
            super(message);
        }
    }

    /** Claims in this segment are for the assignee and for those who supervise that work. */
    public static final String RESTRICTED_SEGMENT = "SIU_REVIEW";

    private final ClaimRepository claims;
    private final InspectionRepository inspections;

    public ClaimAccessPolicy(ClaimRepository claims, InspectionRepository inspections) {
        this.claims = claims;
        this.inspections = inspections;
    }

    /** The authenticated caller of this request: a person, or the service credential. */
    public static Actor caller() {
        return Actor.caller();
    }

    public void requirePermission(Actor actor, Permission permission) {
        if (actor == null) {
            throw new PermissionDeniedException("Authentication is required");
        }
        if (permission == Permission.ADMINISTER && !ReserveAuthority.SUPERVISOR.equals(actor.role())) {
            throw new PermissionDeniedException("This needs a signed-in supervisor");
        }
        if (permission == Permission.CLAIM_DECIDE && actor.type() != ActorType.HUMAN_ACTOR) {
            throw new PermissionDeniedException("A decision needs a signed-in person; a service credential cannot make one");
        }
        if (AuthService.INSPECTOR.equals(actor.role()) && (permission == Permission.GOVERNANCE || permission == Permission.CLAIM_DECIDE
                || permission == Permission.CLAIM_WORK)) {
            throw new PermissionDeniedException("Inspectors report on inspections; they do not decide claims or see governance figures");
        }
    }

    /** @throws ClaimNotVisibleException if this caller may not see this claim */
    public void requireClaimAccess(Actor actor, String claimId, Permission permission) {
        requirePermission(actor, permission);
        if (AuthService.INSPECTOR.equals(actor.role())) {
            if (permission != Permission.CLAIM_BASIC) {
                throw new ClaimNotVisibleException();
            }
            requireInspectionAssignment(actor, claimId);
            return;
        }
        if (AuthService.ADJUSTER.equals(actor.role()) && restrictedFromAdjuster(actor, claimId)) {
            throw new ClaimNotVisibleException();
        }
    }

    public void requireInspectionAssignment(Actor actor, String claimId) {
        if (actor == null || !inspections.existsByClaimIdAndInspectorId(claimId, actor.id())) {
            throw new ClaimNotVisibleException();
        }
    }

    /** For filtering a list. */
    public boolean mayListClaim(Actor actor, String segment, String assignedTo) {
        return !(AuthService.ADJUSTER.equals(actor.role()) && RESTRICTED_SEGMENT.equals(segment) && !actor.id().equals(assignedTo));
    }

    private boolean restrictedFromAdjuster(Actor actor, String claimId) {
        return Optional.ofNullable(claims.findById(claimId).orElse(null))
                .map(c -> !mayListClaim(actor, c.segment, c.assignedTo)).orElse(false);
    }
}
