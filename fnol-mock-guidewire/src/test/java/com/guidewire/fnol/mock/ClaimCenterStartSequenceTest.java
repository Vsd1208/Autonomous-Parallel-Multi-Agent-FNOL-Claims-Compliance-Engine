package com.guidewire.fnol.mock;

import com.guidewire.fnol.common.Models.ClaimCreateResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "MOCK_CC_START_SEQUENCE=500")
class ClaimCenterStartSequenceTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("MOCK_CC_START_SEQUENCE sets the first issued claim number")
    void claimCenter_startsAtConfiguredSequence() {
        var request = Map.of(
                "policyNumber", "POL-AUTO-112233",
                "incidentDate", "2026-08-16",
                "state", "CA",
                "estimatedDamage", new BigDecimal("4200"),
                "reserve", new BigDecimal("4830"));

        ClaimCreateResponse created = rest.postForObject("/cc/claims", request, ClaimCreateResponse.class);

        assertThat(created.claimId()).isEqualTo("CLM-2026-000500");
    }
}
