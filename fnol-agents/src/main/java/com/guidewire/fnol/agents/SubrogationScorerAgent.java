package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.SubrogationResult;
import org.springframework.stereotype.Component;

@Component
public class SubrogationScorerAgent {
    private final SubrogationScorer scorer;

    public SubrogationScorerAgent(SubrogationScorer scorer) {
        this.scorer = scorer;
    }

    public SubrogationResult execute(FNOLPayload payload, DamageAssessment damage) {
        return scorer.score(payload, damage);
    }
}
