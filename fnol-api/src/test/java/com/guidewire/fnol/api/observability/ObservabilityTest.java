package com.guidewire.fnol.api.observability;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.ClaimCreateResponse;
import com.guidewire.fnol.common.Models.Coverage;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.Policy;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityTest {

    private static final String TOKEN = "test-adjuster-token";
    private static final java.util.concurrent.atomic.AtomicInteger lossesSubmitted = new java.util.concurrent.atomic.AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private PolicyCenterClient policyCenterClient;
    @MockBean private ClaimCenterClient claimCenterClient;

    @BeforeEach
    void stubGuidewire() {
        LocalDate start = LocalDate.now().minusMonths(6);
        when(policyCenterClient.getPolicy("POL-AUTO-112233")).thenReturn(new Policy("POL-AUTO-112233", "AUTO", "ACTIVE",
                start, start.plusYears(1), "Avery Johnson", "CA", "2025 Camry", List.of()));
        when(policyCenterClient.getCoverages("POL-AUTO-112233")).thenReturn(List.of(new Coverage("COLLISION", new BigDecimal("50000"), new BigDecimal("1000"))));
        when(claimCenterClient.createClaim(any())).thenAnswer(i -> new ClaimCreateResponse("CLM-2026-0009" + lossesSubmitted.get(), "OPEN"));
    }

    @Test
    @DisplayName("Liveness and readiness answer without a credential, and give no detail away")
    void probes() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.details").doesNotExist()).andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("Other actuator endpoints are not exposed")
    void nothingElseExposed() throws Exception {
        for (String path : List.of("/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/heapdump", "/actuator/loggers")) {
            var response = mockMvc.perform(get(path).header("X-Adjuster-Token", TOKEN)).andReturn().getResponse();
            assertThat(response.getStatus()).as(path).isNotEqualTo(200);
            assertThat(response.getContentAsString()).as(path).doesNotContain(TOKEN).doesNotContain("propertySources");
        }
    }

    @Test
    @DisplayName("Metrics need a credential, and show queue depth and branch timings with no claim data")
    void metrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        submit("Scraped a pole in a parking garage.");

        String body = mockMvc.perform(get("/actuator/prometheus").header("X-Adjuster-Token", TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("fnol_inbox_depth{status=\"DEAD_LETTER\"").contains("fnol_inbox_depth{status=\"PENDING\"");
        assertThat(body).contains("fnol_branch_duration_seconds_count{branch=\"operational\",outcome=\"ok\"");
        assertThat(body).contains("fnol_branch_duration_seconds_count{branch=\"legal\",outcome=\"ok\"");
        assertThat(body).doesNotContain("POL-AUTO").doesNotContain("Avery");
    }

    @Test
    @DisplayName("Each answer carries a correlation ID; a well-formed one is kept, and a hostile one replaced")
    void correlationIds() throws Exception {
        mockMvc.perform(get("/api/v1/health").header(CorrelationIdFilter.HEADER, "abc-12345")).andExpect(header().string(CorrelationIdFilter.HEADER, "abc-12345"));
        String hostile = "x\r\nFAKE-LOG-LINE " + "y".repeat(10);
        String answered = mockMvc.perform(get("/api/v1/health").header(CorrelationIdFilter.HEADER, hostile)).andReturn().getResponse().getHeader(CorrelationIdFilter.HEADER);
        assertThat(answered).isNotEqualTo(hostile).matches("[A-Za-z0-9-]{8,64}");
        assertThat(mockMvc.perform(get("/api/v1/health")).andReturn().getResponse().getHeader(CorrelationIdFilter.HEADER)).isNotBlank();
    }

    @Test
    @DisplayName("A claim's personal data does not reach the logs, even on the branch threads")
    void noPersonalDataInLogs(CapturedOutput output) throws Exception {
        submit("Call Avery at 415-555-0142 or avery.private@example.com, SSN 321-54-9876, who lives at 42 Maple Grove Road.");

        assertThat(output.getAll()).doesNotContain("415-555-0142").doesNotContain("avery.private@example.com").doesNotContain("321-54-9876")
                .doesNotContain("Maple Grove").doesNotContain("Avery Johnson");
    }

    /** One loss per call: the same policy, date and state would be refused as a duplicate. It must be analysed, or these tests prove nothing. */
    private void submit(String description) throws Exception {
        FNOLPayload payload = new FNOLPayload("POL-AUTO-112233", LocalDate.now().minusDays(lossesSubmitted.incrementAndGet()), "CA", description, "Avery Johnson", null, null,
                List.of("mock://photo/normal.jpg"), "WEB", null, null, new BigDecimal("2000"), List.of("CLOSE_UP"));
        mockMvc.perform(post("/api/v1/fnol/intake").header("X-Adjuster-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("The production log pattern carries the trace and masks identifiers that slip through")
    @SuppressWarnings("unchecked")
    void logPatternMasks() throws Exception {
        Map<String, Object> yml;
        try (InputStream in = new FileInputStream("src/main/resources/application.yml")) {
            yml = new Yaml().load(in);
        }
        String pattern = (String) ((Map<String, Object>) ((Map<String, Object>) yml.get("logging")).get("pattern")).get("console");

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(pattern);
        layout.start();

        ch.qos.logback.classic.Logger logger = context.getLogger("t");
        LoggingEvent event = new LoggingEvent("t", logger, ch.qos.logback.classic.Level.INFO,
                "policy=POL-AUTO-112233 ssn 123-45-6789 mail a.b@example.com phone 415-555-0100 estimate 4200", null, null);
        event.setMDCPropertyMap(Map.of("correlationId", "corr-1", "claimId", "CLM-2026-000001"));
        String line = layout.doLayout(event);

        assertThat(line).contains("correlationId=corr-1").contains("claimId=CLM-2026-000001").contains("estimate 4200");
        assertThat(line).doesNotContain("POL-AUTO-112233").doesNotContain("123-45-6789").doesNotContain("a.b@example.com").doesNotContain("415-555-0100");
    }
}
