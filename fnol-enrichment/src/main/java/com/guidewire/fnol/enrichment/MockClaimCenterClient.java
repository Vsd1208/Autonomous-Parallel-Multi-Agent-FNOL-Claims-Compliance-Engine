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
}
