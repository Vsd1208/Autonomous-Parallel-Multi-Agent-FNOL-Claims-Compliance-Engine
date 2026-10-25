package com.guidewire.fnol.api;

import com.guidewire.fnol.agents.AuditTrailAgent;
import com.guidewire.fnol.api.persistence.ClaimDocumentEntity;
import com.guidewire.fnol.api.persistence.ClaimDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the evidence an intake was assessed with, so the adjuster sees the same photos the AI saw.
 *
 * <p>Uploaded photos ({@code data:image/(jpeg|png|webp|gif);base64,...}, at most 5 MB) are stored as
 * bytes; https and {@code mock://} evidence is stored as a reference only (nothing is downloaded).
 * Any other format, including SVG, is refused, so stored files can never carry script. Each stored
 * document's SHA-256 is written to the claim's audit chain, which proves later that the photo the
 * adjuster sees is the one that was submitted. Failures are logged and never fail the intake.
 */
@Component
public class EvidenceStore {

    private static final Logger log = LoggerFactory.getLogger(EvidenceStore.class);
    private static final Pattern DATA_URI = Pattern.compile(
            "^data:(image/(?:jpeg|png|webp|gif));base64,([A-Za-z0-9+/=]+)$", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> EXTENSION = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp", "image/gif", "gif");
    static final int MAX_DOCUMENTS = 8;

    private final ClaimDocumentRepository documents;
    private final AuditService auditService;
    private final AuditTrailAgent auditAgent;

    public EvidenceStore(ClaimDocumentRepository documents, AuditService auditService, AuditTrailAgent auditAgent) {
        this.documents = documents;
        this.auditService = auditService;
        this.auditAgent = auditAgent;
    }

    /** Stores the payload's evidence with each photo's angle. */
    public List<ClaimDocumentEntity> store(String claimId, com.guidewire.fnol.common.Models.FNOLPayload payload) {
        return store(claimId, payload.evidenceUris(), payload.evidenceAngles());
    }

    /** Stores up to eight evidence items for the claim and audits each one. Never throws. */
    public List<ClaimDocumentEntity> store(String claimId, List<String> evidenceUris) {
        return store(claimId, evidenceUris, null);
    }

    public List<ClaimDocumentEntity> store(String claimId, List<String> evidenceUris, List<String> angles) {
        List<ClaimDocumentEntity> stored = new ArrayList<>();
        if (evidenceUris == null) {
            return stored;
        }
        int index = 0;
        for (int position = 0; position < evidenceUris.size(); position++) {
            String uri = evidenceUris.get(position);
            if (uri == null || uri.isBlank()) continue;
            if (stored.size() >= MAX_DOCUMENTS) {
                log.warn("[Evidence] claimId={} has more than {} evidence items; the rest are not stored", claimId, MAX_DOCUMENTS);
                break;
            }
            index++;
            try {
                ClaimDocumentEntity doc = toDocument(claimId, uri.trim(), index);
                if (doc == null) continue;
                doc.angle = angleAt(angles, position);
                if ("UPLOAD".equals(doc.source) && !"OTHER".equals(doc.angle)) {
                    doc.name = doc.angle.toLowerCase(Locale.ROOT).replace('_', '-') + "-" + doc.name;
                }
                documents.save(doc);
                auditService.append(claimId, List.of(auditAgent.record(claimId, "EvidenceStore",
                        "name=" + doc.name + ";angle=" + doc.angle + ";source=" + doc.source,
                        "sha256=" + doc.sha256 + ";bytes=" + doc.sizeBytes,
                        doc.isViewable() ? "STORED" : "REFERENCED")), AuditService.SYSTEM_ACTOR);
                stored.add(doc);
            } catch (RuntimeException e) {
                log.warn("[Evidence] Could not store evidence #{} for claimId={}: {}", index, claimId, e.getMessage());
            }
        }
        return stored;
    }

    private static String angleAt(List<String> angles, int position) {
        if (angles == null || position >= angles.size() || angles.get(position) == null || angles.get(position).isBlank()) {
            return "OTHER";
        }
        String a = angles.get(position).trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z_]", "");
        return a.isEmpty() ? "OTHER" : (a.length() > 16 ? a.substring(0, 16) : a);
    }

    private ClaimDocumentEntity toDocument(String claimId, String uri, int index) {
        ClaimDocumentEntity doc = new ClaimDocumentEntity();
        doc.documentId = UUID.randomUUID().toString();
        doc.claimId = claimId;
        doc.uploadedAt = Instant.now();

        if (uri.regionMatches(true, 0, "data:", 0, 5)) {
            Matcher m = DATA_URI.matcher(uri);
            if (!m.matches()) {
                log.warn("[Evidence] claimId={} evidence #{} is not a JPEG/PNG/WebP/GIF data URI; not stored", claimId, index);
                return null;
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(m.group(2));
            } catch (IllegalArgumentException e) {
                log.warn("[Evidence] claimId={} evidence #{} is not valid base64; not stored", claimId, index);
                return null;
            }
            if (bytes.length == 0 || bytes.length > ClaimDocumentEntity.MAX_BYTES) {
                log.warn("[Evidence] claimId={} evidence #{} is empty or larger than 5 MB; not stored", claimId, index);
                return null;
            }
            String mime = m.group(1).toLowerCase(Locale.ROOT);
            doc.source = "UPLOAD";
            doc.contentType = mime;
            doc.content = bytes;
            doc.sizeBytes = bytes.length;
            doc.sha256 = sha256(bytes);
            doc.name = "damage-photo-" + index + "." + EXTENSION.get(mime);
            return doc;
        }

        String lower = uri.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("mock://")) {
            doc.source = lower.startsWith("mock://") ? "MOCK" : "URL";
            doc.sourceUri = uri.length() > 2000 ? uri.substring(0, 2000) : uri;
            doc.name = nameFrom(uri, index);
            doc.sha256 = sha256(uri.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return doc;
        }
        log.warn("[Evidence] claimId={} evidence #{} has an unsupported scheme; not stored", claimId, index);
        return null;
    }

    private static String nameFrom(String uri, int index) {
        String path = uri.replaceAll("[?#].*$", "");
        String last = path.substring(path.lastIndexOf('/') + 1);
        last = last.replaceAll("[^A-Za-z0-9._-]", "_");
        return last.isBlank() ? "evidence-" + index : (last.length() > 200 ? last.substring(0, 200) : last);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
