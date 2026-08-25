package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.SubrogationResult;

public interface SubrogationScorer {
    SubrogationResult score(FNOLPayload payload, DamageAssessment damage);
}
