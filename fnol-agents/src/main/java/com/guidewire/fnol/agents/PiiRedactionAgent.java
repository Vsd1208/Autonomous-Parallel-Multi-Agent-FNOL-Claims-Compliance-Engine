package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.PIIResult;
import com.guidewire.fnol.common.Models.PiiDetection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Finds and masks personal data in the claim description, then scans the result again. It reports what it found and how. It reports no
 * confidence, because rules have no calibrated one: {@code redactionConfidence} stays null, and the re-scan's result is what the guardrail
 * reads. A name not given on the claim is not found: rules cannot tell a stranger's name from any other word.
 */
@Component
public class PiiRedactionAgent implements FNOLAgent<FNOLPayload, PIIResult> {

    static final String PATTERN_DETECTOR = "PATTERN";
    static final String KNOWN_VALUE_DETECTOR = "KNOWN_VALUE";

    /** A pattern-based category and how it is masked, in the order applied. */
    record Rule(String category, Pattern pattern, String mask) {}

    static final List<Rule> RULES = List.of(
            new Rule("SSN", Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"), "***-**-****"),
            new Rule("GOVERNMENT_ID", Pattern.compile("(?<!\\d[ -])\\b\\d{4}[ -]\\d{4}[ -]\\d{4}\\b(?![ -]\\d)|\\b[A-Z]{5}\\d{4}[A-Z]\\b"), "[REDACTED_ID]"),
            new Rule("DOB", Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{4}\\b"), "**/**/****"),
            new Rule("MRN", Pattern.compile("\\bMRN-\\d+\\b", Pattern.CASE_INSENSITIVE), "[REDACTED_MRN]"),
            new Rule("EMAIL", Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+"), "[REDACTED_EMAIL]"),
            new Rule("FINANCIAL", Pattern.compile("(?i)\\b(?:account|acct|iban|routing|card)(?:\\s+(?:no|num|number))?[\\s.:#-]{0,4}[A-Z0-9][A-Z0-9 -]{5,}\\d\\b"), "[REDACTED_FINANCIAL]"),
            new Rule("FINANCIAL", Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b"), "[REDACTED_FINANCIAL]"),
            new Rule("PHONE", Pattern.compile("(?<![\\d$,.])(?:\\+?\\d{1,2}[ .-]?)?(?:\\(?\\d{3}\\)?[ .-]\\d{3}[ .-]\\d{4}|[6-9]\\d{4}[ -]?\\d{5})(?!\\d|[,.]\\d)"), "[REDACTED_PHONE]"),
            new Rule("POLICY_NUMBER", Pattern.compile("\\bPOL-[A-Z]+-\\d+\\b"), "[REDACTED_POLICY]"),
            new Rule("CLAIM_NUMBER", Pattern.compile("\\bCLM-\\d{4}-[A-Z0-9]+\\b"), "[REDACTED_CLAIM]"),
            new Rule("POSTAL_ADDRESS", Pattern.compile("\\b\\d{1,6}\\s+(?:[A-Z][a-z]+\\s+){1,4}(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Lane|Ln|Drive|Dr|Court|Ct|Way)\\b\\.?"), "[REDACTED_ADDRESS]"));

    @Override
    public PIIResult execute(FNOLPayload p) {
        String text = Objects.toString(p.description(), "");
        Map<String, Integer> found = new LinkedHashMap<>();
        Map<String, String> detector = new LinkedHashMap<>();

        // Exact values first: what the claim itself says is this person's.
        List<String[]> known = new ArrayList<>();
        known.add(new String[]{"FULL_NAME", p.claimantName(), "[REDACTED_NAME]"});
        known.add(new String[]{"FULL_NAME", surname(p.claimantName()), "[REDACTED_NAME]"});
        known.add(new String[]{"PHONE", p.claimantPhone(), "[REDACTED_PHONE]"});
        known.add(new String[]{"EMAIL", p.claimantEmail(), "[REDACTED_EMAIL]"});
        known.add(new String[]{"SSN", p.claimantSsn(), "***-**-****"});
        known.add(new String[]{"DOB", p.claimantDob(), "**/**/****"});
        List<String> values = new ArrayList<>();
        for (String[] k : known) {
            if (k[1] == null || k[1].isBlank()) {
                continue;
            }
            values.add(k[1]);
            Pattern exact = Pattern.compile("(?<![\\p{L}\\d])" + Pattern.quote(k[1].trim()) + "(?![\\p{L}\\d])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            int hits = (int) exact.matcher(text).results().count();
            if (hits > 0) {
                found.merge(k[0], hits, Integer::sum);
                detector.put(k[0], KNOWN_VALUE_DETECTOR);
                text = exact.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(k[2]));
            }
        }
        for (Rule rule : RULES) {
            int hits = (int) rule.pattern().matcher(text).results().count();
            if (hits > 0) {
                found.merge(rule.category(), hits, Integer::sum);
                detector.putIfAbsent(rule.category(), PATTERN_DETECTOR);
                text = rule.pattern().matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(rule.mask()));
            }
        }
        List<PiiDetection> detections = new ArrayList<>();
        found.forEach((category, count) -> detections.add(new PiiDetection(category, detector.get(category), count, null, true)));
        return new PIIResult(text, List.copyOf(found.keySet()), !found.isEmpty(), null, List.copyOf(detections),
                validate(text, values) ? PIIResult.VALIDATED : PIIResult.VALIDATION_FAILED);
    }

    /** The check after masking: no pattern matches any more, and no value known to be this person's is left. */
    static boolean validate(String text, List<String> knownValues) {
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                return false;
            }
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return knownValues.stream().noneMatch(v -> lower.contains(v.trim().toLowerCase(Locale.ROOT)));
    }

    /** The last word of a name, when long enough not to match ordinary words by chance. */
    private static String surname(String name) {
        if (name == null) {
            return null;
        }
        String[] parts = name.trim().split("\\s+");
        return parts.length > 1 && parts[parts.length - 1].length() >= 3 ? parts[parts.length - 1] : null;
    }
}
