package com.guidewire.fnol.agents;
import com.guidewire.fnol.common.Models.*;
public interface VisionProvider {
    DamageAssessment assess(FNOLPayload payload);

    /** With the policy's line of business (AUTO, HOME) so the provider can assess vehicle or property damage. */
    default DamageAssessment assess(FNOLPayload payload, String lineOfBusiness) {
        return assess(payload);
    }
}
