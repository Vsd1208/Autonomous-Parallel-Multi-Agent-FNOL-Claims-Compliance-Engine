package com.guidewire.fnol.api;

import com.guidewire.fnol.api.persistence.AuditEntity;
import com.guidewire.fnol.api.persistence.AuditRepository;
import com.guidewire.fnol.common.Models.AuditRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Appends audit events to a per-claim hash chain and verifies it.
 *
 * <p>{@code chainHash = SHA-256(previous chainHash + inputHash + outputHash)}, with an empty
 * previous hash for the first event. Changing any stored input or output hash, or deleting or
 * reordering an event, breaks every later link. The chain cannot detect a rewrite of the final
 * event together with its own chain hash, which is why the PDF report prints the head hash.
 */
@Service
public class AuditService {

    public static final String SYSTEM_ACTOR = "SYSTEM";

    private final AuditRepository audits;

    public AuditService(AuditRepository audits) {
        this.audits = audits;
    }

    /** Appends records in list order. Synchronized so concurrent appends cannot fork a claim's chain. */
    @Transactional
    public synchronized List<AuditEntity> append(String claimId, List<AuditRecord> records, String actor) {
        List<AuditEntity> existing = audits.findByClaimIdOrderBySeqAscTimestampAsc(claimId);
        String previous = existing.isEmpty() ? "" : Objects.toString(existing.get(existing.size() - 1).chainHash, "");
        int seq = existing.size();
        List<AuditEntity> saved = new java.util.ArrayList<>();
        for (AuditRecord record : records) {
            AuditEntity e = AuditEntity.from(record);
            e.seq = ++seq;
            e.actor = actor;
            e.chainHash = link(previous, e.inputHash, e.outputHash);
            previous = e.chainHash;
            saved.add(audits.save(e));
        }
        return saved;
    }

    public AuditTrail trail(String claimId) {
        List<AuditEntity> records = audits.findByClaimIdOrderBySeqAscTimestampAsc(claimId);
        return new AuditTrail(claimId, verify(records), records);
    }

    /** Recomputes the chain from the stored input/output hashes and compares every link. */
    static boolean verify(List<AuditEntity> ordered) {
        String previous = "";
        for (AuditEntity e : ordered) {
            String expected = link(previous, e.inputHash, e.outputHash);
            if (!expected.equals(e.chainHash)) {
                return false;
            }
            previous = expected;
        }
        return true;
    }

    static String link(String previous, String inputHash, String outputHash) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (previous + inputHash + outputHash).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record AuditTrail(String claimId, boolean chainValid, List<AuditEntity> records) {}
}
