package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The adjuster can view the evidence the AI assessed; it is stored safely and fingerprinted in the audit chain. */
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceDocumentsTest {

    private static final String TOKEN = "test-adjuster-token";
    private static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
    private static final AtomicInteger DAYS_AGO = new AtomicInteger(100);
    private static final AtomicInteger CLAIM_SEQ = new AtomicInteger(7000);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private PolicyCenterClient policyCenterClient;
    @MockBean private ClaimCenterClient claimCenterClient;

    @BeforeEach
    void stubGuidewire() {
        LocalDate start = LocalDate.now().minusMonths(6);
        when(policyCenterClient.getPolicy("POL-AUTO-112233")).thenReturn(new Policy(
                "POL-AUTO-112233", "AUTO", "ACTIVE", start, start.plusYears(1),
                "Avery Johnson", "CA", "2025 Camry", List.of()));
        when(policyCenterClient.getCoverages("POL-AUTO-112233")).thenReturn(List.of(
                new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))));
        doAnswer(inv -> new ClaimCreateResponse("CLM-2026-%06d".formatted(CLAIM_SEQ.getAndIncrement()), "DRAFT_PENDING_REVIEW"))
                .when(claimCenterClient).createClaim(any());
    }

    @Test
    @DisplayName("An uploaded photo is stored and served back byte-for-byte with safe headers; a mock:// URI is a reference")
    void uploadedPhotoIsViewable() throws Exception {
        String claimId = intake("Scraped a pole.", List.of("data:image/png;base64," + PNG_BASE64,
                "mock://photo/normal-front-bumper.jpg"), 200).path("claimId").asText();

        JsonNode docs = getJson("/api/v1/fnol/" + claimId + "/documents");
        assertThat(docs).hasSize(2);
        JsonNode photo = docs.get(0);
        assertThat(photo.path("name").asText()).isEqualTo("damage-photo-1.png");
        assertThat(photo.path("source").asText()).isEqualTo("UPLOAD");
        assertThat(photo.path("viewable").asBoolean()).isTrue();
        assertThat(photo.path("sha256").asText()).isEqualTo(EvidenceStore.sha256(Base64.getDecoder().decode(PNG_BASE64)));
        assertThat(photo.has("content")).as("image bytes never appear in JSON").isFalse();
        JsonNode reference = docs.get(1);
        assertThat(reference.path("source").asText()).isEqualTo("MOCK");
        assertThat(reference.path("viewable").asBoolean()).isFalse();
        assertThat(reference.path("sourceUri").asText()).isEqualTo("mock://photo/normal-front-bumper.jpg");

        MvcResult image = mockMvc.perform(get("/api/v1/fnol/" + claimId + "/documents/" + photo.path("documentId").asText() + "/content")
                        .header("X-Adjuster-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andReturn();
        assertThat(image.getResponse().getContentAsByteArray()).isEqualTo(Base64.getDecoder().decode(PNG_BASE64));

        // A reference has no bytes to serve.
        mockMvc.perform(get("/api/v1/fnol/" + claimId + "/documents/" + reference.path("documentId").asText() + "/content")
                        .header("X-Adjuster-Token", TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Each stored document is fingerprinted in the claim's audit chain, which stays valid")
    void evidenceIsAudited() throws Exception {
        String claimId = intake("Scraped a pole.", List.of("data:image/png;base64," + PNG_BASE64), 200).path("claimId").asText();

        JsonNode trail = getJson("/api/v1/fnol/" + claimId + "/audit");
        assertThat(trail.path("chainValid").asBoolean()).isTrue();
        JsonNode last = trail.path("records").get(trail.path("records").size() - 1);
        assertThat(last.path("agentName").asText()).isEqualTo("EvidenceStore");
        assertThat(last.path("status").asText()).isEqualTo("STORED");
    }

    @Test
    @DisplayName("Documents need the adjuster token, and a document cannot be read through another claim")
    void documentsAreProtected() throws Exception {
        String claimA = intake("Scraped a pole.", List.of("data:image/png;base64," + PNG_BASE64), 200).path("claimId").asText();
        String claimB = intake("Dent in the door.", List.of("mock://photo/normal.jpg"), 200).path("claimId").asText();
        String docA = getJson("/api/v1/fnol/" + claimA + "/documents").get(0).path("documentId").asText();

        mockMvc.perform(get("/api/v1/fnol/" + claimA + "/documents")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/fnol/" + claimA + "/documents/" + docA + "/content")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/fnol/" + claimB + "/documents/" + docA + "/content").header("X-Adjuster-Token", TOKEN))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/fnol/CLM-NOPE/documents").header("X-Adjuster-Token", TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("SVG, non-image and malformed data URIs are not stored; the intake still succeeds")
    void unsafeEvidenceIsNotStored() throws Exception {
        String svg = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString("<svg onload=alert(1)/>".getBytes());
        String html = "data:text/html;base64," + Base64.getEncoder().encodeToString("<script>x</script>".getBytes());
        String claimId = intake("Scraped a pole.", List.of(svg, html, "data:image/png;base64,@@@", "file:///etc/passwd"), 200)
                .path("claimId").asText();

        assertThat(getJson("/api/v1/fnol/" + claimId + "/documents")).isEmpty();
    }

    @Test
    @DisplayName("A failed intake (REJ-) keeps its evidence for the adjuster")
    void failedIntakeKeepsEvidence() throws Exception {
        JsonNode error = intake("Collision. Medical record MRN-123456 attached.",
                List.of("data:image/png;base64," + PNG_BASE64), 422);
        String rejId = error.path("claimId").asText();
        assertThat(rejId).startsWith("REJ-");

        JsonNode docs = getJson("/api/v1/fnol/" + rejId + "/documents");
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).path("viewable").asBoolean()).isTrue();
    }

    private JsonNode intake(String description, List<String> evidence, int expectedStatus) throws Exception {
        FNOLPayload payload = new FNOLPayload("POL-AUTO-112233", LocalDate.now().minusDays(DAYS_AGO.getAndIncrement()), "CA",
                description, "Avery Johnson", null, null, evidence, "WEB");
        String body = mockMvc.perform(post("/api/v1/fnol/intake").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode getJson(String url) throws Exception {
        String body = mockMvc.perform(get(url).header("X-Adjuster-Token", TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
