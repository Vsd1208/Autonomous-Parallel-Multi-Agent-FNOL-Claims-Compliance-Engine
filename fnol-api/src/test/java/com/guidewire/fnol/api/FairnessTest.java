package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Unfair-discrimination monitoring (four-fifths rule), including the fraud anomaly flag. */
class FairnessTest {

    private final AiGovernanceService service = new AiGovernanceService(null, null, new ObjectMapper());

    private static ClaimEntity claim(String id, String state, String status, String gateFlags) {
        ClaimEntity c = new ClaimEntity();
        c.claimId = id;
        c.state = state;
        c.channel = "WEB";
        c.status = status;
        c.gateFlags = gateFlags;
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> fairness, String dimension, String group) {
        return ((List<Map<String, Object>>) fairness.get(dimension)).stream()
                .filter(r -> group.equals(r.get("group"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("A state whose claims are flagged FRAUD_ANOMALY far more often is marked for review")
    void fraudFlagDisparityIsReviewed() {
        List<ClaimEntity> claims = new ArrayList<>();
        Map<String, AiGovernanceService.AiView> views = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            // CA: 1 of 10 flagged. TX: 5 of 10 flagged.
            claims.add(claim("CA-" + i, "CA", "HUMAN_REVIEW", i == 0 ? "FRAUD_ANOMALY" : null));
            claims.add(claim("TX-" + i, "TX", "HUMAN_REVIEW", i < 5 ? "AMBIGUOUS_DAMAGE_TYPE,FRAUD_ANOMALY" : null));
        }
        for (ClaimEntity c : claims) {
            views.put(c.claimId, new AiGovernanceService.AiView("AUTO", "FRONT_BUMPER", c.status, BigDecimal.ONE,
                    null, null, null, null, null, null, null));
        }

        Map<String, Object> fairness = service.fairness(claims, views, List.of());

        Map<String, Object> ca = row(fairness, "state", "CA");
        Map<String, Object> tx = row(fairness, "state", "TX");
        assertThat((BigDecimal) ca.get("fraudAnomalyRate")).isEqualByComparingTo("0.1");
        assertThat((BigDecimal) tx.get("fraudAnomalyRate")).isEqualByComparingTo("0.5");
        assertThat(ca.get("fraudClearStatus")).isEqualTo("OK");
        // TX's not-flagged rate 0.5 is 0.56 of CA's 0.9: below four-fifths
        assertThat(tx.get("fraudClearStatus")).isEqualTo("REVIEW");
    }
}
