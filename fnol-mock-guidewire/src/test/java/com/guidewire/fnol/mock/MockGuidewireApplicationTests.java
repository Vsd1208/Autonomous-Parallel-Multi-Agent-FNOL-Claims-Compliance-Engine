package com.guidewire.fnol.mock;

import com.guidewire.fnol.common.Models.Claim;
import com.guidewire.fnol.common.Models.ClaimAttachment;
import com.guidewire.fnol.common.Models.ClaimUpdateRequest;
import com.guidewire.fnol.common.Models.ClaimHistory;
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
    @DisplayName("POL-AUTO-445566 is an active CA auto policy with two claims in the last 150 days")
    void policyCenter_returnsRecentHistoryForHighFrequencyPolicy() {
        ResponseEntity<Policy> policyResp = rest.getForEntity("/pc/policies/POL-AUTO-445566", Policy.class);
        assertThat(policyResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(policyResp.getBody().status()).isEqualTo("ACTIVE");
        assertThat(policyResp.getBody().state()).isEqualTo("CA");
        assertThat(policyResp.getBody().coverages()).anyMatch(c -> c.type().equals("COLLISION"));

        ResponseEntity<ClaimHistory[]> historyResp =
                rest.getForEntity("/pc/policies/POL-AUTO-445566/history", ClaimHistory[].class);
        assertThat(historyResp.getBody()).hasSize(2);
        assertThat(historyResp.getBody())
                .allMatch(h -> h.lossDate().isAfter(LocalDate.now().minusDays(150)));
    }

    @Test
    @DisplayName("Seeded policy terms are relative to today, so they never expire")
    void policyCenter_policyTermCoversToday() {
        Policy policy = rest.getForObject("/pc/policies/POL-AUTO-112233", Policy.class);
        assertThat(policy.effectiveDate()).isBefore(LocalDate.now());
        assertThat(policy.expirationDate()).isAfter(LocalDate.now());
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
    @DisplayName("Mock ClaimCenter stores attachments and status/notes updates and returns them from GET")
    void claimCenter_storesAttachmentsAndUpdates() {
        var createRequest = Map.of(
                "policyNumber", "POL-AUTO-112233",
                "incidentDate", "2026-08-16",
                "state", "CA",
                "estimatedDamage", new BigDecimal("4200"),
                "reserve", new BigDecimal("4830"));
        String claimId = rest.postForObject("/cc/claims", createRequest, ClaimCreateResponse.class).claimId();

        ResponseEntity<String> attached = rest.postForEntity("/cc/claims/" + claimId + "/attachments",
                new ClaimAttachment("ai-advisory-why-panel.json", "application/json", Map.of("decision", "STRAIGHT_THROUGH")),
                String.class);
        assertThat(attached.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        rest.put("/cc/claims/" + claimId, new ClaimUpdateRequest("APPROVED_BY_ADJUSTER", "Photos match."));

        Claim claim = rest.getForObject("/cc/claims/" + claimId, Claim.class);
        assertThat(claim.status()).isEqualTo("APPROVED_BY_ADJUSTER");
        assertThat(claim.notes()).isEqualTo("Photos match.");
        assertThat(claim.attachments()).hasSize(1);
        assertThat(claim.attachments().get(0).name()).isEqualTo("ai-advisory-why-panel.json");
        assertThat(claim.attachments().get(0).body()).isEqualTo(Map.of("decision", "STRAIGHT_THROUGH"));
    }

    @Test
    @DisplayName("Mock ClaimCenter refuses attachments for an unknown claim")
    void claimCenter_attachmentForUnknownClaimIs404() {
        ResponseEntity<String> response = rest.postForEntity("/cc/claims/CLM-UNKNOWN-1/attachments",
                new ClaimAttachment("x.json", "application/json", Map.of()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Mock ClaimCenter returns 404 for unknown claim ID")
    void claimCenter_returns404ForUnknownClaim() {
        ResponseEntity<String> response = rest.getForEntity("/cc/claims/CLM-UNKNOWN-999", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
