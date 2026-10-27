package com.guidewire.fnol.api;

import com.guidewire.fnol.agents.AuditTrailAgent;
import com.guidewire.fnol.api.persistence.ClaimDocumentEntity;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.api.persistence.UploadRequestEntity;
import com.guidewire.fnol.api.persistence.UploadRequestRepository;
import com.guidewire.fnol.common.Models.ClaimUpdateRequest;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Supplementary photos: the adjuster asks the claimant for more photos (Human Review Point 3, "request
 * information"), the system issues a one-time link, and the claimant adds photos to the same claim.
 *
 * <p>Adjuster endpoints need the adjuster token. The claimant endpoints under /api/v1/public/upload are
 * open but need the link's token: 256 random bits, valid once, for {@link #LINK_VALIDITY}. Only its SHA-256
 * is stored. The public side accepts only uploaded image data (no URLs) and shows no personal data.
 */
@RestController
@RequestMapping("/api/v1")
class UploadRequestController {

    private static final Logger log = LoggerFactory.getLogger(UploadRequestController.class);

    static final Duration LINK_VALIDITY = Duration.ofDays(7);
    static final int MAX_PHOTOS = 6;
    static final Set<String> ANGLES = Set.of("FRONT", "REAR", "LEFT", "RIGHT", "CLOSE_UP", "ODOMETER_VIN",
            "OVERVIEW", "OTHER");
    /** Base64 of a 5 MB image plus the data: prefix, with room to spare. */
    private static final int MAX_URI_CHARS = 7_200_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    record CreateRequest(@NotBlank String requestedBy, @NotEmpty List<String> angles, @Size(max = 500) String message) {}

    record Created(String requestId, String uploadUrl, Instant expiresAt, List<String> angles) {}

    /** What the claimant's page shows: no personal data. */
    record PublicView(String claimId, List<String> angles, String message, Instant expiresAt, String status) {}

    record UploadBody(@NotEmpty List<String> evidenceUris, @NotEmpty List<String> evidenceAngles) {}

    private final ClaimRepository claims;
    private final UploadRequestRepository requests;
    private final EvidenceStore evidence;
    private final AuditService auditService;
    private final AuditTrailAgent auditAgent;
    private final ClaimCenterClient claimCenter;
    private final String portalBaseUrl;

    UploadRequestController(ClaimRepository claims, UploadRequestRepository requests, EvidenceStore evidence,
                            AuditService auditService, AuditTrailAgent auditAgent, ClaimCenterClient claimCenter,
                            @Value("${fnol.portal.base-url:http://localhost:5173}") String portalBaseUrl) {
        this.claims = claims;
        this.requests = requests;
        this.evidence = evidence;
        this.auditService = auditService;
        this.auditAgent = auditAgent;
        this.claimCenter = claimCenter;
        this.portalBaseUrl = portalBaseUrl.replaceAll("/+$", "");
    }

    // ── Adjuster side ────────────────────────────────────────────────────────

    /** Creates the link and moves the claim to NEEDS_MORE_INFO. The token is returned only here. */
    @PostMapping("/fnol/{claimId}/upload-requests")
    @Transactional
    ResponseEntity<?> create(@PathVariable("claimId") String claimId, @Valid @RequestBody CreateRequest body) {
        List<String> angles = normalise(body.angles());
        ClaimEntity claim = claims.findById(claimId).orElse(null);
        if (claim == null) {
            return ResponseEntity.notFound().build();
        }
        if (claimId.startsWith("REJ-")) {
            throw new InvalidStateException("Claim " + claimId + " was not filed; ask the claimant to submit a new FNOL");
        }
        Instant now = Instant.now();
        String previous = claim.status;
        if (claims.moveStatus(claimId, FNOLController.REVIEWABLE_STATUSES, "NEEDS_MORE_INFO", now) == 0) {
            throw new InvalidStateException("Claim " + claimId + " is in status " + previous
                    + "; photos can only be requested while the claim awaits a decision");
        }

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        UploadRequestEntity r = new UploadRequestEntity();
        r.requestId = UUID.randomUUID().toString();
        r.claimId = claimId;
        r.tokenSha256 = sha256(token);
        r.requestedAngles = String.join(",", angles);
        r.message = body.message() == null || body.message().isBlank() ? null : body.message().trim();
        r.requestedBy = body.requestedBy().trim();
        r.status = "OPEN";
        r.createdAt = now;
        r.expiresAt = now.plus(LINK_VALIDITY);
        requests.save(r);

        ClaimEntity updated = claims.findById(claimId).orElseThrow();
        updated.adjusterDecision = "NEEDS_MORE_INFO";
        updated.adjusterId = r.requestedBy;
        updated.adjusterNotes = "Requested photos: " + String.join(", ", angles) + (r.message != null ? ". " + r.message : "");
        updated.reviewedAt = now;
        claims.save(updated);

        audit(claimId, "PhotoRequest", "requestId=" + r.requestId + ";angles=" + r.requestedAngles
                        + ";messageSha256=" + AuditTrailAgent.sha256(Objects.toString(r.message, "")),
                "previousStatus=" + previous + ";status=NEEDS_MORE_INFO;expiresAt=" + r.expiresAt, "REQUESTED", r.requestedBy);
        syncClaimCenter(claimId, "PENDING_INFORMATION", "PHOTOS_REQUESTED", r.requestedBy,
                "Requested photos from the claimant: " + String.join(", ", angles));
        log.info("[FNOL] Photo request {} for {} ({}), link valid until {}", r.requestId, claimId, r.requestedAngles, r.expiresAt);
        return ResponseEntity.ok(new Created(r.requestId, portalBaseUrl + "/upload/" + token, r.expiresAt, angles));
    }

    @GetMapping("/fnol/{claimId}/upload-requests")
    ResponseEntity<?> list(@PathVariable("claimId") String claimId) {
        if (!claims.existsById(claimId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(requests.findByClaimIdOrderByCreatedAtAsc(claimId));
    }

    @PostMapping("/fnol/{claimId}/upload-requests/{requestId}/cancel")
    @Transactional
    ResponseEntity<?> cancel(@PathVariable("claimId") String claimId, @PathVariable("requestId") String requestId,
                             @RequestBody Map<String, String> body) {
        String adjusterId = Objects.toString(body.get("adjusterId"), "").trim();
        if (adjusterId.isEmpty()) {
            throw new IllegalArgumentException("adjusterId is required");
        }
        UploadRequestEntity r = requests.findById(requestId).filter(x -> x.claimId.equals(claimId)).orElse(null);
        if (r == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"OPEN".equals(r.status)) {
            throw new InvalidStateException("Photo request " + requestId + " is " + r.getEffectiveStatus());
        }
        r.status = "CANCELLED";
        requests.save(r);
        audit(claimId, "PhotoRequestCancelled", "requestId=" + requestId, "status=CANCELLED", "CANCELLED", adjusterId);
        return ResponseEntity.ok(r);
    }

    // ── Claimant side (link token, no adjuster token) ─────────────────────────

    @GetMapping("/public/upload/{token}")
    ResponseEntity<?> view(@PathVariable("token") String token) {
        UploadRequestEntity r = requests.findByTokenSha256(sha256(token)).orElse(null);
        if (r == null) {
            return ResponseEntity.notFound().build();
        }
        PublicView v = new PublicView(r.claimId, List.of(r.requestedAngles.split(",")), r.message, r.expiresAt,
                r.getEffectiveStatus());
        return "OPEN".equals(v.status()) ? ResponseEntity.ok(v) : ResponseEntity.status(410).body(v);
    }

    /** Stores the photos on the claim (audited, with angles), closes the link and returns the claim for review. */
    @PostMapping("/public/upload/{token}")
    @Transactional
    ResponseEntity<?> upload(@PathVariable("token") String token, @Valid @RequestBody UploadBody body) {
        UploadRequestEntity r = requests.findByTokenSha256(sha256(token)).orElse(null);
        if (r == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"OPEN".equals(r.getEffectiveStatus())) {
            return ResponseEntity.status(410).body(Map.of("error", "LINK_NOT_ACTIVE",
                    "message", "This upload link is " + r.getEffectiveStatus().toLowerCase(Locale.ROOT)
                            + ". Contact your claim handler for a new one."));
        }
        List<String> uris = body.evidenceUris();
        List<String> angles = normalise(body.evidenceAngles());
        if (uris.size() > MAX_PHOTOS || uris.size() != angles.size()) {
            throw new IllegalArgumentException("Send 1 to " + MAX_PHOTOS + " photos, each with its angle");
        }
        for (String uri : uris) {
            if (uri == null || !uri.regionMatches(true, 0, "data:image/", 0, 11) || uri.length() > MAX_URI_CHARS) {
                throw new IllegalArgumentException("Only uploaded JPEG, PNG, WebP or GIF photos up to 5 MB are accepted");
            }
        }
        ClaimEntity claim = claims.findById(r.claimId).orElseThrow();
        if (!FNOLController.REVIEWABLE_STATUSES.contains(claim.status)) {
            return ResponseEntity.status(410).body(Map.of("error", "CLAIM_CLOSED",
                    "message", "This claim has already been decided. Contact your claim handler."));
        }

        List<ClaimDocumentEntity> stored = evidence.store(r.claimId, uris, angles);
        if (stored.isEmpty()) {
            throw new IllegalArgumentException("None of the photos could be read; use JPEG, PNG, WebP or GIF up to 5 MB");
        }
        Instant now = Instant.now();
        r.status = "USED";
        r.usedAt = now;
        r.photosReceived = stored.size();
        requests.save(r);
        claims.moveStatus(r.claimId, Set.of("NEEDS_MORE_INFO"), "HUMAN_REVIEW", now);

        audit(r.claimId, "SupplementaryEvidence", "requestId=" + r.requestId + ";angles=" + String.join(",", angles),
                "photosStored=" + stored.size() + ";status=HUMAN_REVIEW", "RECEIVED", AuditService.SYSTEM_ACTOR);
        syncClaimCenter(r.claimId, "DRAFT_PENDING_REVIEW", "PHOTOS_RECEIVED", "CLAIMANT",
                stored.size() + " photo(s) received from the claimant: " + String.join(", ", angles));
        log.info("[FNOL] Photo request {} used: {} photo(s) added to {}", r.requestId, stored.size(), r.claimId);
        return ResponseEntity.ok(Map.of("claimId", r.claimId, "photosReceived", stored.size()));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<String> normalise(List<String> angles) {
        List<String> out = new ArrayList<>();
        for (String a : angles) {
            String v = Objects.toString(a, "").trim().toUpperCase(Locale.ROOT);
            if (!ANGLES.contains(v)) {
                throw new IllegalArgumentException("Unknown photo angle '" + a + "'; use one of " + new TreeSet<>(ANGLES));
            }
            out.add(v);
        }
        return out;
    }

    static String sha256(String token) {
        return EvidenceStore.sha256(token.getBytes(StandardCharsets.UTF_8));
    }

    private void audit(String claimId, String agent, String input, String output, String status, String actor) {
        auditService.append(claimId, List.of(auditAgent.record(claimId, agent, input, output, status)), actor);
    }

    /** ClaimCenter is told about each step; an outage is marked FAILED and never blocks the claimant. */
    private void syncClaimCenter(String claimId, String ccStatus, String action, String actor, String notes) {
        ClaimEntity claim = claims.findById(claimId).orElseThrow();
        try {
            claimCenter.updateClaim(claimId, new ClaimUpdateRequest(ccStatus, notes, action, actor, null));
            claim.ccSyncStatus = "SYNCED";
        } catch (RuntimeException e) {
            log.warn("[FNOL] ClaimCenter update failed for claimId={} ({}): {}", claimId, action, e.getMessage());
            claim.ccSyncStatus = "FAILED";
        }
        claims.save(claim);
    }
}
