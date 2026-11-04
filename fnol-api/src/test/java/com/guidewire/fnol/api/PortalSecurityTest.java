package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The public routes: brute force, enumeration, tokens, uploads. */
@SpringBootTest(properties = {"fnol.portal.max-failures=3", "fnol.portal.window-seconds=600"})
@AutoConfigureMockMvc
class PortalSecurityTest {

    private static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ClaimRepository claims;
    @Autowired private PortalProtection protection;
    @MockBean private ClaimCenterClient claimCenter;

    private String claim(String status) {
        ClaimEntity c = new ClaimEntity();
        c.claimId = "CLM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        c.policyNumber = "POL-AUTO-112233";
        c.status = status;
        c.incidentDate = LocalDate.now().minusDays(3);
        c.state = "CA";
        c.estimatedDamage = new BigDecimal("4200");
        c.reserve = new BigDecimal("3200");
        c.ccSyncStatus = "SYNCED";
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        claims.save(c);
        return c.claimId;
    }

    private MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request, String address) {
        return request.with(r -> {
            r.setRemoteAddr(address);
            return r;
        });
    }

    @Test
    @DisplayName("A wrong policy number and a claim that does not exist answer exactly the same")
    void noEnumeration() throws Exception {
        String id = claim("HUMAN_REVIEW");
        String wrongPolicy = mockMvc.perform(from(get("/api/v1/portal/claims/" + id).param("policyNumber", "POL-AUTO-000000"), "10.0.0.1"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String noClaim = mockMvc.perform(from(get("/api/v1/portal/claims/CLM-NOSUCH").param("policyNumber", "POL-AUTO-112233"), "10.0.0.1"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(wrongPolicy).isEqualTo(noClaim);
    }

    @Test
    @DisplayName("Brute force: after the threshold an address is refused, even with the right details; another address still works")
    void bruteForce() throws Exception {
        String id = claim("HUMAN_REVIEW");
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(from(get("/api/v1/portal/claims/CLM-GUESS" + i).param("policyNumber", "POL-AUTO-0" + i), "10.0.0.2")).andExpect(status().isNotFound());
        }
        mockMvc.perform(from(get("/api/v1/portal/claims/" + id).param("policyNumber", "POL-AUTO-112233"), "10.0.0.2")).andExpect(status().isTooManyRequests());
        mockMvc.perform(from(get("/api/v1/portal/claims/" + id).param("policyNumber", "POL-AUTO-112233"), "10.0.0.3")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Guessing one claim's policy number from many addresses is limited too")
    void distributedGuessing() throws Exception {
        String id = claim("HUMAN_REVIEW");
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(from(get("/api/v1/portal/claims/" + id).param("policyNumber", "POL-AUTO-99999" + i), "10.1.0." + i)).andExpect(status().isNotFound());
        }
        mockMvc.perform(from(get("/api/v1/portal/claims/" + id).param("policyNumber", "POL-AUTO-112233"), "10.1.0.50")).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("An access token opens its own claim only, holds no claim data, and stops working when revoked")
    void tokens() throws Exception {
        String id = claim("HUMAN_REVIEW");
        String other = claim("HUMAN_REVIEW");
        JsonNode issued = mapper.readTree(mockMvc.perform(from(post("/api/v1/portal/claims/" + id + "/access").contentType(MediaType.APPLICATION_JSON)
                .content("{\"policyNumber\":\"POL-AUTO-112233\"}"), "10.0.0.4")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String token = issued.get("accessToken").asText();
        for (String part : token.split("\\.")) {
            String decoded = new String(Base64.getUrlDecoder().decode(part.length() % 4 == 1 ? "AAAA" : part));
            assertThat(decoded).doesNotContain(id).doesNotContain("POL-AUTO");
        }
        mockMvc.perform(from(get("/api/v1/portal/claims/" + id).header("X-Portal-Token", token), "10.0.0.4")).andExpect(status().isOk());
        mockMvc.perform(from(get("/api/v1/portal/claims/" + other).header("X-Portal-Token", token), "10.0.0.4")).andExpect(status().isNotFound());
        mockMvc.perform(from(post("/api/v1/portal/access/revoke").header("X-Portal-Token", token), "10.0.0.4")).andExpect(status().isNoContent());
        mockMvc.perform(from(get("/api/v1/portal/claims/" + id).header("X-Portal-Token", token), "10.0.0.5")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A forged or expired token is refused")
    void forgedAndExpired() throws Exception {
        String id = claim("HUMAN_REVIEW");
        String good = protection.issue(id).token();
        String forged = good.substring(0, good.lastIndexOf('.') + 1) + "0".repeat(64);
        assertThat(protection.allows(forged, id)).isFalse();
        mockMvc.perform(from(get("/api/v1/portal/claims/" + id).header("X-Portal-Token", forged), "10.0.0.6")).andExpect(status().isNotFound());
        PortalProtection shortLived = new PortalProtection(null, tokenStore(), 3, 600, 0, "secret");
        String expired = shortLived.issue(id).token();
        Thread.sleep(5);
        assertThat(shortLived.allows(expired, id)).isFalse();
    }

    @Autowired private com.guidewire.fnol.api.persistence.PortalAccessTokenRepository tokenRepository;

    private com.guidewire.fnol.api.persistence.PortalAccessTokenRepository tokenStore() {
        return tokenRepository;
    }

    @Test
    @DisplayName("Uploads: only a real image by its bytes, not by its label; single use; an invalid link is counted")
    void uploads() throws Exception {
        String id = claim("HUMAN_REVIEW");
        String link = mapper.readTree(mockMvc.perform(post("/api/v1/fnol/" + id + "/upload-requests").header("X-Adjuster-Token", "test-adjuster-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"requestedBy\":\"ADJ-001\",\"angles\":[\"FRONT\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("uploadUrl").asText();
        String token = link.substring(link.lastIndexOf('/') + 1);
        String script = "data:image/png;base64," + Base64.getEncoder().encodeToString("<script>alert(1)</script>".getBytes());
        mockMvc.perform(from(post("/api/v1/public/upload/" + token).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("evidenceUris", List.of(script), "evidenceAngles", List.of("FRONT")))), "10.0.0.7")).andExpect(status().isBadRequest());
        mockMvc.perform(from(post("/api/v1/public/upload/" + token).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("evidenceUris", List.of("../../etc/passwd"), "evidenceAngles", List.of("FRONT")))), "10.0.0.7")).andExpect(status().isBadRequest());
        mockMvc.perform(from(post("/api/v1/public/upload/" + token).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("evidenceUris", List.of(PNG), "evidenceAngles", List.of("FRONT")))), "10.0.0.7")).andExpect(status().isOk());
        mockMvc.perform(from(post("/api/v1/public/upload/" + token).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("evidenceUris", List.of(PNG), "evidenceAngles", List.of("FRONT")))), "10.0.0.8")).andExpect(status().isGone());
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(from(get("/api/v1/public/upload/not-a-token-" + i), "10.0.0.9")).andExpect(status().isNotFound());
        }
        mockMvc.perform(from(get("/api/v1/public/upload/not-a-token"), "10.0.0.9")).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("A claim's documents cannot be fetched without a credential")
    void unauthorizedDownload() throws Exception {
        String id = claim("HUMAN_REVIEW");
        mockMvc.perform(get("/api/v1/fnol/" + id + "/documents")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/fnol/" + id + "/documents/any/content")).andExpect(status().isUnauthorized());
    }
}
