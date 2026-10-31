package com.guidewire.fnol.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.FraudAnomaly;
import com.guidewire.fnol.common.Models.FraudContribution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FraudAnomalyScorerTest {

    private final StpProperties stp = new StpProperties();

    private static JsonNode params() throws Exception {
        try (InputStream in = new ClassPathResource(FraudAnomalyScorer.RESOURCE).getInputStream()) {
            return new ObjectMapper().readTree(in);
        }
    }

    private static Map<String, Object> claim(Double ratio, Double policyAge, Double lag, Double prior,
                                             Double disagreement, boolean reused) {
        Map<String, Object> x = new HashMap<>();
        x.put("claimRatio", ratio);
        x.put("policyAgeDays", policyAge);
        x.put("reportLagDays", lag);
        x.put("priorClaims12m", prior);
        x.put("secondOpinionDisagreement", disagreement);
        x.put("photoReused", reused);
        return x;
    }

    @Test
    @DisplayName("Java gives the same scores as tools/fraud-eval/fraud_eval.py on its reference cases")
    void matchesPythonReference() throws Exception {
        JsonNode json = params();
        FraudAnomalyScorer scorer = new FraudAnomalyScorer(json, stp);
        assertThat(json.path("referenceCases").size()).isGreaterThan(0);
        for (JsonNode c : json.path("referenceCases")) {
            FraudAnomaly a = scorer.score(claim(c.path("claimRatio").asDouble(), c.path("policyAgeDays").asDouble(),
                    c.path("reportLagDays").asDouble(), c.path("priorClaims12m").asDouble(),
                    c.path("secondOpinionDisagreement").asDouble(), c.path("photoReused").asInt() == 1));
            assertThat(a.score().doubleValue()).isCloseTo(c.path("expectedScore").asDouble(), within(0.006));
        }
    }

    @Test
    @DisplayName("An ordinary claim scores low; several unusual details together cross the threshold")
    void ordinaryVersusUnusual() throws Exception {
        FraudAnomalyScorer scorer = new FraudAnomalyScorer(params(), stp);
        FraudAnomaly ordinary = scorer.score(claim(1.0, 900.0, 2.0, 0.0, 0.2, false));
        assertThat(ordinary.anomalous()).isFalse();
        assertThat(ordinary.score()).isLessThan(BigDecimal.ONE);

        FraudAnomaly unusual = scorer.score(claim(1.6, 20.0, 45.0, 2.0, 0.2, false));
        assertThat(unusual.anomalous()).isTrue();
        assertThat(unusual.topContributors()).hasSize(3)
                .extracting(FraudContribution::points).isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(unusual.basis()).contains("SYNTHETIC");
    }

    @Test
    @DisplayName("Missing features add nothing; one feature adds at most the cap")
    void missingAndCapped() throws Exception {
        FraudAnomalyScorer scorer = new FraudAnomalyScorer(params(), stp);
        FraudAnomaly nothing = scorer.score(claim(null, null, null, null, null, false));
        assertThat(nothing.score()).isEqualByComparingTo("0");
        assertThat(nothing.topContributors()).isEmpty();

        FraudAnomaly extreme = scorer.score(claim(50.0, null, null, null, null, false));
        assertThat(extreme.score()).isEqualByComparingTo(BigDecimal.valueOf(params().path("cap").asDouble()));
    }

    @Test
    @DisplayName("A reused photo adds its fixed weight; the threshold can be overridden; the score can be turned off")
    void configuration() throws Exception {
        FraudAnomalyScorer scorer = new FraudAnomalyScorer(params(), stp);
        assertThat(scorer.score(claim(null, null, null, null, null, true)).score()).isEqualByComparingTo("3.00");

        stp.setFraudAnomalyThreshold(new BigDecimal("2.5"));
        assertThat(scorer.score(claim(null, null, null, null, null, true)).anomalous()).isTrue();

        stp.setFraudAnomalyEnabled(false);
        assertThat(scorer.isActive()).isFalse();
        assertThat(scorer.score(claim(1.6, 20.0, 45.0, 2.0, 0.2, true))).isNull();
        assertThat(FraudAnomalyScorer.none().isActive()).isFalse();
    }
}
