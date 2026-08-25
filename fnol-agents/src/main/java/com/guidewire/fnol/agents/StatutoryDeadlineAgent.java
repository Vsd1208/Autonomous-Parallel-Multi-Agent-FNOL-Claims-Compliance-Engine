package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DeadlineResult;
import com.guidewire.fnol.common.Models.FNOLPayload;
import org.springframework.stereotype.Component;

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
}
