package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DeadlineResult;
import java.time.LocalDate;

public interface DeadlineRuleProvider {
    DeadlineResult deadline(String state, LocalDate incidentDate);
}
