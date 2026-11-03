package com.guidewire.fnol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.common.Models.ClockMilestone;
import com.guidewire.fnol.common.Models.DeadlineResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The claim clock for an Indian policy, from {@code claim-regulations-in.json}. The rules are data, as for the US states, but India's
 * steps differ, so they are milestones rather than "acknowledge" and "decide". The text read has no fixed acknowledgement period, so
 * none is given. Only with {@code fnol.claim-clock.india.enabled=true} (off by default). Prototype, not reviewed by a lawyer.
 */
@Component
@ConditionalOnProperty(name = "fnol.claim-clock.india.enabled", havingValue = "true")
public class IndianClaimClock {

    static final String RESOURCE = "claim-regulations-in.json";
    private static final int WARNING_WINDOW_DAYS = 3;

    private final JsonNode rules;
    private final Clock clock;

    public IndianClaimClock() {
        this(read(), Clock.systemDefaultZone());
    }

    IndianClaimClock(JsonNode rules, Clock clock) {
        this.rules = rules;
        this.clock = clock;
    }

    static JsonNode read() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
    }

    public DeadlineResult deadline(String state, LocalDate noticeDate) {
        List<ClockMilestone> milestones = new ArrayList<>();
        // Hours are counted in whole days: the claim's time of day is not tracked here.
        LocalDate allocated = noticeDate;
        LocalDate decision = null;
        String decisionCitation = null;
        String decisionNote = null;
        for (JsonNode m : rules.path("milestones")) {
            boolean fromReport = "CLAIM_REPORTED".equals(m.path("startsFrom").asText());
            LocalDate start = fromReport ? noticeDate : allocated;
            LocalDate due = "HOURS".equals(m.path("unit").asText())
                    ? start.plusDays((long) Math.ceil(m.path("amount").asInt() / 24.0))
                    : start.plusDays(m.path("amount").asInt());
            if ("SURVEYOR_ALLOCATION".equals(m.path("id").asText())) {
                allocated = due;
            }
            if ("DECISION".equals(m.path("id").asText())) {
                decision = due;
                decisionCitation = m.path("citation").asText();
                decisionNote = m.path("appliesWhen").asText();
            }
            milestones.add(new ClockMilestone(m.path("action").asText(), due, m.path("startsFrom").asText(),
                    m.path("amount").asInt() + " " + m.path("unit").asText().toLowerCase(java.util.Locale.ROOT), m.path("citation").asText(),
                    m.path("appliesWhen").asText(), m.path("verification").asText()));
        }
        String version = rules.path("version").asText();
        return new DeadlineResult(state, decision, "CITED_RULES " + version, rules.path("disclaimer").asText(), null, decision,
                urgency(decision), null, decisionCitation, decisionNote, version, null, null, List.copyOf(milestones));
    }

    private String urgency(LocalDate due) {
        LocalDate today = LocalDate.now(clock);
        if (today.isAfter(due)) return "CRITICAL";
        return !today.plusDays(WARNING_WINDOW_DAYS).isBefore(due) ? "WARNING" : "OK";
    }
}
