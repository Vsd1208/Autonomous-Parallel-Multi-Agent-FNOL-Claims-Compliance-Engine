package com.guidewire.fnol.enrichment;
import com.guidewire.fnol.common.Models.*;
public interface ClaimCenterClient { ClaimCreateResponse createClaim(ClaimCreateRequest request); Claim getClaim(String claimId); }
