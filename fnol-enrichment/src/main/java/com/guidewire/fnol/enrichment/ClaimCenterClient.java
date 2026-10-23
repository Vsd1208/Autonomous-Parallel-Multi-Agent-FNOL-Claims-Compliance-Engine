package com.guidewire.fnol.enrichment;

import com.guidewire.fnol.common.Models.*;

public interface ClaimCenterClient {

    ClaimCreateResponse createClaim(ClaimCreateRequest request);

    Claim getClaim(String claimId);

    /** Records a status change and notes, e.g. the adjuster decision. */
    void updateClaim(String claimId, ClaimUpdateRequest request);

    /** Attaches a JSON document such as the WHY-panel advisory or the evidence list. */
    void attachDocument(String claimId, ClaimAttachment attachment);
}
