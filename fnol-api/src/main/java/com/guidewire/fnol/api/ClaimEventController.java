package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.common.ComplianceBlockException;
import com.guidewire.fnol.common.CoverageNotEligibleException;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.common.ProcessingTimeoutException;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.orchestrator.FNOLOrchestrator;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.ResourceAccessException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

/**
 * The AI engine as an extension of the claims system. The claims system owns the claim: when an FNOL is submitted
 * there it publishes {@code claim.submitted}; this endpoint receives it, reads the FNOL from the claim API, runs the
 * same analysis as a direct intake and writes the results back onto that claim (note, activities, exposure with the
 * recommended reserve, documents, routing). The engine keeps its own analysis and audit record under the same claim
 * number, and stores no raw personal data.
 *
 * <p>Events are authenticated by an HMAC-SHA256 signature over the body with a shared secret (no adjuster token),
 * written to the durable inbox before the 202, and processed by {@code EventInboxWorker}. Delivery is at-least-once: a repeat of an event
 * already accepted is acknowledged and ignored, and the same id with different content is refused. Nothing is dropped: if the analysis
 * fails, the claim gets an activity for an adjuster and stays in review, and the failure is recorded and audited.
 */
@RestController
@RequestMapping("/api/v1/events")
class ClaimEventController {

    private static final Logger log = LoggerFactory.getLogger(ClaimEventController.class);
    static final String SIGNATURE_HEADER = "X-Claims-Signature";
    static final String EVENT_ID_HEADER = "X-Claims-Event-Id";
    static final String CLAIM_SUBMITTED = "claim.submitted";
    static final String CLAIM_DECIDED = "claim.decided";

    private final byte[] secret;
    private final ClaimCenterClient claimCenter;
    private final FNOLOrchestrator orchestrator;
    private final ClaimRepository claims;
    private final AuditService auditService;
    private final FailedIntakeRecorder failedIntakes;
    private final DuplicateSubmissionRecorder duplicates;
    private final EvidenceStore evidence;
    private final IdempotencyStore idempotency;
    private final ClaimsSystemIntake claimsSystem;
    private final ClaimDecisionRecorder decisions;
    private final ClaimAssignmentService assignment;
    private final EventInboxService inbox;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    ClaimEventController(@Value("${fnol.claims-events.secret:}") String secret, ClaimCenterClient claimCenter,
                         FNOLOrchestrator orchestrator, ClaimRepository claims, AuditService auditService,
                         FailedIntakeRecorder failedIntakes, DuplicateSubmissionRecorder duplicates,
                         EvidenceStore evidence, IdempotencyStore idempotency, ClaimsSystemIntake claimsSystem,
                         ClaimDecisionRecorder decisions, ClaimAssignmentService assignment, EventInboxService inbox) {
        this.inbox = inbox;
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.claimCenter = claimCenter;
        this.orchestrator = orchestrator;
        this.claims = claims;
        this.auditService = auditService;
        this.failedIntakes = failedIntakes;
        this.duplicates = duplicates;
        this.evidence = evidence;
        this.idempotency = idempotency;
        this.claimsSystem = claimsSystem;
        this.decisions = decisions;
        this.assignment = assignment;
    }

    @PostMapping("/claimcenter")
    ResponseEntity<Map<String, String>> receive(@RequestBody String body,
                                                @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
        if (secret.length == 0) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "EVENTS_NOT_CONFIGURED", "message", "No event signing secret is configured"));
        }
        if (!validSignature(body, signature)) {
            log.warn("[Events] Refused an event with a missing or wrong signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "BAD_SIGNATURE", "message", "The event signature is missing or wrong"));
        }
        ClaimEvent event;
        try {
            event = mapper.readValue(body, ClaimEvent.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "BAD_EVENT", "message", "Not a claim event"));
        }
        if (CLAIM_DECIDED.equals(event.type()) && event.subject() != null) {
            return decided(event);
        }
        if (!CLAIM_SUBMITTED.equals(event.type()) || event.subject() == null) {
            return ResponseEntity.accepted().body(Map.of("status", "ignored", "reason", "not a claim event we handle"));
        }
        if (event.id() == null || event.id().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "BAD_EVENT", "message", "An event needs an id"));
        }
        String claimId = event.subject();
        // The row commits before this returns: a 202 means the event survives a crash, and the worker takes it from here.
        return switch (inbox.accept(event, body)) {
            case NEW -> {
                log.info("[Events] claim.submitted {} durably accepted (event {})", claimId, event.id());
                yield ResponseEntity.accepted().body(Map.of("status", "accepted", "claimId", claimId));
            }
            case DUPLICATE -> ResponseEntity.accepted().body(Map.of("status", "duplicate", "claimId", claimId));
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "EVENT_CONFLICT",
                    "message", "This event id was already received with different content"));
        };
    }

    /**
     * A decision recorded in the claims system, whether through the workspace or directly: the analysis record, audit
     * trail and feedback loop follow. Answered with 503 if it cannot be applied yet (the analysis is still running, or
     * the database failed), so the claims system delivers it again; nothing is lost.
     */
    private ResponseEntity<Map<String, String>> decided(ClaimEvent event) {
        String claimId = event.subject();
        Map<String, Object> data = event.data() == null ? Map.of() : event.data();
        String decision = text(data, "decision");
        Instant decidedAt;
        try {
            decidedAt = Instant.parse(String.valueOf(data.get("decidedAt")));
        } catch (RuntimeException e) {
            log.warn("[Events] claim.decided {} without a decision time; ignored", claimId);
            return ResponseEntity.accepted().body(Map.of("status", "ignored", "reason", "no decision time"));
        }
        if (decision == null || !FNOLController.ADJUSTER_DECISIONS.contains(decision)) {
            return ResponseEntity.accepted().body(Map.of("status", "ignored", "reason", "not an adjuster decision"));
        }
        try {
            Optional<ClaimEntity> record = claims.findById(claimId);
            if (record.isPresent() && record.get().reviewedAt != null && !decidedAt.isAfter(record.get().reviewedAt)) {
                return ResponseEntity.accepted().body(Map.of("status", "duplicate", "claimId", claimId));
            }
            if (record.isEmpty()) {
                boolean analysing = inbox.activeFor(claimId);
                record = analysing ? Optional.empty() : claimsSystem.recordOf(claimId);
                if (record.isEmpty() || ClaimsSystemIntake.ANALYSIS_PENDING.equals(record.get().status)) {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "NOT_READY",
                            "message", "The claim's analysis has not finished; deliver this event again later"));
                }
                claimsSystem.saveForDecision(record.get());
            }
            String costSource = text(data, "repairCostSource");
            boolean applied = decisions.apply(new ClaimDecisionRecorder.Decision(claimId, decision,
                    Objects.requireNonNullElse(text(data, "adjusterId"), "UNKNOWN"), notesOf(claimId),
                    decimal(data, "adjustedReserve"), text(data, "overrideReason"), text(data, "confirmedDamageType"),
                    decimal(data, "finalRepairCost"),
                    // Anything unrecognised counts as no stated evidence, so it can never teach the cost model.
                    costSource != null && AdjusterReviewRequest.REPAIR_COST_SOURCES.contains(costSource) ? costSource : null,
                    decidedAt));
            log.info("[Events] claim.decided {} {}: {}", claimId, applied ? "applied" : "already recorded", decision);
            return ResponseEntity.accepted().body(Map.of("status", applied ? "applied" : "duplicate", "claimId", claimId));
        } catch (RuntimeException e) {
            log.error("[Events] claim.decided {} could not be applied; it will be delivered again", claimId, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "NOT_APPLIED", "message", "The decision could not be applied; retry later"));
        }
    }

    /** The event leaves out free text; the claim holds it. Best effort. */
    private String notesOf(String claimId) {
        try {
            return claimCenter.getClaim(claimId).notes();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? null : value.toString();
    }

    private static BigDecimal decimal(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? null : new BigDecimal(value.toString());
    }

    /** A failure worth trying again. The inbox backs off and retries, then dead-letters. */
    static class RetryLaterException extends RuntimeException {
        RetryLaterException(String message) {
            super(message);
        }
    }

    private static boolean transientFailure(RuntimeException e) {
        return e instanceof ProcessingTimeoutException || e instanceof ResourceAccessException
                || e instanceof org.springframework.web.client.HttpServerErrorException;
    }

    /**
     * Reads the FNOL from the claims system, analyses it and writes the results back onto that claim. Safe to run again: a claim already
     * analysed is left alone. A transient failure throws {@link RetryLaterException} to try again later, but a person hears of it on the first
     * failure: retrying must not leave a claim out of sight for minutes. The person can act at once, and a later success adds the analysis.
     */
    void analyse(String claimId, LocalDate noticeDate, com.guidewire.fnol.orchestrator.BranchCheckpoint checkpoint, boolean firstAttempt, boolean lastAttempt) {
        // Already analysed, by an attempt that died before saying so; or a person decided while it was retrying, which a retry must not
        // overwrite. A row that only records a failure may be analysed again.
        if (claims.findById(claimId).filter(c -> c.failureReason == null || c.adjusterDecision != null).isPresent()) {
            return;
        }
        FNOLPayload fnol;
        try {
            fnol = claimCenter.getFnol(claimId);
        } catch (RuntimeException e) {
            if (transientFailure(e) && !lastAttempt) {
                throw new RetryLaterException(String.valueOf(e.getMessage()));
            }
            log.error("[Events] FNOL for {} could not be read from the claims system; the event will not be retried "
                    + "by the engine: {}", claimId, e.getMessage());
            flagForAdjuster(claimId, "HUMAN_REVIEW", "AI analysis could not start: the loss report could not be read. "
                    + "Handle this claim manually.");
            if (transientFailure(e)) {
                throw new RetryLaterException(String.valueOf(e.getMessage()));   // attempts used up: dead letter, and a person has it
            }
            return;
        }
        String policy = fnol.policyNumber();
        LocalDate date = fnol.incidentDate();
        if (!idempotency.tryAcquire(policy, date, fnol.state(), claimId)) {
            Optional<String> original = duplicates.record(fnol);
            failedIntakes.record(claimId, fnol, "HUMAN_REVIEW", "DUPLICATE_FNOL",
                    "Possible duplicate of " + original.orElse("a claim still being processed"));
            flagForAdjuster(claimId, "HUMAN_REVIEW", "Possible duplicate: the same policy, loss date and state were already reported"
                    + original.map(id -> " as " + id).orElse("") + ". Verify before any action.");
            return;
        }
        FNOLResponse response;
        try {
            response = orchestrator.process(fnol, claimId, noticeDate, checkpoint);
        } catch (RuntimeException e) {
            idempotency.release(policy, date.toString(), fnol.state());
            String[] outcome = classify(e);
            if (transientFailure(e) && !lastAttempt) {
                if (firstAttempt) {
                    failedIntakes.record(claimId, fnol, outcome[0], outcome[1], outcome[2] + ". Retrying automatically");
                    flagForAdjuster(claimId, outcome[0], "AI analysis is delayed (" + outcome[1] + "): " + outcome[2]
                            + ". It will be retried automatically. You can handle this claim now; the analysis is added if a retry succeeds first.");
                }
                throw new RetryLaterException(String.valueOf(e.getMessage()));
            }
            failedIntakes.record(claimId, fnol, outcome[0], outcome[1], outcome[2]);
            flagForAdjuster(claimId, outcome[0], "AI analysis could not complete (" + outcome[1] + "): " + outcome[2]
                    + ". Handle this claim manually.");
            if (transientFailure(e)) {
                throw new RetryLaterException(String.valueOf(e.getMessage()));   // attempts used up: dead letter, and a person has it
            }
            return;
        }
        try {
            claims.save(ClaimEntity.from(response, fnol));
            auditService.append(claimId, response.branchB().audit(), AuditService.SYSTEM_ACTOR);
            evidence.store(claimId, fnol);
        } catch (RuntimeException e) {
            log.error("[Events] The analysis of {} finished but could not be saved", claimId, e);
            flagForAdjuster(claimId, "HUMAN_REVIEW", "The AI analysis finished but could not be saved. Handle this "
                    + "claim manually.");
            return;
        }
        assignment.assign(response);
        // Only now does the claim leave Awaiting AI Analysis: its record is complete. If this fails the claim stays
        // listed there, showing its real status, and can be opened and decided.
        try {
            claimCenter.patchClaim(claimId, new ClaimPatch(null, null, null, response.status()));
        } catch (RuntimeException e) {
            log.error("[Events] Claim {} analysed, but its work queue could not be set: {}", claimId, e.getMessage());
        }
        log.info("[Events] Claim {} analysed and written back: status={}, flags={}", claimId, response.status(),
                response.gateFlags());
    }

    /** Status, error code and a reason without personal data, as for a failed direct intake. */
    static String[] classify(RuntimeException e) {
        if (e instanceof ComplianceBlockException) {
            return new String[] {"BLOCKED_COMPLIANCE", "COMPLIANCE_BLOCK", e.getMessage()};
        }
        if (e instanceof CoverageNotEligibleException) {
            return new String[] {"HUMAN_REVIEW", "EXTERNAL_OR_PROCESSING_ERROR", e.getMessage()};
        }
        if (e instanceof ProcessingTimeoutException || e instanceof ResourceAccessException) {
            return new String[] {"HUMAN_REVIEW", "PROCESSING_TIMEOUT", e instanceof ProcessingTimeoutException
                    ? e.getMessage() : "Guidewire integration call timed out or was unreachable"};
        }
        if (e instanceof IllegalArgumentException) {
            return new String[] {"HUMAN_REVIEW", "VALIDATION_ERROR", "The loss report is incomplete or invalid"};
        }
        String reason = e instanceof IllegalStateException && e.getMessage() != null ? e.getMessage()
                : "Unexpected processing error (" + e.getClass().getSimpleName() + ")";
        return new String[] {"HUMAN_REVIEW", "EXTERNAL_OR_PROCESSING_ERROR", reason};
    }

    /** The claim stays in review with an activity telling the adjuster what happened. Best effort. */
    private void flagForAdjuster(String claimId, String workQueue, String what) {
        try {
            claimCenter.patchClaim(claimId, new ClaimPatch(null, null, null, workQueue));
            claimCenter.addActivity(claimId, new CcActivity(null, "Review needed: AI analysis did not complete", what,
                    "high", LocalDate.now().plusDays(1), null, null, null));
        } catch (RuntimeException e) {
            log.error("[Events] Could not flag claim {} in the claims system: {}", claimId, e.getMessage());
        }
    }

    private boolean validSignature(String body, String header) {
        if (header == null || !header.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)))
                    .getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, header.substring(7).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
