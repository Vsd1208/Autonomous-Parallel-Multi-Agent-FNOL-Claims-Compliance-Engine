package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DeadlineResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

/**
 * Prototype statutory deadlines. Every day count below is a PLACEHOLDER, not a real state
 * regulation; Compliance must confirm real rules before production use.
 */
/** Placeholder rules for tests; the application uses {@link CitedDeadlineRuleProvider}. */
public class SyntheticDeadlineRuleProvider implements DeadlineRuleProvider {

    /** Days from the incident to acknowledge the claim (placeholder). */
    private static final Map<String, Integer> ACKNOWLEDGE_DAYS = Map.of(
            "CA", 30,
            "NY", 35,
            "TX", 15,
            "FL", 20,
            "IL", 30
    );

    /** Days from the incident to accept or deny the claim (placeholder). */
    private static final Map<String, Integer> DECISION_DAYS = Map.of(
            "CA", 70,
            "NY", 65,
            "TX", 45,
            "FL", 90,
            "IL", 75
    );

    static final int WARNING_WINDOW_DAYS = 3;

    private final Clock clock;

    @Autowired
    public SyntheticDeadlineRuleProvider() {
        this(Clock.systemDefaultZone());
    }

    public SyntheticDeadlineRuleProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public DeadlineResult deadline(String state, LocalDate incidentDate) {
        int ackDays = ACKNOWLEDGE_DAYS.getOrDefault(state, 30);
        int decisionDays = DECISION_DAYS.getOrDefault(state, 60);
        LocalDate acknowledgeBy = incidentDate.plusDays(ackDays);
        LocalDate decisionBy = incidentDate.plusDays(decisionDays);
        return new DeadlineResult(
                state,
                acknowledgeBy,
                "MOCK_RULE",
                "PROTOTYPE PLACEHOLDER: acknowledgeBy (incident + " + ackDays + " days) and decisionBy (incident + "
                        + decisionDays + " days) are synthetic values, not state regulations and not legal advice. "
                        + "Compliance must confirm real rules before production use.",
                acknowledgeBy,
                decisionBy,
                urgency(acknowledgeBy)
        );
    }

    /** CRITICAL when past due, WARNING when due within three days, otherwise OK. */
    private String urgency(LocalDate dueBy) {
        LocalDate today = LocalDate.now(clock);
        if (today.isAfter(dueBy)) return "CRITICAL";
        if (!today.plusDays(WARNING_WINDOW_DAYS).isBefore(dueBy)) return "WARNING";
        return "OK";
    }
}
