package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.common.Models.AdjusterReviewRequest;
import com.guidewire.fnol.common.Models.ClaimUpdateRequest;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P11: the adjuster decision reaches ClaimCenter without making it a point of failure. P12: adjuster queue. */
@SpringBootTest
@AutoConfigureMockMvc
class ClaimCenterSyncAndQueueTest {

    private static final String TOKEN = "test-adjuster-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ClaimRepository claims;
    @MockBean private PolicyCenterClient policyCenterClient;
    @MockBean private ClaimCenterClient claimCenterClient;

    @BeforeEach
    void clean() {
        claims.deleteAll();
        reset(claimCenterClient);
    }

    @Test
    @DisplayName("A review decision is pushed to ClaimCenter with the notes, and the claim is SYNCED")
    void reviewCallsUpdateClaim() throws Exception {
        String claimId = saveClaim("CLM-Q-", "HUMAN_REVIEW", Instant.now());

        review(claimId).andExpect(status().isOk());

        verify(claimCenterClient).updateClaim(eq(claimId), eq(new ClaimUpdateRequest("APPROVED_BY_ADJUSTER", "Photos match.")));
        assertThat(claims.findById(claimId).orElseThrow().ccSyncStatus).isEqualTo("SYNCED");
    }

    @Test
    @DisplayName("A ClaimCenter outage does not break /review; the claim is marked FAILED for retry")
    void claimCenterOutageDoesNotBreakReview() throws Exception {
        String claimId = saveClaim("CLM-Q-", "HUMAN_REVIEW", Instant.now());
        doThrow(new ResourceAccessException("ClaimCenter down")).when(claimCenterClient).updateClaim(anyString(), any());

        review(claimId).andExpect(status().isOk());

        ClaimEntity saved = claims.findById(claimId).orElseThrow();
        assertThat(saved.status).isEqualTo("APPROVED_BY_ADJUSTER");
        assertThat(saved.ccSyncStatus).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Reviewing a failed intake (REJ-) does not call ClaimCenter")
    void failedIntakeIsNotSynced() throws Exception {
        String claimId = saveClaim("REJ-", "HUMAN_REVIEW", Instant.now());

        review(claimId).andExpect(status().isOk());

        verify(claimCenterClient, never()).updateClaim(anyString(), any());
        assertThat(claims.findById(claimId).orElseThrow().ccSyncStatus).isEqualTo("NOT_FILED");
    }

    @Test
    @DisplayName("GET /queue lists HUMAN_REVIEW claims oldest first, paginated, and needs the token")
    void queueListsOldestFirst() throws Exception {
        Instant now = Instant.now();
        String newest = saveClaim("CLM-Q-", "HUMAN_REVIEW", now);
        String oldest = saveClaim("CLM-Q-", "HUMAN_REVIEW", now.minusSeconds(300));
        String middle = saveClaim("REJ-", "HUMAN_REVIEW", now.minusSeconds(100));
        saveClaim("CLM-Q-", "PENDING_ADVISORY_REVIEW", now.minusSeconds(500));
        saveClaim("CLM-Q-", "APPROVED_BY_ADJUSTER", now.minusSeconds(600));

        mockMvc.perform(get("/api/v1/fnol/queue")).andExpect(status().isUnauthorized());

        JsonNode page = getJson("/api/v1/fnol/queue?status=HUMAN_REVIEW&page=0&size=2");
        assertThat(page.path("totalElements").asLong()).isEqualTo(3);
        assertThat(page.path("totalPages").asInt()).isEqualTo(2);
        assertThat(page.path("items")).extracting(n -> n.path("claimId").asText()).containsExactly(oldest, middle);
        JsonNode first = page.path("items").get(0);
        assertThat(first.path("severityGate").asText()).isEqualTo("LOW");
        assertThat(first.path("reserve").decimalValue()).isEqualByComparingTo("4830");
        assertThat(first.has("createdAt")).isTrue();

        JsonNode second = getJson("/api/v1/fnol/queue?status=HUMAN_REVIEW&page=1&size=2");
        assertThat(second.path("items")).extracting(n -> n.path("claimId").asText()).containsExactly(newest);

        JsonNode both = getJson("/api/v1/fnol/queue?status=HUMAN_REVIEW,PENDING_ADVISORY_REVIEW");
        assertThat(both.path("totalElements").asLong()).isEqualTo(4);
    }

    @Test
    @DisplayName("GET /queue rejects an oversized page")
    void queueRejectsBadPaging() throws Exception {
        mockMvc.perform(get("/api/v1/fnol/queue?size=500").header("X-Adjuster-Token", TOKEN))
                .andExpect(status().isBadRequest());
    }

    private JsonNode getJson(String url) throws Exception {
        String body = mockMvc.perform(get(url).header("X-Adjuster-Token", TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private org.springframework.test.web.servlet.ResultActions review(String claimId) throws Exception {
        return mockMvc.perform(post("/api/v1/fnol/" + claimId + "/review")
                .header("X-Adjuster-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new AdjusterReviewRequest("APPROVED_BY_ADJUSTER", "ADJ-001", "Photos match."))));
    }

    private String saveClaim(String prefix, String status, Instant createdAt) {
        ClaimEntity e = new ClaimEntity();
        e.claimId = prefix + UUID.randomUUID().toString().substring(0, 8);
        e.policyNumber = "POL-AUTO-112233";
        e.status = status;
        e.incidentDate = LocalDate.now().minusDays(3);
        e.state = "CA";
        e.estimatedDamage = new BigDecimal("4200");
        e.reserve = new BigDecimal("4830");
        e.severityGate = "LOW";
        e.createdAt = createdAt;
        e.updatedAt = createdAt;
        claims.save(e);
        return e.claimId;
    }
}
