package com.guidewire.fnol.enrichment;

import com.guidewire.fnol.common.Models.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Collection;
import java.util.Map;

@Component
@Profile("!guidewire & !guidewire-cloud-claims")
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

    @Override
    public FNOLPayload getFnol(String claimId) {
        return rest.getForObject(baseUrl + CLAIM_API + "/fnol", FNOLPayload.class, claimId);
    }

    @Override
    public void patchClaim(String claimId, ClaimPatch patch) {
        rest.patchForObject(baseUrl + CLAIM_API, patch, Claim.class, claimId);
    }

    @Override
    public Claim recordDecision(String claimId, ClaimUpdateRequest decision) {
        return rest.exchange(baseUrl + "/cc/claims/{claimId}", HttpMethod.PUT, new HttpEntity<>(decision), Claim.class,
                claimId).getBody();
    }

    @Override
    public ClaimQueuePage listWorkQueues(Collection<String> workQueues, int page, int size) {
        return rest.getForObject(baseUrl + "/cc/rest/claim/v1/claims?workQueue={q}&page={p}&size={s}",
                ClaimQueuePage.class, String.join(",", workQueues), page, size);
    }

    @Override
    public ClaimCreateResponse submitFnol(FNOLPayload fnol) {
        ClaimCreateResponse draft;
        try {
            draft = rest.postForObject(baseUrl + "/cc/rest/claim/v1/claims", fnol, ClaimCreateResponse.class);
        } catch (HttpClientErrorException.Conflict e) {
            Map<?, ?> body = e.getResponseBodyAs(Map.class);
            throw new DuplicateLossException(body == null ? null : (String) body.get("claimId"));
        }
        return rest.postForObject(baseUrl + CLAIM_API + "/submit", null, ClaimCreateResponse.class, draft.claimId());
    }
}
