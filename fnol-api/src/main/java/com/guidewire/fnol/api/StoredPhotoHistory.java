package com.guidewire.fnol.api;

import com.guidewire.fnol.api.persistence.ClaimDocumentRepository;
import com.guidewire.fnol.api.persistence.ClaimDocumentRepository.PhotoFingerprint;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.orchestrator.PhotoHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares an intake's uploaded photos with every photo stored on earlier claims: the same SHA-256 (identical file)
 * or a perceptual hash within {@link PerceptualHash#MAX_DISTANCE} bits (the same picture resized or recompressed).
 * Prototype scale: it scans all stored fingerprints; a production system would index them.
 */
@Component
public class StoredPhotoHistory implements PhotoHistory {

    private static final Logger log = LoggerFactory.getLogger(StoredPhotoHistory.class);
    private static final Pattern DATA_URI = Pattern.compile("^data:image/[a-z]+;base64,([A-Za-z0-9+/=]+)$",
            Pattern.CASE_INSENSITIVE);

    private final ClaimDocumentRepository documents;

    public StoredPhotoHistory(ClaimDocumentRepository documents) {
        this.documents = documents;
    }

    @Override
    public List<String> claimsWithSamePhotos(FNOLPayload payload) {
        try {
            List<String[]> incoming = new ArrayList<>();   // {sha256, phash}
            for (String uri : payload.evidenceUris() == null ? List.<String>of() : payload.evidenceUris()) {
                Matcher m = uri == null ? null : DATA_URI.matcher(uri.trim());
                if (m == null || !m.matches()) continue;
                byte[] bytes = Base64.getDecoder().decode(m.group(1));
                incoming.add(new String[]{EvidenceStore.sha256(bytes), PerceptualHash.of(bytes)});
            }
            if (incoming.isEmpty()) {
                return List.of();
            }
            Set<String> matches = new LinkedHashSet<>();
            for (PhotoFingerprint stored : documents.findUploadedPhotoFingerprints()) {
                for (String[] photo : incoming) {
                    boolean same = photo[0].equals(stored.getSha256())
                            || (photo[1] != null && stored.getPhash() != null
                                && PerceptualHash.distance(photo[1], stored.getPhash()) <= PerceptualHash.MAX_DISTANCE);
                    if (same) {
                        matches.add(stored.getClaimId());
                    }
                }
            }
            return List.copyOf(matches);
        } catch (RuntimeException e) {
            log.warn("[Evidence] Photo reuse check failed — continuing without it: {}", e.getMessage());
            return List.of();
        }
    }
}
