package com.guidewire.fnol.enrichment;

import com.guidewire.fnol.common.Models.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@Profile("!guidewire")
public class MockClaimCenterClient implements ClaimCenterClient {

    private final RestTemplate rest;
    private final String baseUrl;

    public MockClaimCenterClient(RestTemplate rest,
                                 @Value("${mock-guidewire.base-url:http://localhost:9090}") String baseUrl) {
        this.rest = rest;
        this.baseUrl = baseUrl;
    }

    @Override
    public ClaimCreateResponse createClaim(ClaimCreateRequest request) {
        return rest.postForObject(baseUrl + "/cc/claims", request, ClaimCreateResponse.class);
    }

    @Override
    public Claim getClaim(String claimId) {
        return rest.getForObject(baseUrl + "/cc/claims/{claimId}", Claim.class, claimId);
    }

    @Override
    public void updateClaim(String claimId, ClaimUpdateRequest request) {
        rest.put(baseUrl + "/cc/claims/{claimId}", request, claimId);
    }

    @Override
    public void attachDocument(String claimId, ClaimAttachment attachment) {
        rest.postForLocation(baseUrl + "/cc/claims/{claimId}/attachments", attachment, claimId);
    }

    // Claim child objects, on paths modelled on (not verified against) the ClaimCenter Cloud API claim resources.
    private static final String CLAIM_API = "/cc/rest/claim/v1/claims/{claimId}";

    @Override
    public CcExposure addExposure(String claimId, CcExposure exposure) {
        return rest.postForObject(baseUrl + CLAIM_API + "/exposures", exposure, CcExposure.class, claimId);
    }

    @Override
    public CcActivity addActivity(String claimId, CcActivity activity) {
        return rest.postForObject(baseUrl + CLAIM_API + "/activities", activity, CcActivity.class, claimId);
    }

    @Override
    public CcNote addNote(String claimId, CcNote note) {
        return rest.postForObject(baseUrl + CLAIM_API + "/notes", note, CcNote.class, claimId);
    }
}
