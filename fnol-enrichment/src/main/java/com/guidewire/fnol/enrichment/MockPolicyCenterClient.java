package com.guidewire.fnol.enrichment;

import com.guidewire.fnol.common.Models.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Objects;

@Component
@Profile("!guidewire")
public class MockPolicyCenterClient implements PolicyCenterClient {

    private final RestTemplate rest;
    private final String baseUrl;

    public MockPolicyCenterClient(RestTemplate rest,
                                  @Value("${mock-guidewire.base-url:http://localhost:9090}") String baseUrl) {
        this.rest = rest;
        this.baseUrl = baseUrl;
    }

    @Override
    public Policy getPolicy(String policyNumber) {
        return rest.getForObject(baseUrl + "/pc/policies/{policyNumber}", Policy.class, policyNumber);
    }

    @Override
    public List<Coverage> getCoverages(String policyNumber) {
        Coverage[] c = rest.getForObject(baseUrl + "/pc/policies/{policyNumber}/coverages", Coverage[].class, policyNumber);
        return List.of(Objects.requireNonNullElse(c, new Coverage[0]));
    }

    @Override
    public List<ClaimHistory> getPolicyClaimHistory(String policyNumber) {
        ClaimHistory[] h = rest.getForObject(baseUrl + "/pc/policies/{policyNumber}/history", ClaimHistory[].class, policyNumber);
        return List.of(Objects.requireNonNullElse(h, new ClaimHistory[0]));
    }
}
