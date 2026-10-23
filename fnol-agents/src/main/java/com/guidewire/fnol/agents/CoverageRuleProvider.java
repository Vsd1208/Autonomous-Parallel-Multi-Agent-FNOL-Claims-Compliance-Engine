package com.guidewire.fnol.agents;

import java.util.List;

/** Which coverage types satisfy a loss for a given line of business (policy type). */
public interface CoverageRuleProvider {

    CoverageRule ruleFor(String lineOfBusiness);

    /**
     * @param acceptedCoverageTypes in order of preference; empty means no coverage qualifies
     */
    record CoverageRule(String lineOfBusiness, List<String> acceptedCoverageTypes, String source) {
        public String describe() {
            return acceptedCoverageTypes.isEmpty()
                    ? "No coverage rule for line of business " + lineOfBusiness + " (" + source + ")"
                    : lineOfBusiness + " requires " + String.join(" or ", acceptedCoverageTypes) + " (" + source + ")";
        }
    }
}
