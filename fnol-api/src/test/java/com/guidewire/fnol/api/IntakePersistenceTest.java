package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P2: claim IDs are never overwritten, and a failed save after ClaimCenter filed the claim keeps the key held. */
@SpringBootTest
@AutoConfigureMockMvc
class IntakePersistenceTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @SpyBean private ClaimRepository claims;
    @MockBean private PolicyCenterClient policyCenterClient;
    @MockBean private ClaimCenterClient claimCenterClient;

    @BeforeEach
    void stubPolicyCenter() {
        LocalDate start = LocalDate.now().minusMonths(6);
        when(policyCenterClient.getPolicy("POL-AUTO-112233")).thenReturn(new Policy(
                "POL-AUTO-112233", "AUTO", "ACTIVE", start, start.plusYears(1),
                "Avery Johnson", "CA", "2025 Camry", List.of()));
        when(policyCenterClient.getCoverages("POL-AUTO-112233")).thenReturn(List.of(
                new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))));
    }

    @Test
    @DisplayName("D2: a ClaimCenter ID that already exists returns 409 CLAIM_ID_CONFLICT and the old claim is untouched")
    void existingClaimIdIsNotOverwritten() throws Exception {
        ClaimEntity existing = new ClaimEntity();
        existing.claimId = "CLM-2026-000777";
        existing.policyNumber = "POL-AUTO-223344";
        existing.status = "HUMAN_REVIEW";
        existing.incidentDate = LocalDate.now().minusDays(100);
        existing.state = "NY";
        existing.estimatedDamage = new BigDecimal("1000");
        existing.reserve = new BigDecimal("1150");
        existing.createdAt = Instant.now();
        existing.updatedAt = existing.createdAt;
        claims.save(existing);

        when(claimCenterClient.createClaim(any())).thenReturn(new ClaimCreateResponse("CLM-2026-000777", "OPEN"));

        mockMvc.perform(post("/api/v1/fnol/intake").header("X-Adjuster-Token", "test-adjuster-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload(LocalDate.now().minusDays(21)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CLAIM_ID_CONFLICT"));

        ClaimEntity after = claims.findById("CLM-2026-000777").orElseThrow();
        assertThat(after.policyNumber).isEqualTo("POL-AUTO-223344");
        assertThat(after.state).isEqualTo("NY");
    }

    @Test
    @DisplayName("D14: when the save fails after ClaimCenter created the claim, the idempotency key stays held")
    void saveFailureKeepsIdempotencyKeyHeld() throws Exception {
        when(claimCenterClient.createClaim(any())).thenReturn(new ClaimCreateResponse("CLM-2026-000888", "OPEN"));
        doThrow(new DataAccessResourceFailureException("database down"))
                .when(claims).save(argThat(e -> e != null && "CLM-2026-000888".equals(e.claimId)));

        String body = objectMapper.writeValueAsString(payload(LocalDate.now().minusDays(22)));

        mockMvc.perform(post("/api/v1/fnol/intake").header("X-Adjuster-Token", "test-adjuster-token").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is5xxServerError());

        // A retry must not reach ClaimCenter again and file a second claim.
        mockMvc.perform(post("/api/v1/fnol/intake").header("X-Adjuster-Token", "test-adjuster-token").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_FNOL"));
    }

    private static FNOLPayload payload(LocalDate incidentDate) {
        return new FNOLPayload("POL-AUTO-112233", incidentDate, "CA",
                "Minor scrape in a parking lot.", "Avery Johnson", "01/02/1980", "123-45-6789",
                List.of("mock://photo/normal.jpg"), "WEB");
    }
}
