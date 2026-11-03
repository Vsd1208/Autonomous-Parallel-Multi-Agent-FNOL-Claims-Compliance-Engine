package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.guidewire.fnol.common.Models.ClockMilestone;
import com.guidewire.fnol.common.Models.DeadlineResult;
import com.guidewire.fnol.common.Models.FNOLPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** India's claim clock: milestones from the official text, as data, and only with its switch on. */
class IndianClaimClockTest {

    private static final LocalDate NOTICE = LocalDate.of(2026, 11, 3);
    private final IndianClaimClock clock = new IndianClaimClock(IndianClaimClock.read(),
            Clock.fixed(NOTICE.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

    @Test
    @DisplayName("24 hours to allocate a surveyor, 15 days for the report, and the decision's outer limit, each with its citation")
    void milestones() {
        DeadlineResult d = clock.deadline("MH", NOTICE);
        List<ClockMilestone> m = d.milestones();
        assertThat(m).hasSize(3);
        assertThat(m.get(0).dueBy()).isEqualTo(NOTICE.plusDays(1));
        assertThat(m.get(1).dueBy()).isEqualTo(NOTICE.plusDays(16));
        assertThat(m.get(2).dueBy()).isEqualTo(NOTICE.plusDays(16));
        assertThat(m).allSatisfy(x -> {
            assertThat(x.citation()).contains("IRDAI/PP&GR/CIR/MISC/117/9/2024");
            assertThat(x.verification()).isEqualTo("regulation text");
        });
        assertThat(d.decisionBy()).isEqualTo(NOTICE.plusDays(16));
        assertThat(d.urgencyFlag()).isEqualTo("OK");
    }

    @Test
    @DisplayName("The text read gives no fixed acknowledgement period or recovery limit, so none is invented")
    void nothingInvented() {
        DeadlineResult d = clock.deadline("MH", NOTICE);
        assertThat(d.acknowledgeBy()).isNull();
        assertThat(d.acknowledgeCitation()).isNull();
        assertThat(d.recoverBy()).isNull();
    }

    @Test
    @DisplayName("Every rule says where it came from: an official URL, an effective date and how it was verified")
    void rulesAreCited() {
        JsonNode rules = IndianClaimClock.read();
        assertThat(rules.path("source").path("url").asText()).startsWith("https://irdai.gov.in/");
        assertThat(rules.path("disclaimer").asText()).contains("Not reviewed by a lawyer");
        for (JsonNode m : rules.path("milestones")) {
            assertThat(m.path("effectiveDate").asText()).isEqualTo("2024-09-05");
            assertThat(m.path("startsFrom").asText()).isNotBlank();
            assertThat(m.path("verification").asText()).isEqualTo("regulation text");
        }
    }

    @Test
    @DisplayName("With the switch off, an Indian policy gets no dates and a note to set them by hand, as any state with no rule does")
    void switchOff() {
        FNOLPayload claim = new FNOLPayload("POL-AUTO-IN0001", NOTICE.minusDays(2), "MH", "loss", null, null, null, List.of(), "WEB");
        DeadlineResult off = new StatutoryDeadlineAgent(new CitedDeadlineRuleProvider()).execute(claim, "AUTO", true, NOTICE, "IN");
        assertThat(off.milestones()).isEmpty();
        assertThat(off.acknowledgeBy()).isNull();
        assertThat(off.urgencyFlag()).isEqualTo(CitedDeadlineRuleProvider.NO_RULE);

        StatutoryDeadlineAgent on = new StatutoryDeadlineAgent(new CitedDeadlineRuleProvider());
        on.setIndia(clock);
        assertThat(on.execute(claim, "AUTO", true, NOTICE, "IN").milestones()).hasSize(3);
        // A US policy is untouched, whatever the switch.
        FNOLPayload us = new FNOLPayload("POL-AUTO-112233", NOTICE.minusDays(2), "CA", "loss", null, null, null, List.of(), "WEB");
        assertThat(on.execute(us, "AUTO", true, NOTICE, "US").acknowledgeBy()).isEqualTo(NOTICE.plusDays(15));
        assertThat(on.execute(us, "AUTO", true, NOTICE, null).acknowledgeBy()).isEqualTo(NOTICE.plusDays(15));
    }
}
