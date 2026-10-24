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

    /** Branch B: third-party liability assessment and compliance notes. */
    public SubrogationResult assessLiability(FNOLPayload payload) {
        return scorer.assessLiability(payload);
    }

    /** Reconciliation gate: final score with Branch A's damage severity. */
    public SubrogationResult finalizeScore(SubrogationResult liability, DamageAssessment damage) {
        return scorer.applySeverity(liability, damage);
    }

    public SubrogationResult execute(FNOLPayload payload, DamageAssessment damage) {
        return scorer.score(payload, damage);
    }
}
