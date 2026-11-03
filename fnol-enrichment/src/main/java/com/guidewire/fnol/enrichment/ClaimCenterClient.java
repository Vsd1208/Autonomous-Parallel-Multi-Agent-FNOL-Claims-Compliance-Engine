package com.guidewire.fnol.enrichment;

import com.guidewire.fnol.common.Models.*;

import java.util.Collection;

public interface ClaimCenterClient {

    ClaimCreateResponse createClaim(ClaimCreateRequest request);

    Claim getClaim(String claimId);

    /** Records a status change and notes, e.g. the adjuster decision. */
    void updateClaim(String claimId, ClaimUpdateRequest request);

    /** Attaches a JSON document such as the WHY-panel advisory or the evidence list. */
    void attachDocument(String claimId, ClaimAttachment attachment);

    /** Adds an exposure (coverage + reserve line) to the claim; returns it with its ClaimCenter id. */
    CcExposure addExposure(String claimId, CcExposure exposure);

    /** Adds an activity (a task for the adjuster) to the claim. */
    CcActivity addActivity(String claimId, CcActivity activity);

    /** Adds a note to the claim. */
    CcNote addNote(String claimId, CcNote note);

    // ── Extension mode: the claims system owns the claim and the FNOL ─────────────────────────────

    /** The FNOL as it was reported to the claims system (loss details, reporter, photos). */
    default FNOLPayload getFnol(String claimId) {
        throw new UnsupportedOperationException("getFnol is not supported by this ClaimCenter client");
    }

    /** Records the AI's routing, estimate and recommended reserve on a claim the claims system already holds. */
    default void patchClaim(String claimId, ClaimPatch patch) {
        throw new UnsupportedOperationException("patchClaim is not supported by this ClaimCenter client");
    }

    /** Records an adjuster decision on the claim and returns the claim, with the decision's time on its review actions. */
    default Claim recordDecision(String claimId, ClaimUpdateRequest decision) {
        throw new UnsupportedOperationException("recordDecision is not supported by this ClaimCenter client");
    }

    /** A page of the claims in these adjuster work queues, oldest first. */
    default ClaimQueuePage listWorkQueues(Collection<String> workQueues, int page, int size) {
        throw new UnsupportedOperationException("listWorkQueues is not supported by this ClaimCenter client");
    }

    /** As {@link #listWorkQueues(Collection, int, int)}, within one segment. */
    default ClaimQueuePage listWorkQueues(Collection<String> workQueues, String segment, int page, int size) {
        throw new UnsupportedOperationException("listWorkQueues by segment is not supported by this ClaimCenter client");
    }

    /**
     * Files an FNOL in the claims system: creates the draft claim and submits it (which emits claim.submitted).
     *
     * @throws DuplicateLossException when the claims system already holds a claim for the same loss
     */
    default ClaimCreateResponse submitFnol(FNOLPayload fnol) {
        throw new UnsupportedOperationException("submitFnol is not supported by this ClaimCenter client");
    }
}
