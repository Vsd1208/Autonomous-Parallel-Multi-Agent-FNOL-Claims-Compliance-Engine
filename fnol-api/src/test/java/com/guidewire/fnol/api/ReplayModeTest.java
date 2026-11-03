package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.agents.GeminiVisionProvider;
import com.guidewire.fnol.agents.ReplayAnswers;
import com.guidewire.fnol.agents.ReplayVisionProvider;
import com.guidewire.fnol.agents.VisionProvider;
import com.guidewire.fnol.common.Models.ClaimCreateResponse;
import com.guidewire.fnol.common.Models.Coverage;
import com.guidewire.fnol.common.Models.Policy;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Replay mode: real recorded model answers go through the real trust score and checks, with no model and no network. */
@SpringBootTest(properties = {"fnol.lite-mode=true", "fnol.vision.provider=replay", "fnol.replay.dir=../demo-scenarios/replay",
        "gemini.vision.enabled=false", "fnol.second-opinion.url=", "fnol.redaction.url="})
@AutoConfigureMockMvc
class ReplayModeTest {

    private static final Path REPLAY = Path.of("..", "demo-scenarios", "replay");
    private static final AtomicInteger DAYS = new AtomicInteger(1);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ApplicationContext context;
    @MockBean private PolicyCenterClient policyCenter;
    @MockBean private ClaimCenterClient claimCenter;

    @BeforeEach
    void policy() {
        LocalDate start = LocalDate.now().minusMonths(6);
        when(policyCenter.getPolicy("POL-AUTO-112233")).thenReturn(new Policy("POL-AUTO-112233", "AUTO", "ACTIVE", start, start.plusYears(1),
                "Avery Johnson", "CA", "2025 Camry", List.of()));
        when(policyCenter.getCoverages("POL-AUTO-112233")).thenReturn(List.of(new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))));
        when(claimCenter.createClaim(any())).thenAnswer(i -> new ClaimCreateResponse("CLM-2026-0R" + String.format("%04d", DAYS.incrementAndGet()), "OPEN"));
    }

    private JsonNode submit(String uri) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("policyNumber", "POL-AUTO-112233");
        payload.put("incidentDate", LocalDate.now().minusDays(DAYS.incrementAndGet() % 25 + 1).toString());
        payload.put("state", "CA");
        payload.put("description", "Recorded photo.");
        payload.put("claimantName", "Avery Johnson");
        payload.put("evidenceUris", Collections.nCopies(5, uri));
        payload.put("evidenceAngles", List.of("FRONT", "REAR", "LEFT", "RIGHT", "CLOSE_UP"));
        payload.put("channel", "API");
        payload.put("claimedAmount", 3000);
        String body = mockMvc.perform(post("/api/v1/fnol/intake").header("X-Adjuster-Token", "test-adjuster-token")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(payload)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private JsonNode scenario(String name) throws Exception {
        return mapper.readTree(Files.readString(REPLAY.resolve("scenario-" + name + ".json")));
    }

    private String uriOf(JsonNode scenario) throws Exception {
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(REPLAY.resolve(scenario.path("photo").asText())));
    }

    private List<String> gateFlags(JsonNode response) {
        return mapper.convertValue(response.path("gateFlags"), mapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    @Test
    @DisplayName("A recorded photo passes: found by its hash, with the trust score and second opinion, and no vision check objects")
    void passingPhotos() throws Exception {
        for (String name : List.of("pass-1", "pass-2", "pass-3")) {
            JsonNode s = scenario(name);
            JsonNode r = submit(uriOf(s));
            JsonNode d = r.path("branchA").path("damageAssessment");
            assertThat(d.path("damageType").asText()).isEqualTo(s.path("recordedDamageType").asText());
            assertThat(d.path("visionSource").asText()).startsWith("replay of gemini-2.5-flash");
            assertThat(d.path("confidence").decimalValue()).isGreaterThanOrEqualTo(new BigDecimal("0.85"));
            assertThat(d.path("rawConfidence").isNumber()).as("the trust score replaced Gemini's confidence").isTrue();
            assertThat(r.path("branchA").path("secondOpinion").path("damageProbabilities").isObject()).isTrue();
            assertThat(gateFlags(r)).doesNotContain("AMBIGUOUS_DAMAGE_TYPE", "MODEL_DISAGREEMENT");
            assertThat(r.path("notes").toString()).contains("replaying recorded model answers");
        }
    }

    @Test
    @DisplayName("The margin check stops a recorded hedged answer")
    void ambiguousPhoto() throws Exception {
        JsonNode r = submit(uriOf(scenario("ambiguous")));
        assertThat(gateFlags(r)).contains("AMBIGUOUS_DAMAGE_TYPE");
        assertThat(r.path("status").asText()).isEqualTo("HUMAN_REVIEW");
    }

    @Test
    @DisplayName("The second opinion stops a confident answer that the ground truth says is wrong")
    void disagreementPhoto() throws Exception {
        JsonNode r = submit(uriOf(scenario("disagreement")));
        assertThat(gateFlags(r)).contains("MODEL_DISAGREEMENT");
        assertThat(r.path("status").asText()).isEqualTo("HUMAN_REVIEW");
    }

    @Test
    @DisplayName("An unknown photo gets the safe placeholder and goes to a person: it is never given someone else's answer")
    void unknownPhoto() throws Exception {
        String unknown = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString("not in the recorded set".getBytes());
        JsonNode r = submit(unknown);
        JsonNode d = r.path("branchA").path("damageAssessment");
        assertThat(d.path("damageType").asText()).isEqualTo("UNKNOWN");
        assertThat(d.path("confidence").decimalValue()).isLessThan(new BigDecimal("0.85"));
        assertThat(r.path("branchA").path("secondOpinion").isMissingNode() || r.path("branchA").path("secondOpinion").isNull()).isTrue();
        assertThat(r.path("status").asText()).isEqualTo("HUMAN_REVIEW");
    }

    @Test
    @DisplayName("No external model can be reached: no Gemini provider exists, and replay refuses to start with Gemini on")
    void noExternalCall() {
        assertThat(context.getBeansOfType(GeminiVisionProvider.class)).isEmpty();
        assertThat(context.getBean(VisionProvider.class)).isInstanceOf(ReplayVisionProvider.class);
        assertThatThrownBy(() -> new ReplayAnswers("../demo-scenarios/replay", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("external model");
    }

    @Test
    @DisplayName("The bundle matches its manifest and holds only what may be redistributed, and health says it is replaying")
    void bundleAndHealth() throws Exception {
        JsonNode manifest = mapper.readTree(Files.readString(REPLAY.resolve("manifest.json")));
        JsonNode answers = mapper.readTree(Files.readString(REPLAY.resolve("answers.json")));
        assertThat(answers.size()).isEqualTo(manifest.path("photos").asInt()).isEqualTo(66);
        for (JsonNode a : answers) {
            assertThat(a.path("licence").asText()).isEqualTo("CC0-1.0");
            assertThat(Files.exists(REPLAY.resolve(a.path("photo").asText()))).isTrue();
            assertThat(a.has("usage")).isFalse();
        }
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(jsonPath("$.secondOpinion").value("REPLAYING_RECORDED_ANSWERS"))
                .andExpect(jsonPath("$.vision").value(org.hamcrest.Matchers.containsString("replaying recorded model answers (recorded 30-31 Oct 2026)")));
    }
}
