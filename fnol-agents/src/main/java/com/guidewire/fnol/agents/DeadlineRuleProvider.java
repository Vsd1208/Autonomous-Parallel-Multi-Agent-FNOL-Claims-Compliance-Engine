package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DeadlineResult;
import java.time.LocalDate;

public interface DeadlineRuleProvider {
    DeadlineResult deadline(String state, LocalDate incidentDate);

    /**
     * Deadlines for a claim received on {@code noticeDate}; {@code proofOfLossDate} is null while the proof of loss is
     * incomplete (e.g. requested photos still missing), so a clock that starts from it has not started yet.
     */
    default DeadlineResult deadline(String state, LocalDate incidentDate, String lineOfBusiness, LocalDate noticeDate,
                                    LocalDate proofOfLossDate) {
        return deadline(state, incidentDate);
    }
}
