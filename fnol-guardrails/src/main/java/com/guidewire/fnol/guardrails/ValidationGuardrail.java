package com.guidewire.fnol.guardrails;

import com.guidewire.fnol.common.CoverageNotEligibleException;
import com.guidewire.fnol.common.Models.PolicyValidationResult;
import org.springframework.stereotype.Component;

@Component
public class ValidationGuardrail {
    public void requireCovered(PolicyValidationResult r) {
        // What the data cannot settle, and a policy not in force at the loss, are for a person to judge with the analysis in front of them.
        // They go on, and the Coverage factor fails, which sends them to a person. The AI refuses nothing here.
        if (r.policyFound() && (PolicyValidationResult.UNVERIFIED.equals(r.coverageState()) || PolicyValidationResult.NOT_IN_FORCE.equals(r.coverageState()))) {
            return;
        }
        if (!r.policyFound() || !r.policyActive() || !r.covered()) {
            throw new CoverageNotEligibleException(
                    "Policy " + r.policyNumber() + " is not eligible under synthetic coverage rules");
        }
    }
}
