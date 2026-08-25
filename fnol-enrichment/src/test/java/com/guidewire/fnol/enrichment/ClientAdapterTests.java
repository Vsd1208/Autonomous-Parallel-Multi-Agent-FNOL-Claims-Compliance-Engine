package com.guidewire.fnol.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ClientAdapterTests {

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private ObjectMapper objectMapper;
    private MockPolicyCenterClient policyClient;
    private MockClaimCenterClient claimClient;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        objectMapper = new ObjectMapper().findAndRegisterModules();

        String baseUrl = "http://localhost:9090";
        policyClient = new MockPolicyCenterClient(restTemplate, baseUrl);
        claimClient = new MockClaimCenterClient(restTemplate, baseUrl);
    }

    @Test
    @DisplayName("MockPolicyCenterClient fetches policy details over HTTP")
    void policyClient_fetchesPolicyOverHttp() throws Exception {
        Policy expected = new Policy(
                "POL-AUTO-112233", "AUTO", "ACTIVE",
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                "Avery Johnson", "CA", "2025 Camry", List.of()
        );

        mockServer.expect(requestTo("http://localhost:9090/pc/policies/POL-AUTO-112233"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(expected), MediaType.APPLICATION_JSON));

        Policy actual = policyClient.getPolicy("POL-AUTO-112233");
        assertThat(actual.policyNumber()).isEqualTo("POL-AUTO-112233");
        assertThat(actual.status()).isEqualTo("ACTIVE");
        mockServer.verify();
    }

    @Test
    @DisplayName("MockPolicyCenterClient fetches policy coverages over HTTP")
    void policyClient_fetchesCoveragesOverHttp() throws Exception {
        List<Coverage> expected = List.of(
                new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))
        );

        mockServer.expect(requestTo("http://localhost:9090/pc/policies/POL-AUTO-112233/coverages"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(expected), MediaType.APPLICATION_JSON));

        List<Coverage> actual = policyClient.getCoverages("POL-AUTO-112233");
        assertThat(actual).hasSize(1);
        assertThat(actual.get(0).type()).isEqualTo("COLLISION");
        mockServer.verify();
    }

    @Test
    @DisplayName("MockClaimCenterClient creates claim over HTTP")
    void claimClient_createsClaimOverHttp() throws Exception {
        ClaimCreateResponse expected = new ClaimCreateResponse("CLM-2026-000001", "OPEN");

        mockServer.expect(requestTo("http://localhost:9090/cc/claims"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(objectMapper.writeValueAsString(expected), MediaType.APPLICATION_JSON));

        ClaimCreateRequest request = new ClaimCreateRequest(
                "POL-AUTO-112233", LocalDate.now(), "CA",
                new BigDecimal("4200"), new BigDecimal("4830")
        );

        ClaimCreateResponse actual = claimClient.createClaim(request);
        assertThat(actual.claimId()).isEqualTo("CLM-2026-000001");
        assertThat(actual.status()).isEqualTo("OPEN");
        mockServer.verify();
    }
}
