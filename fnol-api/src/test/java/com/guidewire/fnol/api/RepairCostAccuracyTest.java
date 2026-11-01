package com.guidewire.fnol.api;

import com.guidewire.fnol.api.persistence.AiFeedbackEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RepairCostAccuracyTest {

    private static AiFeedbackEntity decision(String cost, String source, String ai, String table) {
        AiFeedbackEntity f = new AiFeedbackEntity();
        f.finalRepairCost = cost == null ? null : new BigDecimal(cost);
        f.costSource = source;
        f.aiEstimate = new BigDecimal(ai);
        f.publishedEstimate = new BigDecimal(table);
        return f;
    }

    @Test
    @DisplayName("Every repair-cost source fits the ai_feedback.cost_source column (VARCHAR(20), migration V18)")
    void sourcesFitTheColumn() {
        var all = new java.util.HashSet<>(com.guidewire.fnol.common.Models.AdjusterReviewRequest.REPAIR_COST_SOURCES);
        all.addAll(com.guidewire.fnol.common.Models.AdjusterReviewRequest.EVIDENCE_SOURCES);
        all.add(AiGovernanceService.HELD);
        all.add(AiGovernanceService.INVESTIGATION);
        assertThat(all).allMatch(source -> source.length() <= 20);
    }

    @Test
    @DisplayName("One label per claim: a claim decided twice counts once, with its latest cost")
    void oneLabelPerClaim() {
        AiFeedbackEntity held = decision("5000", "INVESTIGATION_HOLD", "4200", "1300");
        held.claimId = "CLM-1";
        AiFeedbackEntity investigated = decision("3200", "INVESTIGATION", "4200", "1300");
        investigated.claimId = "CLM-1";
        Map<String, Object> out = AiGovernanceService.repairCostAccuracy(List.of(held, investigated));
        assertThat(out.get("claimsWithRepairCost")).isEqualTo(1);
        assertThat(out.get("evidenceBacked")).isEqualTo(1);
    }

    @Test
    @DisplayName("Accuracy uses evidence-backed costs only; an adjuster's judgement is counted but not measured against")
    @SuppressWarnings("unchecked")
    void judgementIsCountedButNotUsed() {
        Map<String, Object> out = AiGovernanceService.repairCostAccuracy(List.of(
                decision("2000", "INVOICE", "2000", "1000"),            // AI exact, table 50% off
                decision("4000", "INSPECTION", "5000", "4000"),         // AI 25% off, table exact
                decision("1000", "ADJUSTER_JUDGEMENT", "9000", "100"),  // would distort both: excluded
                decision(null, null, "3000", "3000")));                 // no cost known
        assertThat(out.get("claimsWithRepairCost")).isEqualTo(3);
        assertThat(out.get("evidenceBacked")).isEqualTo(2);
        assertThat((Map<String, Long>) out.get("bySource")).containsEntry("ADJUSTER_JUDGEMENT", 1L).containsEntry("INVOICE", 1L);
        Map<String, Object> ai = (Map<String, Object>) out.get("ai");
        assertThat(ai.get("claims")).isEqualTo(2);
        assertThat((BigDecimal) ai.get("medianAbsPercentError")).isEqualByComparingTo("12.5");   // (0% + 25%) / 2
        assertThat(ai.get("within25Percent")).isEqualTo(2L);
        Map<String, Object> table = (Map<String, Object>) out.get("costTable");
        assertThat((BigDecimal) table.get("medianAbsPercentError")).isEqualByComparingTo("25.0"); // (50% + 0%) / 2
        assertThat(table.get("within25Percent")).isEqualTo(1L);
    }
}
