package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.common.Models.DeadlineResult;
import com.guidewire.fnol.common.Models.FNOLPayload;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * The written acknowledgement of a claim that state regulations require within a few days of notice. Built from a
 * fixed template (not by AI) so its wording is predictable and reviewable. It confirms receipt, says what happens
 * next and by when, lists what is still needed, and cites the rule it satisfies. For a represented claimant it is
 * addressed to their attorney. Prototype: it is filed on the ClaimCenter claim, not posted or e-mailed.
 */
public final class AcknowledgementLetter {

    private AcknowledgementLetter() {
    }

    public static String write(String claimId, FNOLPayload payload, DeadlineResult deadline, List<String> missingAngles,
                               boolean represented, LocalDate today) {
        String name = payload.claimantName() == null || payload.claimantName().isBlank()
                ? "Policyholder" : payload.claimantName().trim();
        StringBuilder b = new StringBuilder();
        b.append(represented
                ? "To the attorney representing " + name + "\n\n"
                : "Dear " + name + ",\n\n");
        b.append("We have received ").append(represented ? "your client's" : "your")
                .append(" claim and have opened claim ").append(claimId).append(" under policy ")
                .append(payload.policyNumber()).append(" for the loss on ").append(payload.incidentDate())
                .append(". We received it on ").append(today).append(".\n\n");

        b.append("What happens next\n");
        b.append("A claims adjuster is reviewing the claim and the photos sent with it.");
        if (deadline.decisionBy() != null) {
            b.append(" We will tell you in writing whether the claim is accepted by ").append(deadline.decisionBy())
                    .append(", or explain why we need more time.");
        } else if (deadline.decisionNote() != null) {
            b.append(" ").append(deadline.decisionNote());
        }
        b.append("\n\n");

        if (missingAngles != null && !missingAngles.isEmpty()) {
            b.append("What we still need\n");
            b.append("Photos of: ").append(String.join(", ", missingAngles.stream()
                    .map(a -> a.toLowerCase(Locale.ROOT).replace('_', ' ')).toList()))
                    .append(". Your adjuster will send a secure link to upload them.\n\n");
        }

        b.append("Your reference: ").append(claimId).append(". Please quote it in any contact about the claim.\n");
        if (represented) {
            b.append("As you represent the claimant, we will direct all communication about this claim to you.\n");
        }
        if (deadline.acknowledgeCitation() != null) {
            b.append("\nThis acknowledgement is sent under ").append(deadline.acknowledgeCitation()).append(".\n");
        }
        b.append("\nClaims department");
        return b.toString();
    }
}
