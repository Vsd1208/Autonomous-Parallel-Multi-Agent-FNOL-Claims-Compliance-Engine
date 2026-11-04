package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.AppliedRule;
import com.guidewire.fnol.common.Models.DeadlineResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule versions, public holidays and the rule data's own completeness. */
class RegulatoryRuleVersionsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    private final CitedDeadlineRuleProvider rules = new CitedDeadlineRuleProvider(CitedDeadlineRuleProvider.read(),
            Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

    private static JsonNode resource(String name) throws Exception {
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            return new ObjectMapper().readTree(in);
        }
    }

    // ── Historical versions, at the boundary ──

    @Test
    @DisplayName("Florida, last day of the old rule: 14 days to acknowledge and 90 to decide")
    void floridaLastDayOfOldRule() {
        LocalDate notice = LocalDate.of(2023, 2, 28);
        DeadlineResult d = rules.deadline("FL", notice, "HOME", notice, notice);
        assertThat(d.acknowledgeBy()).isEqualTo(notice.plusDays(14));
        assertThat(d.decisionBy()).isEqualTo(notice.plusDays(90));
        assertThat(d.appliedRules()).filteredOn(r -> "FL-ACK".equals(r.ruleId())).singleElement().satisfies(r -> {
            assertThat(r.ruleVersion()).isEqualTo(1);
            assertThat(r.effectiveTo()).isEqualTo(notice);
            assertThat(r.verificationStatus()).isEqualTo("SECONDARY_SOURCE");
        });
    }

    @Test
    @DisplayName("Florida, first day of the new rule: 7 days to acknowledge and 60 to decide")
    void floridaFirstDayOfNewRule() {
        LocalDate notice = LocalDate.of(2023, 3, 1);
        DeadlineResult d = rules.deadline("FL", notice, "HOME", notice, notice);
        assertThat(d.acknowledgeBy()).isEqualTo(notice.plusDays(7));
        assertThat(d.decisionBy()).isEqualTo(notice.plusDays(60));
        assertThat(d.appliedRules()).filteredOn(r -> "FL-ACK".equals(r.ruleId())).singleElement()
                .satisfies(r -> assertThat(r.ruleVersion()).isEqualTo(2));
    }

    @Test
    @DisplayName("The claim's own date picks the version, not today's")
    void versionFollowsTheClaimNotToday() {
        LocalDate oldNotice = LocalDate.of(2022, 6, 1);
        assertThat(rules.deadline("FL", oldNotice, "HOME", oldNotice, oldNotice).acknowledgeBy()).isEqualTo(oldNotice.plusDays(14));
    }

    @Test
    @DisplayName("A loss before Florida's recovery rule began gets no recovery date, and none is invented")
    void recoveryRuleBoundary() {
        LocalDate before = LocalDate.of(2023, 3, 23);
        LocalDate first = LocalDate.of(2023, 3, 24);
        assertThat(rules.deadline("FL", before, "HOME", TODAY, TODAY).recoverBy()).isNull();
        assertThat(rules.deadline("FL", first, "HOME", TODAY, TODAY).recoverBy()).isEqualTo(first.plusYears(2));
    }

    @Test
    @DisplayName("With no version in force on the date there are no dates, and a person is told to set them")
    void noVersionInForce() throws Exception {
        JsonNode data = new ObjectMapper().readTree("""
                {"version":"t","states":{"ZZ":{"acknowledge":{"days":5,"dayType":"CALENDAR","effectiveFrom":"2025-01-01"}}}}""");
        CitedDeadlineRuleProvider p = new CitedDeadlineRuleProvider(data, Clock.systemUTC());
        DeadlineResult before = p.deadline("ZZ", null, "AUTO", LocalDate.of(2024, 12, 31), null);
        assertThat(before.acknowledgeBy()).isNull();
        assertThat(before.urgencyFlag()).isEqualTo(CitedDeadlineRuleProvider.NO_RULE);
        assertThat(before.disclaimer()).contains("set the deadlines manually");
        assertThat(p.deadline("ZZ", null, "AUTO", LocalDate.of(2025, 1, 1), null).acknowledgeBy()).isEqualTo(LocalDate.of(2025, 1, 6));
    }

    // ── Public holidays ──

    @Test
    @DisplayName("Business days skip a listed holiday; calendar days do not")
    void holidaysSkipped() {
        Set<LocalDate> veteransDay = Set.of(LocalDate.of(2026, 11, 11));
        assertThat(CitedDeadlineRuleProvider.add(LocalDate.of(2026, 11, 10), 1, "BUSINESS", veteransDay)).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(CitedDeadlineRuleProvider.add(LocalDate.of(2026, 11, 10), 1, "CALENDAR", veteransDay)).isEqualTo(LocalDate.of(2026, 11, 11));
    }

    @Test
    @DisplayName("Business days: Friday to Monday, and a start on a holiday counts from the next working day")
    void weekendAndHolidayBoundaries() {
        assertThat(CitedDeadlineRuleProvider.add(LocalDate.of(2026, 10, 30), 1, "BUSINESS", Set.of())).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(CitedDeadlineRuleProvider.add(LocalDate.of(2026, 11, 11), 1, "BUSINESS", Set.of(LocalDate.of(2026, 11, 11)))).isEqualTo(LocalDate.of(2026, 11, 12));
    }

    @Test
    @DisplayName("A period running past the calendar's last year counts weekends only, and says so: the safer, earlier date")
    void calendarNotCoveringTheYear() {
        LocalDate notice = LocalDate.of(2026, 12, 21);
        DeadlineResult d = rules.deadline("NY", notice, "AUTO", notice, notice);
        // 2027 is not loaded. Weekends only: 15 business days from Mon 21 Dec 2026 is Mon 11 Jan 2027.
        assertThat(d.acknowledgeBy()).isEqualTo(LocalDate.of(2027, 1, 11));
        assertThat(d.decisionNote()).contains("Public holidays for NY are not loaded").contains("weekends only");
    }

    @Test
    @DisplayName("Texas names a holiday calendar but no dates could be loaded: weekends only, and it says why")
    void texasBlockerIsVisible() throws Exception {
        LocalDate notice = LocalDate.of(2026, 11, 4);
        DeadlineResult d = rules.deadline("TX", notice, "AUTO", notice, notice);
        assertThat(d.decisionBy()).isEqualTo(CitedDeadlineRuleProvider.add(notice, 15, "BUSINESS"));
        assertThat(d.decisionNote()).contains("Public holidays for TX are not loaded");
        assertThat(resource(CitedDeadlineRuleProvider.HOLIDAYS_RESOURCE).path("calendars").path("TX").path("blocker").asText()).contains("connection refused");
    }

    @Test
    @DisplayName("A state whose text was not checked for holidays stays on weekends only, with no note about a calendar it does not use")
    void illinoisHasNoCalendar() {
        LocalDate notice = LocalDate.of(2026, 11, 4);
        DeadlineResult d = rules.deadline("IL", notice, "AUTO", notice, notice);
        assertThat(d.acknowledgeBy()).isEqualTo(CitedDeadlineRuleProvider.add(notice, 15, "BUSINESS"));
        assertThat(d.decisionNote()).doesNotContain("Public holidays");
    }

    @Test
    @DisplayName("Every date in a holiday calendar parses, sits in a listed year, and every calendar names its authority")
    void holidayDataIsSound() throws Exception {
        JsonNode calendars = resource(CitedDeadlineRuleProvider.HOLIDAYS_RESOURCE).path("calendars");
        calendars.fields().forEachRemaining(c -> {
            assertThat(c.getValue().path("authority").asText()).as(c.getKey()).isNotBlank();
            assertThat(c.getValue().path("source").asText()).as(c.getKey()).startsWith("https://");
            List<Integer> years = new ArrayList<>();
            c.getValue().path("years").forEach(y -> years.add(y.asInt()));
            c.getValue().path("dates").forEach(d -> assertThat(years).contains(LocalDate.parse(d.asText()).getYear()));
        });
        assertThat(calendars.path("NY").path("dates")).hasSize(13);
        assertThat(LocalDate.of(2026, 11, 3).getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
    }

    // ── What each date rests on ──

    @Test
    @DisplayName("A result names each rule behind its dates, with its category and how far it was verified")
    void appliedRulesAreReported() {
        DeadlineResult d = rules.deadline("CA", LocalDate.of(2026, 10, 1), "AUTO", TODAY, TODAY);
        assertThat(d.appliedRules()).extracting(AppliedRule::ruleId).containsExactly("CA-ACK", "CA-DECIDE", "CA-RECOVERY");
        assertThat(d.appliedRules()).extracting(AppliedRule::category).containsOnly("STATUTORY");
        assertThat(d.appliedRules()).filteredOn(r -> "CA-RECOVERY".equals(r.ruleId())).singleElement()
                .satisfies(r -> assertThat(r.verificationStatus()).isEqualTo("UNVERIFIED"));
    }

    @Test
    @DisplayName("A decision rule for another line of business is not named as applying")
    void otherLineNotReported() {
        DeadlineResult auto = rules.deadline("FL", LocalDate.of(2026, 10, 1), "AUTO", TODAY, TODAY);
        assertThat(auto.appliedRules()).extracting(AppliedRule::ruleId).doesNotContain("FL-DECIDE");
    }

    @Test
    @DisplayName("Every rule in the data is complete, and nothing claims a lawyer's review")
    void ruleDataIsComplete() throws Exception {
        JsonNode data = resource(CitedDeadlineRuleProvider.RESOURCE);
        assertThat(data.path("legalReview").asText()).isEqualTo("NOT_REVIEWED");
        Set<String> categories = Set.of("STATUTORY", "INSURER_POLICY", "INTERNAL_TARGET", "EXPERIMENTAL");
        Set<String> statuses = Set.of("PRIMARY_TEXT_READ", "SECONDARY_SOURCE", "UNVERIFIED");
        List<String> ids = new ArrayList<>();
        data.path("states").fields().forEachRemaining(state -> state.getValue().fields().forEachRemaining(kind -> {
            List<JsonNode> versions = new ArrayList<>(List.of(kind.getValue()));
            kind.getValue().path("previousVersions").forEach(versions::add);
            for (JsonNode rule : versions) {
                String id = rule.path("ruleId").asText();
                ids.add(id + "#" + rule.path("ruleVersion").asInt());
                assertThat(id).as(state.getKey() + "/" + kind.getKey()).startsWith(state.getKey() + "-");
                assertThat(rule.path("ruleVersion").asInt()).as(id).isPositive();
                assertThat(rule.path("citation").asText()).as(id).isNotBlank();
                assertThat(rule.path("requiredAction").asText()).as(id).isNotBlank();
                assertThat(rule.path("claimType").asText()).as(id).isNotBlank();
                assertThat(categories).as(id).contains(rule.path("category").asText());
                assertThat(statuses).as(id).contains(rule.path("verificationStatus").asText());
                assertThat(rule.has("effectiveFrom") && rule.has("effectiveTo")).as(id).isTrue();
                assertThat(rule.has("exceptions")).as(id).isTrue();
            }
        }));
        assertThat(ids).doesNotHaveDuplicates().hasSizeGreaterThanOrEqualTo(17);
    }

    @Test
    @DisplayName("A rule's versions do not overlap: one date, one version")
    void versionsDoNotOverlap() throws Exception {
        JsonNode fl = resource(CitedDeadlineRuleProvider.RESOURCE).path("states").path("FL").path("acknowledge");
        LocalDate end = LocalDate.parse(fl.path("previousVersions").get(0).path("effectiveTo").asText());
        assertThat(LocalDate.parse(fl.path("effectiveFrom").asText())).isEqualTo(end.plusDays(1));
    }

    @Test
    @DisplayName("India's clock is marked experimental and unreviewed")
    void indiaIsExperimental() throws Exception {
        JsonNode india = resource("claim-regulations-in.json");
        assertThat(india.path("legalReview").asText()).isEqualTo("NOT_REVIEWED");
        india.path("milestones").forEach(m -> assertThat(m.path("category").asText()).isEqualTo("EXPERIMENTAL"));
    }
}
