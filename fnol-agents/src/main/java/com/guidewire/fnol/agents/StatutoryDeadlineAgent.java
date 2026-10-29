package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DeadlineResult;
import com.guidewire.fnol.common.Models.FNOLPayload;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class StatutoryDeadlineAgent implements FNOLAgent<FNOLPayload, DeadlineResult> {
    private final DeadlineRuleProvider provider;

    public StatutoryDeadlineAgent(DeadlineRuleProvider provider) {
        this.provider = provider;
    }

    @Override
    public DeadlineResult execute(FNOLPayload input) {
        return provider.deadline(input.state(), input.incidentDate());
    }

    /**
     * Deadlines for a claim received today: acknowledgement from notice; the decision clock from notice or, where the
     * rule says, from a complete proof of loss (not yet started when {@code proofOfLossComplete} is false).
     */
    public DeadlineResult execute(FNOLPayload input, String lineOfBusiness, boolean proofOfLossComplete) {
        LocalDate today = LocalDate.now();
        return provider.deadline(input.state(), input.incidentDate(), lineOfBusiness, today,
                proofOfLossComplete ? today : null);
    }

    /** The decision deadline once the proof of loss is complete on {@code completedOn} (e.g. requested photos arrived). */
    public DeadlineResult afterProofOfLoss(String state, LocalDate incidentDate, String lineOfBusiness,
                                           LocalDate noticeDate, LocalDate completedOn) {
        return provider.deadline(state, incidentDate, lineOfBusiness, noticeDate, completedOn);
    }
}
