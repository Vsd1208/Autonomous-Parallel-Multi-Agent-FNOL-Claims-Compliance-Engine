
package com.guidewire.fnol.agents;

import com.guidewire.fnol.common.Models.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.*;
import java.math.BigDecimal;

@Component
public class PiiRedactionAgent implements FNOLAgent<FNOLPayload, PIIResult> {

    private static final Pattern SSN =
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

    private static final Pattern DOB =
            Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{4}\\b");

    private static final Pattern MRN =
            Pattern.compile("\\bMRN-\\d+\\b", Pattern.CASE_INSENSITIVE);

    public PIIResult execute(FNOLPayload p) {
        String text = Objects.toString(p.description(), "");
        List<String> types = new ArrayList<>();

        if (SSN.matcher(text).find()) {
            types.add("SSN");
            text = SSN.matcher(text).replaceAll("***-**-****");
        }

        if (DOB.matcher(text).find()) {
            types.add("DOB");
            text = DOB.matcher(text).replaceAll("**/**/****");
        }

        if (MRN.matcher(text).find()) {
            types.add("MRN");
            text = MRN.matcher(text).replaceAll("[REDACTED_MRN]");
        }

        if (p.claimantName() != null && !p.claimantName().isBlank() && text.contains(p.claimantName())) {
            types.add("FULL_NAME");
            text = text.replace(p.claimantName(), "[REDACTED_NAME]");
        }

        BigDecimal confidence = new BigDecimal("0.95");

        return new PIIResult(
                text,
                types,
                !types.isEmpty(),
                confidence
        );
    }
}