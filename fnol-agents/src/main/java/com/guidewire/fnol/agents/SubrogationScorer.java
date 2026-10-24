package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.SubrogationResult;

/**
 * Subrogation runs in two steps so Branch B stays independent of Branch A:
 * {@link #assessLiability} (Branch B, text only, with compliance notes) and
 * {@link #applySeverity} (reconciliation gate, adds Branch A's damage severity).
 */
public interface SubrogationScorer {

    /** Third-party liability from the description; recommendedAction is PENDING_SEVERITY. */
    SubrogationResult assessLiability(FNOLPayload payload);

    /** Final score and action once the damage severity is known. */
    SubrogationResult applySeverity(SubrogationResult liability, DamageAssessment damage);

    default SubrogationResult score(FNOLPayload payload, DamageAssessment damage) {
        return applySeverity(assessLiability(payload), damage);
    }
}
