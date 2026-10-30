package com.guidewire.fnol.api;

import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.api.persistence.UploadRequestEntity;
import com.guidewire.fnol.api.persistence.UploadRequestRepository;
import com.guidewire.fnol.common.Models.ClaimAttachment;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.common.Models.FNOLResponse;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.orchestrator.FNOLOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The claimant's FNOL portal API, separate from the adjuster workspace. A policyholder reports a loss and later
 * tracks it; they never see the AI analysis, reserves, flags, fraud or subrogation scores, or adjuster notes, which
 * stay behind the adjuster token. Tracking needs the claim number and the policy number; any mismatch answers 404
 * so the portal does not reveal which claim numbers exist.
 *
 * <p>Prototype: the policy number stands in for a policyholder login (production: the insurer's customer identity
 * provider, with the claim tied to the signed-in policyholder).
 */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalController {

    private static final Logger log = LoggerFactory.getLogger(PortalController.class);
    static final String CHANNEL = "WEB";

    /** What the claimant sees. Nothing about how the AI or the adjuster assessed the claim. */
    public record PortalClaim(String claimId, String lineOfBusiness, LocalDate incidentDate, LocalDate reportedOn,
                              String stage, String stageTitle, String stageText, LocalDate acknowledgedOn,
                              LocalDate acknowledgeBy, LocalDate decisionBy, List<String> photosRequested,
                              String letter) {}

    private final FNOLController intake;
    private final ClaimRepository claims;
    private final UploadRequestRepository uploads;
    private final ClaimCenterClient claimCenter;

    public PortalController(FNOLController intake, ClaimRepository claims, UploadRequestRepository uploads,
                            ClaimCenterClient claimCenter) {
        this.intake = intake;
        this.claims = claims;
        this.uploads = uploads;
        this.claimCenter = claimCenter;
    }

    /**
     * Reports a loss: the same intake as the adjuster workspace (duplicate check, AI branches, ClaimCenter filing,
     * audit), recorded as a WEB report, but the answer is the claimant's receipt rather than the AI analysis.
     */
    @PostMapping("/claims")
    PortalClaim report(@Valid @RequestBody FNOLPayload p, HttpServletRequest request) {
        FNOLPayload web = new FNOLPayload(p.policyNumber(), p.incidentDate(), p.state(), p.description(),
                p.claimantName(), p.claimantDob(), p.claimantSsn(), p.evidenceUris(), CHANNEL, p.claimantPhone(),
                p.claimantEmail(), p.claimedAmount(), p.evidenceAngles());
        FNOLResponse response = intake.intake(web, request);
        ClaimEntity claim = claims.findById(response.claimId()).orElseThrow();
        return view(claim);
    }

    /** Claim status for the claimant: claim number and policy number must both match. */
    @GetMapping("/claims/{claimId}")
    ResponseEntity<?> track(@PathVariable("claimId") String claimId,
                            @RequestParam("policyNumber") String policyNumber) {
        return claims.findById(claimId.trim().toUpperCase(Locale.ROOT))
                .filter(c -> sameText(c.policyNumber, policyNumber))
                .<ResponseEntity<?>>map(c -> ResponseEntity.ok(view(c)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND",
                        "message", "No claim matches that claim number and policy number.")));
    }

    PortalClaim view(ClaimEntity c) {
        List<String> requested = uploads.findByClaimIdOrderByCreatedAtAsc(c.claimId).stream()
                .filter(u -> "OPEN".equals(u.getEffectiveStatus()))
                .flatMap(u -> Arrays.stream(u.requestedAngles.split(",")))
                .map(String::trim).filter(a -> !a.isEmpty()).distinct().toList();
        String[] stage = stage(c, !requested.isEmpty());
        String line = c.policyNumber != null && c.policyNumber.toUpperCase(Locale.ROOT).startsWith("POL-HOME")
                ? "HOME" : "AUTO";
        return new PortalClaim(c.claimId, line, c.incidentDate,
                c.createdAt == null ? null : LocalDate.ofInstant(c.createdAt, ZoneOffset.UTC),
                stage[0], stage[1], stage[2],
                c.acknowledgedAt == null ? null : LocalDate.ofInstant(c.acknowledgedAt, ZoneOffset.UTC),
                c.acknowledgeDue, c.decisionDue, requested, letter(c.claimId));
    }

    /** Stage code, title and plain-language text for a claim status. Neutral wording: no investigation language. */
    static String[] stage(ClaimEntity c, boolean photosRequested) {
        String status = c.status == null ? "" : c.status;
        if (c.claimId != null && c.claimId.startsWith("REJ-")) {
            return new String[]{"SPECIALIST", "A claims specialist will contact you",
                    "We received your report but could not open the claim automatically. A claims specialist will "
                            + "contact you to complete it. Please keep this reference."};
        }
        if (photosRequested || "NEEDS_MORE_INFO".equals(status)) {
            return new String[]{"ACTION_NEEDED", "We need something from you",
                    "Your adjuster has asked for more information. Use the secure link we sent you to upload the "
                            + "requested photos; your claim continues as soon as they arrive."};
        }
        return switch (status) {
            case "APPROVED_BY_ADJUSTER" -> new String[]{"ACCEPTED", "Your claim has been accepted",
                    "Your adjuster will contact you about the repair and payment."};
            case "REJECTED_BY_ADJUSTER" -> new String[]{"DECIDED", "A decision has been made",
                    "Your claim was not accepted. Your adjuster will send you a letter that explains the reasons and "
                            + "your options, including how to ask for the decision to be reviewed."};
            case "CLOSED" -> new String[]{"CLOSED", "Your claim is closed",
                    "This claim is closed. Contact us with your claim number if you have questions."};
            case "INVESTIGATION_REQUIRED" -> new String[]{"IN_REVIEW", "Your claim is being reviewed",
                    "Your claim needs a closer look, which may include an inspection of the damage. Your adjuster "
                            + "will contact you if anything else is needed."};
            default -> new String[]{"IN_REVIEW", "Your claim is being reviewed",
                    "A licensed claims adjuster is reviewing your claim and photos. We will contact you if we need "
                            + "anything else."};
        };
    }

    /** The acknowledgement letter filed on the ClaimCenter claim; null when ClaimCenter cannot be reached. */
    private String letter(String claimId) {
        try {
            List<ClaimAttachment> attachments = claimCenter.getClaim(claimId).attachments();
            if (attachments == null) return null;
            return attachments.stream()
                    .filter(a -> FNOLOrchestrator.ACKNOWLEDGEMENT_LETTER.equals(a.name()) && a.body() instanceof String)
                    .map(a -> (String) a.body()).findFirst().orElse(null);
        } catch (RuntimeException e) {
            log.warn("[Portal] Acknowledgement letter unavailable for claimId={}: {}", claimId, e.getMessage());
            return null;
        }
    }

    private static boolean sameText(String stored, String supplied) {
        if (stored == null || supplied == null) return false;
        return MessageDigest.isEqual(stored.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                supplied.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }
}
