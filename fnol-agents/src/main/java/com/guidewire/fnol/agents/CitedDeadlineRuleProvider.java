package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.AppliedRule;
import com.guidewire.fnol.common.Models.DeadlineResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Regulatory claim deadlines from a cited rule set ({@code claim-regulations.json}): each rule carries its citation,
 * source, day type (calendar or business days), the event that starts the clock, and how well it was verified.
 *
 * <ul>
 *   <li>Acknowledge: counted from notice of the claim (the day the insurer receives it), not from the accident.</li>
 *   <li>Decide: counted from notice or from a complete proof of loss, as the rule says. When the proof of loss is not
 *       complete yet (photos missing), the decision clock has not started: it starts when the requested items arrive.</li>
 *   <li>Recover by: the limitation period for a recovery action against an at-fault party, from the loss date.</li>
 * </ul>
 * States without a loaded rule get no dates and a note to set them manually; a state whose rule has no fixed decision
 * period (e.g. Illinois: "a reasonable time") says so instead of inventing one.
 *
 * <p>Each rule is versioned: the version in force on the notice date (the loss date for recovery) applies, and with none in force no
 * date is given. Public holidays count only where the rule names a calendar ({@code regulatory-holidays.json}) that covers the years
 * involved; otherwise weekends only, which gives an earlier date, and the note says so.
 */
@Component
public class CitedDeadlineRuleProvider implements DeadlineRuleProvider {

    static final String RESOURCE = "claim-regulations.json";
    static final String HOLIDAYS_RESOURCE = "regulatory-holidays.json";
    static final int WARNING_WINDOW_DAYS = 3;
    /** Urgency when no cited rule exists for the state. */
    public static final String NO_RULE = "NO_RULE";

    private final JsonNode rules;
    private final JsonNode holidays;
    private final Clock clock;
    private final int warningWindowDays;

    @Autowired
    public CitedDeadlineRuleProvider() {
        this(read(), Clock.systemDefaultZone());
    }

    public CitedDeadlineRuleProvider(JsonNode rules, Clock clock) {
        this(rules, readResource(HOLIDAYS_RESOURCE), clock);
    }

    public CitedDeadlineRuleProvider(JsonNode rules, JsonNode holidays, Clock clock) {
        this.rules = rules;
        this.holidays = holidays;
        this.clock = clock;
        this.warningWindowDays = rules.path("internalTargets").path("warningWindowDays").path("value").asInt(WARNING_WINDOW_DAYS);
    }

    public static JsonNode read() {
        return readResource(RESOURCE);
    }

    private static JsonNode readResource(String name) {
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + name, e);
        }
    }

    /** The version of a rule in force on a date: the rule itself or one of its previous versions; a missing node when none is. */
    static JsonNode inForce(JsonNode rule, LocalDate on) {
        if (rule.isMissingNode() || on == null || covers(rule, on)) {
            return rule;
        }
        for (JsonNode older : rule.path("previousVersions")) {
            if (covers(older, on)) {
                return older;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static boolean covers(JsonNode rule, LocalDate on) {
        LocalDate from = date(rule, "effectiveFrom");
        LocalDate to = date(rule, "effectiveTo");
        return (from == null || !on.isBefore(from)) && (to == null || !on.isAfter(to));
    }

    private static LocalDate date(JsonNode rule, String field) {
        return rule.hasNonNull(field) ? LocalDate.parse(rule.path(field).asText()) : null;
    }

    private static AppliedRule applied(JsonNode rule) {
        return new AppliedRule(rule.path("ruleId").asText(null), rule.path("ruleVersion").asInt(), rule.path("category").asText(null),
                rule.path("verificationStatus").asText(null), date(rule, "effectiveFrom"), date(rule, "effectiveTo"),
                rule.path("citation").asText(null), rule.path("source").asText(null));
    }

    /** A rule's period from a start date, using its holiday calendar if that covers the years involved. Notes a calendar it could not use. */
    private LocalDate due(LocalDate start, JsonNode rule, List<String> notes) {
        int days = rule.path("days").asInt();
        String dayType = rule.path("dayType").asText();
        String calendarName = rule.path("holidayCalendar").asText("");
        LocalDate withoutHolidays = add(start, days, dayType);
        if (calendarName.isEmpty()) {
            return withoutHolidays;
        }
        JsonNode calendar = holidays.path("calendars").path(calendarName);
        Set<Integer> years = new HashSet<>();
        calendar.path("years").forEach(y -> years.add(y.asInt()));
        Set<LocalDate> dates = new HashSet<>();
        calendar.path("dates").forEach(d -> dates.add(LocalDate.parse(d.asText())));
        LocalDate withHolidays = add(start, days, dayType, dates);
        if (years.contains(start.getYear()) && years.contains(withHolidays.getYear())) {
            return withHolidays;
        }
        notes.add("Public holidays for " + calendarName + " are not loaded for these dates, so " + rule.path("ruleId").asText()
                + " counts weekends only: the date may be earlier than the law requires.");
        return withoutHolidays;
    }

    @Override
    public DeadlineResult deadline(String state, LocalDate incidentDate) {
        LocalDate today = LocalDate.now(clock);
        return deadline(state, incidentDate, "AUTO", today, today);
    }

    @Override
    public DeadlineResult deadline(String state, LocalDate incidentDate, String lineOfBusiness, LocalDate noticeDate,
                                   LocalDate proofOfLossDate) {
        String version = rules.path("version").asText();
        JsonNode stateRules = rules.path("states").path(state == null ? "" : state.toUpperCase());
        if (stateRules.isMissingNode()) {
            return new DeadlineResult(state, null, "NO_CITED_RULE",
                    "No cited regulatory rule is loaded for " + state + ": set the acknowledgement and decision "
                            + "deadlines manually.", null, null, NO_RULE, null, null,
                    "No cited rule for this state.", version, null, null);
        }

        JsonNode ack = inForce(stateRules.path("acknowledge"), noticeDate);
        if (ack.isMissingNode()) {
            return new DeadlineResult(state, null, "NO_RULE_IN_FORCE " + version,
                    "No version of the rule for " + state + " is loaded for a notice on " + noticeDate + ": set the deadlines manually.",
                    null, null, NO_RULE, null, null, "No rule version in force on that date.", version, null, null);
        }
        List<String> calendarNotes = new ArrayList<>();
        List<AppliedRule> appliedRules = new ArrayList<>();
        appliedRules.add(applied(ack));
        LocalDate acknowledgeBy = due(noticeDate, ack, calendarNotes);

        JsonNode decide = inForce(stateRules.path("decide"), noticeDate);
        LocalDate decisionBy = null;
        String decisionNote;
        boolean appliesToLine = !decide.has("lines")
                || decide.path("lines").toString().contains("\"" + lineOfBusiness + "\"");
        if (decide.isMissingNode() || !appliesToLine) {
            decisionNote = "No fixed statutory decision period loaded for this line of business: decide with reasonable "
                    + "promptness.";
        } else if (decide.path("days").isNull() || !decide.has("days")) {
            decisionNote = decide.path("summary").asText();
        } else {
            boolean fromProof = "PROOF_OF_LOSS".equals(decide.path("trigger").asText());
            LocalDate start = fromProof ? proofOfLossDate : noticeDate;
            if (start == null) {
                decisionNote = "The decision clock starts when the proof of loss is complete (the requested items arrive): "
                        + decide.path("days").asInt() + " " + dayWord(decide) + " after that.";
            } else {
                decisionBy = due(start, decide, calendarNotes);
                decisionNote = decide.path("days").asInt() + " " + dayWord(decide) + " from "
                        + (fromProof ? "the complete proof of loss" : "notice of the claim") + ".";
            }
        }

        if (!decide.isMissingNode() && appliesToLine) {
            appliedRules.add(applied(decide));
        }
        decisionNote = String.join(" ", decisionNote, String.join(" ", calendarNotes)).strip();

        JsonNode recovery = inForce(stateRules.path("recovery"), incidentDate);
        if (!recovery.isMissingNode()) {
            appliedRules.add(applied(recovery));
        }
        LocalDate recoverBy = recovery.has("years") && incidentDate != null
                ? incidentDate.plusYears(recovery.path("years").asInt()) : null;

        String disclaimer = ack.path("summary").asText()
                + (decide.isMissingNode() ? "" : " " + decide.path("summary").asText())
                + " Verification: " + ack.path("verification").asText() + " / "
                + decide.path("verification").asText("—") + ". " + rules.path("disclaimer").asText();
        return new DeadlineResult(state, acknowledgeBy, "CITED_RULES " + version, disclaimer, acknowledgeBy, decisionBy,
                urgency(acknowledgeBy), ack.path("citation").asText(null),
                decide.isMissingNode() || !appliesToLine ? null : decide.path("citation").asText(null), decisionNote,
                version, recoverBy, recovery.has("citation")
                        ? recovery.path("citation").asText() + " (" + recovery.path("verification").asText() + ")" : null,
                List.of(), List.copyOf(appliedRules));
    }

    private static String dayWord(JsonNode rule) {
        return "BUSINESS".equals(rule.path("dayType").asText()) ? "business days" : "calendar days";
    }

    /** Adds calendar days, or business days (Monday to Friday, public holidays not excluded). */
    static LocalDate add(LocalDate from, int days, String dayType) {
        return add(from, days, dayType, Set.of());
    }

    /** Adds calendar days, or business days skipping weekends and the given holidays. A period ending on a holiday moves past it. */
    static LocalDate add(LocalDate from, int days, String dayType, Set<LocalDate> holidays) {
        if (!"BUSINESS".equals(dayType)) {
            return from.plusDays(days);
        }
        LocalDate d = from;
        int added = 0;
        while (added < days) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d)) {
                added++;
            }
        }
        return d;
    }

    /** CRITICAL when past due, WARNING when due within three days, otherwise OK. */
    private String urgency(LocalDate dueBy) {
        LocalDate today = LocalDate.now(clock);
        if (today.isAfter(dueBy)) return "CRITICAL";
        if (!today.plusDays(warningWindowDays).isBefore(dueBy)) return "WARNING";
        return "OK";
    }
}
