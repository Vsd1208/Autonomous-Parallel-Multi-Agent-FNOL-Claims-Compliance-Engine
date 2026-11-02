package com.guidewire.fnol.api;

import com.guidewire.fnol.common.Models.Coverage;
import com.guidewire.fnol.common.Models.ClaimCreateResponse;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.Policy;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lite mode: no vision service, mock vision, and a claim handled safely without the second opinion. */
@SpringBootTest(properties = {"fnol.lite-mode=true", "fnol.second-opinion.url=", "fnol.redaction.url="})
@AutoConfigureMockMvc
class LiteModeTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @MockBean private PolicyCenterClient policyCenter;
    @MockBean private ClaimCenterClient claimCenter;

    @Test
    @DisplayName("Lite mode refuses to start with Gemini on: no photo can reach an external model unblurred")
    void refusesGemini() {
        assertThatThrownBy(() -> new LiteMode(true, true)).isInstanceOf(IllegalStateException.class).hasMessageContaining("blurring");
        assertThat(new LiteMode(true, false).enabled()).isTrue();
        assertThat(new LiteMode(false, true).enabled()).isFalse();
    }

    @Test
    @DisplayName("Health reports the second opinion as not available, and a claim says so while still being handled")
    void claimWithoutTheSecondOpinion() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(jsonPath("$.secondOpinion").value("NOT_AVAILABLE_IN_LITE_MODE"));

        LocalDate start = LocalDate.now().minusMonths(6);
        when(policyCenter.getPolicy("POL-AUTO-112233")).thenReturn(new Policy("POL-AUTO-112233", "AUTO", "ACTIVE", start, start.plusYears(1),
                "Avery Johnson", "CA", "2025 Camry", List.of()));
        when(policyCenter.getCoverages("POL-AUTO-112233")).thenReturn(List.of(new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))));
        when(claimCenter.createClaim(any())).thenReturn(new ClaimCreateResponse("CLM-2026-0L0001", "OPEN"));
        FNOLPayload payload = new FNOLPayload("POL-AUTO-112233", LocalDate.now().minusDays(4), "CA", "Scraped a pole in a parking garage.",
                "Avery Johnson", "01/02/1980", null, List.of("mock://photo/normal.jpg"), "WEB", null, null, new BigDecimal("2000"));
        mockMvc.perform(post("/api/v1/fnol/intake").header("X-Adjuster-Token", "test-adjuster-token").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchA.secondOpinion").doesNotExist())
                .andExpect(jsonPath("$.notes[?(@ =~ /.*not available in lite mode.*/)]").isNotEmpty());
    }
}
