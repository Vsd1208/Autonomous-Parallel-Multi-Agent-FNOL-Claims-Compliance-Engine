package com.guidewire.fnol.mock;

import com.guidewire.fnol.common.Models.Claim;
import com.guidewire.fnol.common.Models.ClaimCreateResponse;
import com.guidewire.fnol.common.Models.Policy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockGuidewireApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("Mock PolicyCenter returns seeded auto policy with coverages and history")
    void policyCenter_returnsAutoPolicyDetails() {
        ResponseEntity<Policy> policyResp = rest.getForEntity("/pc/policies/POL-AUTO-112233", Policy.class);
        assertThat(policyResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(policyResp.getBody()).isNotNull();
        assertThat(policyResp.getBody().policyNumber()).isEqualTo("POL-AUTO-112233");
        assertThat(policyResp.getBody().status()).isEqualTo("ACTIVE");
        assertThat(policyResp.getBody().state()).isEqualTo("CA");

        ResponseEntity<String> coveragesResp = rest.getForEntity("/pc/policies/POL-AUTO-112233/coverages", String.class);
        assertThat(coveragesResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(coveragesResp.getBody()).contains("COLLISION", "COMPREHENSIVE", "LIABILITY");

        ResponseEntity<String> historyResp = rest.getForEntity("/pc/policies/POL-AUTO-112233/history", String.class);
        assertThat(historyResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(historyResp.getBody()).contains("HIST-2233-01", "HIST-2233-02");
    }

    @Test
    @DisplayName("Mock PolicyCenter returns seeded home policy")
    void policyCenter_returnsHomePolicy() {
        ResponseEntity<Policy> policyResp = rest.getForEntity("/pc/policies/POL-HOME-334455", Policy.class);
        assertThat(policyResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(policyResp.getBody()).isNotNull();
        assertThat(policyResp.getBody().policyType()).isEqualTo("HOME");
        assertThat(policyResp.getBody().coverages()).anyMatch(c -> c.type().equals("DWELLING"));
    }

    @Test
    @DisplayName("Mock PolicyCenter returns 404 for non-existent policy")
    void policyCenter_returns404ForUnknownPolicy() {
        ResponseEntity<String> response = rest.getForEntity("/pc/policies/POL-UNKNOWN-999999", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Mock ClaimCenter creates claim and enables retrieval by ID")
    void claimCenter_createsAndRetrievesClaim() {
        var createRequest = Map.of(
                "policyNumber", "POL-AUTO-112233",
                "incidentDate", "2026-08-16",
                "state", "CA",
                "estimatedDamage", new BigDecimal("4200"),
                "reserve", new BigDecimal("4830")
        );

        ResponseEntity<ClaimCreateResponse> createResp = rest.postForEntity("/cc/claims", createRequest, ClaimCreateResponse.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(createResp.getBody()).isNotNull();
        assertThat(createResp.getBody().claimId()).startsWith("CLM-2026-");
        assertThat(createResp.getBody().status()).isEqualTo("OPEN");

        String claimId = createResp.getBody().claimId();

        ResponseEntity<Claim> getResp = rest.getForEntity("/cc/claims/" + claimId, Claim.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody()).isNotNull();
        assertThat(getResp.getBody().claimId()).isEqualTo(claimId);
        assertThat(getResp.getBody().policyNumber()).isEqualTo("POL-AUTO-112233");
        assertThat(getResp.getBody().estimatedDamage()).isEqualByComparingTo("4200");
        assertThat(getResp.getBody().reserve()).isEqualByComparingTo("4830");
    }

    @Test
    @DisplayName("Mock ClaimCenter returns 404 for unknown claim ID")
    void claimCenter_returns404ForUnknownClaim() {
        ResponseEntity<String> response = rest.getForEntity("/cc/claims/CLM-UNKNOWN-999", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
