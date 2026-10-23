package com.guidewire.fnol.agents;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Prototype coverage rules. Not real underwriting rules; the coverage owner must confirm them. */
@Component
public class SyntheticCoverageRuleProvider implements CoverageRuleProvider {

    static final String SOURCE = "SYNTHETIC_PROTOTYPE_RULE";

    private static final Map<String, List<String>> ACCEPTED = Map.of(
            "AUTO", List.of("COLLISION", "COMPREHENSIVE"),
            "HOME", List.of("DWELLING", "PERSONAL_PROPERTY"));

    @Override
    public CoverageRule ruleFor(String lineOfBusiness) {
        String lob = lineOfBusiness == null ? "UNKNOWN" : lineOfBusiness.toUpperCase(Locale.ROOT);
        return new CoverageRule(lob, ACCEPTED.getOrDefault(lob, List.of()), SOURCE);
    }
}
