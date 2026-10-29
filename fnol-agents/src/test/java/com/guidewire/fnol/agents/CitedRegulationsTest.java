package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DeadlineResult;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.SubrogationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Regulatory deadlines from the cited rule set, and recovery details for subrogation. */
class CitedRegulationsTest {

    // Wednesday 2026-10-28
    private static final LocalDate NOTICE = LocalDate.of(2026, 10, 28);
    private static final LocalDate LOSS = LocalDate.of(2026, 10, 20);
    private final CitedDeadlineRuleProvider rules = new CitedDeadlineRuleProvider(CitedDeadlineRuleProvider.read(),
            Clock.fixed(NOTICE.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

    @Test
    @DisplayName("California: acknowledge 15 calendar days from notice, decide 40 days from proof of loss, cited")
    void california() {
        DeadlineResult d = rules.deadline("CA", LOSS, "AUTO", NOTICE, NOTICE);
        assertThat(d.acknowledgeBy()).isEqualTo(NOTICE.plusDays(15));
        assertThat(d.decisionBy()).isEqualTo(NOTICE.plusDays(40));
        assertThat(d.acknowledgeCitation()).isEqualTo("Cal. Code Regs. tit. 10, § 2695.5(e)");
        assertThat(d.decisionCitation()).isEqualTo("Cal. Code Regs. tit. 10, § 2695.7(b)");
        assertThat(d.rulesVersion()).isEqualTo("2026-10-29");
        assertThat(d.recoverBy()).isEqualTo(LOSS.plusYears(3));
        assertThat(d.recoveryCitation()).contains("§ 338(c)").contains("not verified");
    }

    @Test
    @DisplayName("The decision clock waits for a complete proof of loss; New York counts business days")
    void proofOfLossAndBusinessDays() {
        DeadlineResult waiting = rules.deadline("NY", LOSS, "AUTO", NOTICE, null);
        // 15 business days from Wed 28 Oct 2026 = Wed 18 Nov 2026 (weekends skipped)
        assertThat(waiting.acknowledgeBy()).isEqualTo(LocalDate.of(2026, 11, 18));
        assertThat(waiting.decisionBy()).isNull();
        assertThat(waiting.decisionNote()).contains("starts when the proof of loss is complete");

        DeadlineResult started = rules.deadline("NY", LOSS, "AUTO", NOTICE, LocalDate.of(2026, 11, 2));
        assertThat(started.decisionBy()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(CitedDeadlineRuleProvider.add(LocalDate.of(2026, 10, 30), 1, "BUSINESS")).isEqualTo(LocalDate.of(2026, 11, 2));
    }

    @Test
    @DisplayName("Florida's 60-day rule is for home claims only; Illinois has no fixed decision period; unknown states say so")
    void lineSpecificAndMissingRules() {
        assertThat(rules.deadline("FL", LOSS, "HOME", NOTICE, null).decisionBy()).isEqualTo(NOTICE.plusDays(60));
        DeadlineResult flAuto = rules.deadline("FL", LOSS, "AUTO", NOTICE, NOTICE);
        assertThat(flAuto.acknowledgeBy()).isEqualTo(NOTICE.plusDays(7));
        assertThat(flAuto.decisionBy()).isNull();
        assertThat(flAuto.decisionNote()).contains("No fixed statutory decision period");

        DeadlineResult il = rules.deadline("IL", LOSS, "AUTO", NOTICE, NOTICE);
        assertThat(il.decisionBy()).isNull();
        assertThat(il.decisionNote()).contains("reasonable time");

        DeadlineResult wa = rules.deadline("WA", LOSS, "AUTO", NOTICE, NOTICE);
        assertThat(wa.acknowledgeBy()).isNull();
        assertThat(wa.urgencyFlag()).isEqualTo(CitedDeadlineRuleProvider.NO_RULE);
        assertThat(wa.disclaimer()).contains("set the acknowledgement and decision deadlines manually");
    }

    @Test
    @DisplayName("Subrogation: wider third-party phrases and the other party's plate, insurer and police report number")
    void thirdPartyDetails() {
        DeterministicSubrogationScorer scorer = new DeterministicSubrogationScorer();
        FNOLPayload payload = new FNOLPayload("POL-AUTO-112233", LOSS, "CA",
                "A truck hit my car at the junction. The truck's licence plate is 7ABC123 and the driver is insured with "
                        + "State Farm. Police report number 2026-55123.", "Avery Johnson", null, null, List.of(), "WEB");
        SubrogationResult r = scorer.assessLiability(payload);
        assertThat(r.factors().get("thirdPartyMention")).isEqualTo(35);
        assertThat(r.thirdParty()).containsEntry("otherVehiclePlate", "7ABC123")
                .containsEntry("otherInsurer", "State Farm").containsEntry("policeReportNumber", "2026-55123");

        FNOLPayload alone = new FNOLPayload("POL-AUTO-112233", LOSS, "CA", "I scraped a pillar in a car park.",
                "Avery Johnson", null, null, List.of(), "WEB");
        assertThat(scorer.assessLiability(alone).thirdParty()).isEmpty();
        assertThat(DeterministicSubrogationScorer.thirdPartyDetails("Insured with GEICO.")).containsEntry("otherInsurer", "GEICO");
    }
}
