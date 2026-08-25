package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DeadlineResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

@Component
public class SyntheticDeadlineRuleProvider implements DeadlineRuleProvider {
    private static final Map<String, Integer> DAYS = Map.of(
            "CA", 30,
            "NY", 35,
            "TX", 15,
            "FL", 20,
            "IL", 30
    );

    @Override
    public DeadlineResult deadline(String state, LocalDate incidentDate) {
        int days = DAYS.getOrDefault(state, 30);
        return new DeadlineResult(
                state,
                incidentDate.plusDays(days),
                "MOCK_RULE",
                "Synthetic prototype deadline; not legal advice."
        );
    }
}
