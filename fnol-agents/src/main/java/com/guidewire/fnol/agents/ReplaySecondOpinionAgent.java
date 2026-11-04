package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.guidewire.fnol.common.Models.DamageExtent;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.SecondOpinion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** The second-opinion model's recorded, out-of-fold output for a known photo; none for any other. It calls no service. */
@Component
@Primary
@ConditionalOnProperty(name = "fnol.vision.provider", havingValue = "replay")
public class ReplaySecondOpinionAgent extends SecondOpinionAgent {

    private final ReplayAnswers answers;

    public ReplaySecondOpinionAgent(ReplayAnswers answers) {
        super("", null, null);
        this.answers = answers;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public CompletableFuture<SecondOpinion> start(FNOLPayload payload, String lineOfBusiness) {
        if (!"AUTO".equalsIgnoreCase(lineOfBusiness)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.completedFuture(answers.find(payload.evidenceUris()).map(a -> recorded(a.path("secondOpinion"))).orElse(null));
    }

    private static SecondOpinion recorded(JsonNode s) {
        Map<String, BigDecimal> probabilities = new LinkedHashMap<>();
        s.path("probabilities").fields().forEachRemaining(e -> probabilities.put(e.getKey(), e.getValue().decimalValue()));
        DamageExtent extent = s.hasNonNull("extentBand") ? new DamageExtent(s.path("extentBand").asText(), null) : null;
        return new SecondOpinion("recorded second opinion (out of fold)", probabilities, Map.of(), extent, null);
    }
}
