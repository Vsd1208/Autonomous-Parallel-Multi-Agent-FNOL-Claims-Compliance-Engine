package com.guidewire.fnol.api;

import com.guidewire.fnol.api.persistence.AiFeedbackEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** AI governance figures for adjusters and model-risk reviewers (adjuster token required). */
@RestController
@RequestMapping("/api/v1/ai")
public class AiGovernanceController {

    private final AiGovernanceService governance;

    public AiGovernanceController(AiGovernanceService governance) {
        this.governance = governance;
    }

    /** Routing, flags, leakage, adjuster agreement, privacy and drift. */
    @GetMapping("/metrics")
    Map<String, Object> metrics() {
        return governance.metrics();
    }

    /** Every adjuster decision next to the AI's view: labelled data for retraining the trust score. */
    @GetMapping("/feedback")
    List<AiFeedbackEntity> feedback() {
        return governance.feedback();
    }
}
