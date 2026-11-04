package com.guidewire.fnol.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.guidewire.fnol.common.Models.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * STARTING POINT, NEVER RUN AGAINST A REAL TENANT. A ClaimCenter Cloud API client for the first sandbox session (profile
 * {@code guidewire-cloud-claims}; policy data stays on the mock). Tested only against a stub of what is written here.
 *
 * <p>From Guidewire's public documentation: draft with {@code POST /claim/v1/claims}, then
 * {@code POST /claim/v1/claims/{id}/submit}; activities and exposures on the claim; a claim's id and number being
 * different things. <b>Assumed, to be checked in the sandbox:</b> the {@code data.attributes} envelope and every field
 * name, the OAuth2 token request, and the notes path. A body the tenant rejects shows up as an HTTP error, not silently.
 * Anything without a known mapping (routing, decisions, documents) refuses, rather than guessing:
 * {@code tools/guidewire-check} and docs/guidewire-integration.md list what is open.
 */
@Component
@Profile("guidewire-cloud-claims")
public class GuidewireCloudClaimCenterClient implements ClaimCenterClient {

    private static final String CLAIMS = "/claim/v1/claims";

    private final GuidewireCloudHttp http;

    public GuidewireCloudClaimCenterClient(RestTemplate rest,
                                           @Value("${guidewire.cloud.base-url:}") String baseUrl,
                                           @Value("${guidewire.cloud.token-url:}") String tokenUrl,
                                           @Value("${guidewire.cloud.client-id:}") String clientId,
                                           @Value("${guidewire.cloud.client-secret:}") String clientSecret) {
        GuidewireTokenProvider.requireConfigured("The Guidewire Cloud claims client", Map.of("guidewire.cloud.base-url", baseUrl,
                "guidewire.cloud.token-url", tokenUrl, "guidewire.cloud.client-id", clientId, "guidewire.cloud.client-secret", clientSecret));
        this.http = new GuidewireCloudHttp(rest, baseUrl, new GuidewireTokenProvider(rest, tokenUrl, clientId, clientSecret, Clock.systemUTC()), Thread::sleep);
    }

    private JsonNode call(HttpMethod method, String path, Map<String, Object> attributes, Object... uriVariables) {
        return http.call(method, path, attributes, uriVariables);
    }

    private static JsonNode attributes(JsonNode answer) {
        return answer == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance()
                : answer.path("data").path("attributes");
    }

    /** Draft, then submit; the claim opens with its open number. The id is what later calls use. */
    @Override
    public ClaimCreateResponse submitFnol(FNOLPayload fnol) {
        Map<String, Object> draft = new HashMap<>();
        draft.put("policyNumber", fnol.policyNumber());
        draft.put("lossDate", fnol.incidentDate() + "T00:00:00.000Z");
        draft.put("description", fnol.description());
        String id;
        try {
            id = attributes(call(HttpMethod.POST, CLAIMS, draft)).path("id").asText(null);
        } catch (HttpClientErrorException.Conflict e) {
            throw new DuplicateLossException(null);
        }
        if (id == null) {
            throw new IllegalStateException("The draft claim's answer carried no id");
        }
        JsonNode open = attributes(call(HttpMethod.POST, CLAIMS + "/{id}/submit", Map.of(), id));
        return new ClaimCreateResponse(id, "OPEN", open.path("claimNumber").asText(null));
    }

    @Override
    public Claim getClaim(String claimId) {
        JsonNode a = attributes(call(HttpMethod.GET, CLAIMS + "/{id}", null, claimId));
        return new Claim(claimId, a.path("state").path("code").asText(a.path("state").asText(null)),
                a.path("policyNumber").asText(null), null, null, null, null, null, java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), null, a.path("claimNumber").asText(null));
    }

    @Override
    public CcActivity addActivity(String claimId, CcActivity activity) {
        Map<String, Object> a = new HashMap<>();
        a.put("subject", activity.subject());
        a.put("description", activity.description());
        call(HttpMethod.POST, CLAIMS + "/{id}/activities", a, claimId);
        return activity;
    }

    @Override
    public CcNote addNote(String claimId, CcNote note) {
        Map<String, Object> a = new HashMap<>();
        a.put("topic", note.topic());
        a.put("subject", note.subject());
        a.put("body", note.body());
        call(HttpMethod.POST, CLAIMS + "/{id}/notes", a, claimId);
        return note;
    }

    private static UnsupportedOperationException noMapping(String what) {
        return new UnsupportedOperationException(what + " has no verified Cloud API mapping yet; see docs/guidewire-integration.md");
    }

    @Override
    public ClaimCreateResponse createClaim(ClaimCreateRequest request) {
        throw noMapping("Direct claim creation");
    }

    @Override
    public void updateClaim(String claimId, ClaimUpdateRequest request) {
        throw noMapping("A status or review step");
    }

    @Override
    public void attachDocument(String claimId, ClaimAttachment attachment) {
        throw noMapping("Attaching a document");
    }

    /** A real exposure links an incident and claimant records this adapter does not create yet. */
    @Override
    public CcExposure addExposure(String claimId, CcExposure exposure) {
        throw noMapping("Creating an exposure with its incident and claimant");
    }
}
