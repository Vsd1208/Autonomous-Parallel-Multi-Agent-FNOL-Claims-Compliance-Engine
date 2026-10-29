package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
@Component
public class CitedDeadlineRuleProvider implements DeadlineRuleProvider {

    static final String RESOURCE = "claim-regulations.json";
    static final int WARNING_WINDOW_DAYS = 3;
    /** Urgency when no cited rule exists for the state. */
    public static final String NO_RULE = "NO_RULE";

    private final JsonNode rules;
    private final Clock clock;

    @Autowired
    public CitedDeadlineRuleProvider() {
        this(read(), Clock.systemDefaultZone());
    }

    public CitedDeadlineRuleProvider(JsonNode rules, Clock clock) {
        this.rules = rules;
        this.clock = clock;
    }

    public static JsonNode read() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
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

        JsonNode ack = stateRules.path("acknowledge");
        LocalDate acknowledgeBy = add(noticeDate, ack.path("days").asInt(), ack.path("dayType").asText());

        JsonNode decide = stateRules.path("decide");
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
                decisionBy = add(start, decide.path("days").asInt(), decide.path("dayType").asText());
                decisionNote = decide.path("days").asInt() + " " + dayWord(decide) + " from "
                        + (fromProof ? "the complete proof of loss" : "notice of the claim") + ".";
            }
        }

        JsonNode recovery = stateRules.path("recovery");
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
                        ? recovery.path("citation").asText() + " (" + recovery.path("verification").asText() + ")" : null);
    }

    private static String dayWord(JsonNode rule) {
        return "BUSINESS".equals(rule.path("dayType").asText()) ? "business days" : "calendar days";
    }

    /** Adds calendar days, or business days (Monday to Friday; public holidays are not excluded). */
    static LocalDate add(LocalDate from, int days, String dayType) {
        if (!"BUSINESS".equals(dayType)) {
            return from.plusDays(days);
        }
        LocalDate d = from;
        int added = 0;
        while (added < days) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return d;
    }

    /** CRITICAL when past due, WARNING when due within three days, otherwise OK. */
    private String urgency(LocalDate dueBy) {
        LocalDate today = LocalDate.now(clock);
        if (today.isAfter(dueBy)) return "CRITICAL";
        if (!today.plusDays(WARNING_WINDOW_DAYS).isBefore(dueBy)) return "WARNING";
        return "OK";
    }
}
