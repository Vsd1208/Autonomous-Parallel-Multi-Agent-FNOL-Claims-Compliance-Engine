package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.DamageAssessment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.SubrogationResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyword subrogation score (0-100). Liability (third party, police report) is assessed in
 * Branch B; the damage-severity points are added at the reconciliation gate. Weights and
 * compliance notes are prototype values, not legal guidance.
 *
 * <p>Besides scoring, it picks out what a recovery team needs first: the other vehicle's plate, the other party's
 * insurer and a police report number, when the description gives them. These are third-party details kept on the
 * claim file for the recovery; they are not sent to any external model.
 */
@Component
public class DeterministicSubrogationScorer implements SubrogationScorer {

    static final int INVESTIGATE_THRESHOLD = 70;
    static final String PENDING_SEVERITY = "PENDING_SEVERITY";

    /** Phrases that say someone else caused the loss. */
    static final List<String> THIRD_PARTY_PHRASES = List.of(
            "other driver", "rear-ended", "rear ended", "hit me", "hit my", "hit us", "crashed into me", "crashed into my",
            "ran a red", "ran the red", "ran a stop", "ran the stop", "t-boned", "sideswiped", "another car",
            "another vehicle", "other vehicle", "other car", "at fault", "at-fault", "did not stop", "didn't stop",
            "backed into", "drove into my", "neighbour's tree", "neighbor's tree", "contractor", "the truck", "a truck hit",
            "a van hit", "lorry");

    /** Common US auto and property insurers, to spot the other party's insurer by name. */
    static final List<String> INSURERS = List.of(
            "state farm", "geico", "progressive", "allstate", "usaa", "liberty mutual", "farmers", "nationwide",
            "travelers", "american family", "erie", "the hartford", "hartford", "chubb", "auto-owners", "kemper",
            "mercury", "safeco", "esurance", "metlife");

    /** Names written as the insurers write them, where title case would be wrong. */
    static final Map<String, String> INSURER_NAMES = Map.of("geico", "GEICO", "usaa", "USAA", "metlife", "MetLife",
            "the hartford", "The Hartford", "auto-owners", "Auto-Owners");

    private static final Pattern PLATE = Pattern.compile(
            "(?:licen[cs]e plate|number plate|registration|plate)\\s*(?:no\\.?|number|#)?\\s*(?:is|was|:|-)?\\s*"
                    + "([A-Za-z0-9]{1,4}[ -]?[A-Za-z0-9]{2,5})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern POLICE_REPORT = Pattern.compile(
            "(?:police|incident|case)\\s+(?:report\\s+)?(?:no\\.?|number|#)\\s*(?:is|:|-)?\\s*([A-Za-z0-9-]{4,20})",
            Pattern.CASE_INSENSITIVE);

    @Override
    public SubrogationResult assessLiability(FNOLPayload payload) {
        String text = payload.description().toLowerCase(Locale.ROOT);
        boolean thirdParty = THIRD_PARTY_PHRASES.stream().anyMatch(text::contains);
        boolean policeReport = text.contains("police");
        Map<String, String> details = thirdPartyDetails(payload.description());

        Map<String, Integer> factors = new LinkedHashMap<>();
        factors.put("thirdPartyMention", thirdParty ? 35 : 10);
        factors.put("policeReportSignal", policeReport ? 20 : 5);

        List<String> notes = new ArrayList<>();
        if (thirdParty) {
            notes.add("Third-party involvement indicated: preserve evidence and identify the other party's insurer "
                    + "before any payment.");
            notes.add(policeReport
                    ? "Police report referenced: record the report number in the liability file."
                    : "No police report referenced: request one to support a liability claim.");
            if (!details.containsKey("otherInsurer")) {
                notes.add("The other party's insurer is not named: ask the claimant for it, or trace it from the plate.");
            }
        } else {
            notes.add("No third party identified in the description: subrogation recovery is unlikely.");
        }
        if (!details.isEmpty()) {
            notes.add("Third-party details found in the description: " + String.join(", ", details.keySet()) + ".");
        }

        int partial = factors.values().stream().mapToInt(Integer::intValue).sum();
        return new SubrogationResult(partial, PENDING_SEVERITY, factors, List.copyOf(notes), details);
    }

    /** The other vehicle's plate, the other party's insurer and the police report number, when stated. */
    static Map<String, String> thirdPartyDetails(String description) {
        Map<String, String> out = new LinkedHashMap<>();
        if (description == null) return out;
        Matcher plate = PLATE.matcher(description);
        if (plate.find()) out.put("otherVehiclePlate", plate.group(1).toUpperCase(Locale.ROOT));
        String lower = description.toLowerCase(Locale.ROOT);
        INSURERS.stream().filter(lower::contains).findFirst()
                .ifPresent(name -> out.put("otherInsurer", INSURER_NAMES.getOrDefault(name, titleCase(name))));
        Matcher report = POLICE_REPORT.matcher(description);
        if (report.find()) out.put("policeReportNumber", report.group(1).toUpperCase(Locale.ROOT));
        return out;
    }

    private static String titleCase(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.split(" ")) {
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    @Override
    public SubrogationResult applySeverity(SubrogationResult liability, DamageAssessment damage) {
        Map<String, Integer> factors = new LinkedHashMap<>(liability.factors());
        factors.put("damageSeverity", severityPoints(damage.severity()));

        int score = Math.min(100, factors.values().stream().mapToInt(Integer::intValue).sum());
        boolean investigate = score >= INVESTIGATE_THRESHOLD;

        List<String> notes = new ArrayList<>(liability.complianceNotes());
        notes.add(investigate
                ? "Score " + score + " >= " + INVESTIGATE_THRESHOLD + ": refer to the subrogation unit and start the "
                        + "recovery before the limitation deadline (see the recovery deadline on the claim)."
                : "Score " + score + " < " + INVESTIGATE_THRESHOLD + ": monitor; no subrogation referral recommended.");

        return new SubrogationResult(score, investigate ? "INVESTIGATE" : "MONITOR", factors, List.copyOf(notes),
                liability.thirdParty() == null ? Map.of() : liability.thirdParty());
    }

    /** MINOR 5, MODERATE 15, SEVERE 25; anything else is treated as MODERATE. */
    static int severityPoints(String severity) {
        if (severity == null) return 15;
        return switch (severity) {
            case "MINOR" -> 5;
            case "SEVERE" -> 25;
            default -> 15;
        };
    }
}
