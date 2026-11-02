package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The initial reserve comes from a table by line, coverage and severity; the AI can only move it within a band. */
class InitialReserveTest {

    private static final BigDecimal MAX = new BigDecimal("0.40");
    private final ReserveCalculatorAgent agent = new ReserveCalculatorAgent(new TableInitialReserveProvider(),
            ReserveCalculatorAgent.AVERAGE_TABLE, MAX);

    private static DamageAssessment damage(String severity, String estimate) {
        return new DamageAssessment("FRONT_BUMPER", severity, new BigDecimal(estimate), new BigDecimal("0.91"), "CLEAR", Map.of());
    }

    private static PolicyValidationResult coverage(String type, String limit, String deductible) {
        return new PolicyValidationResult("POL-AUTO-112233", true, true, true, true, type,
                limit == null ? null : new BigDecimal(limit), deductible == null ? null : new BigDecimal(deductible), List.of());
    }

    private static final ReserveCalculatorAgent.Adjustment BLOCKED = ReserveCalculatorAgent.Adjustment.blocked("trust score below 0.85");

    @ParameterizedTest(name = "{0} / {1} / {2} is {3} ({4})")
    @CsvSource({
            "AUTO, COLLISION, MINOR, 1698, PUBLISHED",
            "AUTO, COLLISION, MODERATE, 4818, PUBLISHED",
            "AUTO, COLLISION, SEVERE, 11708, PUBLISHED",
            "AUTO, COMPREHENSIVE, MINOR, 1440, PUBLISHED",
            "AUTO, COMPREHENSIVE, MODERATE, 5000, SYNTHETIC",
            "AUTO, COMPREHENSIVE, SEVERE, 12000, SYNTHETIC",
            "HOME, DWELLING, MINOR, 1500, SYNTHETIC",
            "HOME, DWELLING, MODERATE, 6000, SYNTHETIC",
            "HOME, DWELLING, SEVERE, 25000, SYNTHETIC",
            "HOME, PERSONAL_PROPERTY, MINOR, 1000, SYNTHETIC",
            "HOME, PERSONAL_PROPERTY, MODERATE, 4000, SYNTHETIC",
            "HOME, PERSONAL_PROPERTY, SEVERE, 15000, SYNTHETIC",
    })
    @DisplayName("Every line, coverage and severity has a row, and says what stands behind it")
    void tableLookup(String line, String cover, String severity, String amount, String provenance) {
        InitialReserveProvider.InitialReserve row = new TableInitialReserveProvider().lookup(line, cover, severity).orElseThrow();
        assertThat(row.amount()).isEqualByComparingTo(amount);
        assertThat(row.provenance()).isEqualTo(provenance);
        if ("SYNTHETIC".equals(provenance)) {
            assertThat(row.source()).startsWith("synthetic prototype value");
        } else {
            assertThat(row.source()).contains("(").doesNotStartWith("synthetic");   // a titled source with its date
        }
    }

    @Test
    @DisplayName("The reserve does not follow a stock dollar estimate when the adjustment is blocked")
    void blockedAdjustmentKeepsTheBase() {
        ReserveResult r = agent.reserve(damage("SEVERE", "15000"), coverage("COLLISION", "50000", "1000"), null, "AUTO", BLOCKED);
        assertThat(r.recommendedReserve()).isEqualByComparingTo("10708");   // 11,708 - 1,000
        assertThat(r.breakdown().method()).isEqualTo("AVERAGE_TABLE");
        assertThat(r.breakdown().baseReserve()).isEqualByComparingTo("11708");
        assertThat(r.breakdown().adjustmentAllowed()).isFalse();
        assertThat(r.breakdown().adjustmentReason()).contains("trust score");
        assertThat(r.breakdown().supportedAmount()).isEqualByComparingTo("11708");
    }

    @Test
    @DisplayName("Allowed: the AI moves the base toward its figure, inside the band")
    void allowedAdjustmentMovesWithinTheBand() {
        ReserveResult inside = agent.reserve(damage("MODERATE", "4200"), coverage("COLLISION", "50000", "1000"), null, "AUTO",
                ReserveCalculatorAgent.Adjustment.allowed(new BigDecimal("4200"), "trust score passed; no vision flag"));
        assertThat(inside.breakdown().adjustmentAllowed()).isTrue();
        assertThat(inside.breakdown().supportedAmount()).isEqualByComparingTo("4200");
        assertThat(inside.recommendedReserve()).isEqualByComparingTo("3200");

        ReserveResult up = agent.reserve(damage("MODERATE", "90000"), coverage("COLLISION", "90000", "0"), null, "AUTO",
                ReserveCalculatorAgent.Adjustment.allowed(new BigDecimal("90000"), "ok"));
        assertThat(up.recommendedReserve()).isEqualByComparingTo("6745");   // 4,818 x 1.4, HALF_UP

        ReserveResult down = agent.reserve(damage("MODERATE", "100"), coverage("COLLISION", "90000", "0"), null, "AUTO",
                ReserveCalculatorAgent.Adjustment.allowed(new BigDecimal("100"), "ok"));
        assertThat(down.recommendedReserve()).isEqualByComparingTo("2891");  // 4,818 x 0.6, HALF_UP
    }

    @Test
    @DisplayName("Allowed but with no signal: the base stands")
    void noSignal() {
        ReserveResult r = agent.reserve(damage("MODERATE", "4200"), coverage("COLLISION", "50000", "0"), null, "AUTO",
                ReserveCalculatorAgent.Adjustment.allowed(null, "ok"));
        assertThat(r.recommendedReserve()).isEqualByComparingTo("4818");
    }

    @Test
    @DisplayName("Caps: never above the amount claimed; less the deductible; never below zero; at most the limit")
    void caps() {
        ReserveResult claimed = agent.reserve(damage("SEVERE", "15000"), coverage("COLLISION", "50000", "1000"), new BigDecimal("7000"), "AUTO", BLOCKED);
        assertThat(claimed.recommendedReserve()).isEqualByComparingTo("6000");
        assertThat(claimed.settlementBasis()).isEqualByComparingTo("7000");
        assertThat(claimed.breakdown().capsApplied()).anyMatch(c -> c.contains("amount claimed")).anyMatch(c -> c.contains("deductible"));

        ReserveResult belowDeductible = agent.reserve(damage("MINOR", "500"), coverage("COLLISION", "50000", "2500"), null, "AUTO", BLOCKED);
        assertThat(belowDeductible.recommendedReserve()).isEqualByComparingTo("0");
        assertThat(belowDeductible.breakdown().capsApplied()).anyMatch(c -> c.contains("Held at zero"));

        ReserveResult limited = agent.reserve(damage("SEVERE", "15000"), coverage("COLLISION", "5000", "0"), null, "AUTO", BLOCKED);
        assertThat(limited.recommendedReserve()).isEqualByComparingTo("5000");
        assertThat(limited.breakdown().capsApplied()).anyMatch(c -> c.contains("coverage limit"));
    }

    @Test
    @DisplayName("A missing row is never a silent zero: the AI's figure is kept and the row is reported missing")
    void missingRow() {
        ReserveResult r = agent.reserve(damage("CATASTROPHIC", "9000"), coverage("COLLISION", "50000", "1000"), null, "AUTO", BLOCKED);
        assertThat(r.breakdown().tableEntryMissing()).isTrue();
        assertThat(r.recommendedReserve()).isEqualByComparingTo("8000");
        assertThat(r.breakdown().baseReserve()).isNull();
        assertThat(new TableInitialReserveProvider().lookup("MARINE", "HULL", "MINOR")).isEmpty();
        assertThat(new TableInitialReserveProvider().lookup(null, "COLLISION", "MINOR")).isEmpty();

        ReserveResult uncovered = agent.reserve(damage("MODERATE", "9000"), coverage(null, null, null), null, "AUTO", BLOCKED);
        assertThat(uncovered.breakdown().tableEntryMissing()).isTrue();
    }

    @Test
    @DisplayName("The switch: AI_ESTIMATE keeps the old formula so the two can be compared")
    void oldMethodStays() {
        ReserveCalculatorAgent old = new ReserveCalculatorAgent(new TableInitialReserveProvider(), ReserveCalculatorAgent.AI_ESTIMATE, MAX);
        ReserveResult r = old.reserve(damage("SEVERE", "15000"), coverage("COLLISION", "50000", "1000"), null, "AUTO", BLOCKED);
        assertThat(r.recommendedReserve()).isEqualByComparingTo("14000");
        assertThat(r.breakdown().method()).isEqualTo("AI_ESTIMATE");
        assertThat(old.usesTable()).isFalse();
        assertThat(agent.usesTable()).isTrue();
    }

    @Test
    @DisplayName("A row provider with nothing for the claim is handled like a missing row")
    void emptyProvider() {
        ReserveCalculatorAgent none = new ReserveCalculatorAgent((l, c, s) -> Optional.empty(), ReserveCalculatorAgent.AVERAGE_TABLE, MAX);
        assertThat(none.reserve(damage("MINOR", "800"), coverage("COLLISION", "50000", "0"), null, "AUTO", BLOCKED)
                .breakdown().tableEntryMissing()).isTrue();
    }
}
