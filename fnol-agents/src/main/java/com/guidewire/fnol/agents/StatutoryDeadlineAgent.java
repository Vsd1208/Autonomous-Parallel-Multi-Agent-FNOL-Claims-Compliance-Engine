package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DeadlineResult;
import com.guidewire.fnol.common.Models.FNOLPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class StatutoryDeadlineAgent implements FNOLAgent<FNOLPayload, DeadlineResult> {
    private final DeadlineRuleProvider provider;
    private IndianClaimClock india;      // only with fnol.claim-clock.india.enabled

    public StatutoryDeadlineAgent(DeadlineRuleProvider provider) {
        this.provider = provider;
    }

    @Autowired(required = false)
    public void setIndia(IndianClaimClock india) {
        this.india = india;
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
        return execute(input, lineOfBusiness, proofOfLossComplete, LocalDate.now());
    }

    /**
     * As above, for a claim the insurer received on {@code noticeDate}, which may be before today: the clocks run from when the
     * claim was received, not from when it was analysed (the engine may have been down in between).
     */
    public DeadlineResult execute(FNOLPayload input, String lineOfBusiness, boolean proofOfLossComplete, LocalDate noticeDate, String jurisdiction) {
        if ("IN".equals(jurisdiction) && india != null) {
            return india.deadline(input.state(), noticeDate);
        }
        return execute(input, lineOfBusiness, proofOfLossComplete, noticeDate);
    }

    public DeadlineResult execute(FNOLPayload input, String lineOfBusiness, boolean proofOfLossComplete, LocalDate noticeDate) {
        return provider.deadline(input.state(), input.incidentDate(), lineOfBusiness, noticeDate,
                proofOfLossComplete ? noticeDate : null);
    }

    /** The decision deadline once the proof of loss is complete on {@code completedOn} (e.g. requested photos arrived). */
    public DeadlineResult afterProofOfLoss(String state, LocalDate incidentDate, String lineOfBusiness,
                                           LocalDate noticeDate, LocalDate completedOn) {
        return provider.deadline(state, incidentDate, lineOfBusiness, noticeDate, completedOn);
    }
}
