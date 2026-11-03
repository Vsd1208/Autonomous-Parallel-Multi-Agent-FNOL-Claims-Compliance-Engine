package com.guidewire.fnol.agents;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a draft deterministically against what the tools returned. A draft may not contain raw personal data, state a decision,
 * payment or liability finding, or give a date, amount or period the tools did not return. It errs towards rejecting, since the
 * cost of a false rejection is only the template text. Prototype patterns, English only.
 */
public final class DraftGuard {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE;
    private static final Map<String, Pattern> PERSONAL_DATA = Map.of(
            "a social security number", Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
            "an email address", Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+"),
            "a phone number", Pattern.compile("(?<!\\d)(\\+?1[ .-]?)?\\(?\\d{3}\\)?[ .-]\\d{3}[ .-]\\d{4}(?!\\d)"),
            "a date of birth", Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{4}\\b"));
    private static final Map<String, Pattern> STATEMENTS = Map.of(
            "a promise of payment", Pattern.compile("\\b(we|the insurer)( will| shall|'ll) (pay|reimburse|cover|settle)\\b"
                    + "|\\bwill be (paid|reimbursed|settled)\\b|\\byou will receive (a |your )?(payment|settlement|\\$)|\\bguarantee", FLAGS),
            "a coverage or claim decision", Pattern.compile("(?<!whether the )\\bclaim (is|has been|was|will be) (approved|accepted|denied|rejected|covered|paid)\\b"
                    + "|\\bcoverage (is|has been|was) (confirmed|approved|denied|rejected)\\b|\\b(is|are) (not )?covered\\b", FLAGS),
            "a liability finding", Pattern.compile("\\b(is|are|was|were) (at fault|liable|responsible for the (loss|accident))\\b|\\bat fault\\b", FLAGS));
    private static final Pattern ISO_DATE = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern WORD_DATE = Pattern.compile(
            "\\b(?:(\\d{1,2}) )?(January|February|March|April|May|June|July|August|September|October|November|December)(?: (\\d{1,2}))?,? (\\d{4})\\b");
    private static final Pattern MONEY = Pattern.compile("\\$\\s?([\\d,]+(?:\\.\\d+)?)");
    private static final Pattern PERIOD = Pattern.compile("\\b(\\d+)[ -](?:calendar |business |working )?(day|month|year|hour)s?\\b", FLAGS);

    private DraftGuard() {
    }

    /** Raw personal data only: what also applies to text a person edited. */
    public static List<String> personalData(String text) {
        List<String> problems = new ArrayList<>();
        PERSONAL_DATA.forEach((what, pattern) -> {
            if (pattern.matcher(text).find()) {
                problems.add("contains " + what);
            }
        });
        return problems;
    }

    /** Everything wrong with an LLM's draft, given the tool outputs it was entitled to use. Empty when it may be shown. */
    public static List<String> problems(String draft, Collection<String> toolOutputs) {
        List<String> problems = new ArrayList<>(personalData(draft));
        String sources = String.join("\n", toolOutputs);
        STATEMENTS.forEach((what, pattern) -> {
            if (pattern.matcher(draft).find()) {
                problems.add("states " + what);
            }
        });
        Matcher iso = ISO_DATE.matcher(draft);
        while (iso.find()) {
            if (!sources.contains(iso.group())) {
                problems.add("gives a date the tools did not return: " + iso.group());
            }
        }
        Matcher words = WORD_DATE.matcher(draft);
        while (words.find()) {
            String day = words.group(1) != null ? words.group(1) : words.group(3);
            String asIso = day == null ? null : toIso(day, words.group(2), words.group(4));
            if (asIso == null || !sources.contains(asIso)) {
                problems.add("gives a date the tools did not return: " + words.group());
            }
        }
        Matcher money = MONEY.matcher(draft);
        while (money.find()) {
            String digits = money.group(1).replace(",", "").replaceAll("\\.0+$", "");
            if (!Pattern.compile("(?<![\\d.])" + Pattern.quote(digits) + "(?!\\d)").matcher(sources.replace(",", "")).find()) {
                problems.add("gives an amount the tools did not return: " + money.group());
            }
        }
        Matcher period = PERIOD.matcher(draft);
        while (period.find()) {
            if (!Pattern.compile("\\b" + period.group(1) + "[ -](calendar |business |working )?" + period.group(2).toLowerCase(Locale.ROOT), FLAGS).matcher(sources).find()) {
                problems.add("gives a period the tools did not return: " + period.group());
            }
        }
        return problems;
    }

    private static String toIso(String day, String month, String year) {
        try {
            return LocalDate.parse(day + " " + month + " " + year, DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
