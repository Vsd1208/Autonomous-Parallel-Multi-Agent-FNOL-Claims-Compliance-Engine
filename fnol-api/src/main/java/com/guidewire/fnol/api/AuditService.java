package com.guidewire.fnol.api;

import com.guidewire.fnol.api.persistence.AuditChainHeadEntity;
import com.guidewire.fnol.api.persistence.AuditChainHeadRepository;
import com.guidewire.fnol.api.persistence.AuditEntity;
import com.guidewire.fnol.api.persistence.AuditRepository;
import com.guidewire.fnol.api.security.Actor;
import com.guidewire.fnol.api.security.ActorType;
import com.guidewire.fnol.common.Models.AuditRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Appends audit events to a per-claim hash chain and verifies it.
 *
 * <p>{@code H(i) = SHA-256(length-prefixed H(i-1) || canonical event(i))}. The canonical event is a fixed, versioned list of fields, each
 * length-prefixed so no two different events can encode alike. It binds who, when, what, in which position and with what result, so editing
 * any of them breaks the chain. Rows written before version 2 keep verifying under their own rule ({@code SHA-256(previous + input + output)}).
 *
 * <p>A hash chain shows tampering. It does not make the database immutable: whoever can rewrite a whole chain and its head can forge it, unless
 * the head was also anchored somewhere they cannot reach. See docs/AUDIT_INTEGRITY.md.
 */
@Service
public class AuditService {

    /** The actor for the engine's own steps. */
    public static final String SYSTEM_ACTOR = "SYSTEM";
    static final String ALGORITHM_V2 = "FNOL-AUDIT-SHA256-V2";

    private final AuditRepository audits;
    private final AuditChainHeadRepository heads;

    public AuditService(AuditRepository audits, AuditChainHeadRepository heads) {
        this.audits = audits;
        this.heads = heads;
    }

    /** Appends in list order. The lock on the claim's head row serialises appends across instances, and a unique index backs it up. */
    @Transactional
    public List<AuditEntity> append(String claimId, List<AuditRecord> records, String actor) {
        heads.ensureExists(claimId);
        AuditChainHeadEntity head = heads.lockHead(claimId).orElseThrow();
        List<AuditEntity> existing = audits.findByClaimIdOrderBySeqAscTimestampAsc(claimId);
        String previous = existing.isEmpty() ? "" : Objects.toString(existing.get(existing.size() - 1).chainHash, "");
        int seq = existing.size();
        ActorMeta meta = meta(actor);
        List<AuditEntity> saved = new ArrayList<>();
        for (AuditRecord record : records) {
            AuditEntity e = AuditEntity.from(record);
            e.seq = ++seq;
            e.actor = actor;
            e.actorType = meta.type();
            e.actorRole = meta.role();
            // Truncated to what the database stores, so the same instant hashes the same after reading it back.
            e.timestamp = e.timestamp.truncatedTo(ChronoUnit.MICROS);
            e.hashAlgorithm = ALGORITHM_V2;
            e.previousHash = previous;
            e.chainHash = linkV2(previous, e);
            previous = e.chainHash;
            saved.add(audits.save(e));
        }
        head.lastSeq = seq;
        head.headHash = previous;
        heads.save(head);
        return saved;
    }

    private record ActorMeta(String type, String role) {}

    /** Taken from the authenticated caller. An actor who is not the caller was reported to us, by the claims system's signed event. */
    private static ActorMeta meta(String actor) {
        if (SYSTEM_ACTOR.equals(actor)) {
            return new ActorMeta(ActorType.SYSTEM_ACTOR.name(), null);
        }
        Actor caller = Actor.caller();
        return caller != null && caller.id().equals(actor) ? new ActorMeta(caller.type().name(), caller.role())
                : new ActorMeta(ActorType.SERVICE_ACTOR.name(), null);
    }

    public AuditTrail trail(String claimId) {
        List<AuditEntity> records = audits.findByClaimIdOrderBySeqAscTimestampAsc(claimId);
        List<String> problems = new ArrayList<>(verifyChain(records));
        Optional<AuditChainHeadEntity> head = heads.findById(claimId);
        if (head.isPresent() && !records.isEmpty() && (head.get().lastSeq != records.size()
                || !head.get().headHash.equals(records.get(records.size() - 1).chainHash))) {
            problems.add("The chain does not end where its head says: expected " + head.get().lastSeq + " events, found " + records.size());
        }
        if (head.isPresent() && records.isEmpty() && head.get().lastSeq > 0) {
            problems.add("All " + head.get().lastSeq + " events are missing");
        }
        return new AuditTrail(claimId, problems.isEmpty(), records, List.copyOf(problems));
    }

    static boolean verify(List<AuditEntity> ordered) {
        return verifyChain(ordered).isEmpty();
    }

    /** Everything wrong with a chain, event by event: position, link and content. */
    static List<String> verifyChain(List<AuditEntity> ordered) {
        List<String> problems = new ArrayList<>();
        String previous = "";
        int expectedSeq = 0;
        for (AuditEntity e : ordered) {
            expectedSeq++;
            if (e.seq == null || e.seq != expectedSeq) {
                problems.add("Event " + e.eventId + ": position " + e.seq + " where " + expectedSeq + " was expected");
            }
            boolean v2 = ALGORITHM_V2.equals(e.hashAlgorithm);
            if (v2 && !previous.equals(e.previousHash)) {
                problems.add("Event " + e.eventId + ": it does not link to the event before it");
            }
            String expected = v2 ? linkV2(previous, e) : link(previous, e.inputHash, e.outputHash);
            if (!expected.equals(e.chainHash)) {
                problems.add("Event " + e.eventId + ": its content does not match its hash");
            }
            previous = Objects.toString(e.chainHash, "");
        }
        return problems;
    }

    /** The version 1 rule, for rows written before version 2. */
    static String link(String previous, String inputHash, String outputHash) {
        return hex(sha256((previous + inputHash + outputHash).getBytes(StandardCharsets.UTF_8)));
    }

    static String linkV2(String previous, AuditEntity e) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            field(out, previous);
            for (String part : canonical(e)) {
                field(out, part);
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return hex(sha256(bytes.toByteArray()));
    }

    /** The canonical event: a fixed order that must never change within a version. */
    static List<String> canonical(AuditEntity e) {
        return java.util.Arrays.asList(e.hashAlgorithm, e.claimId, String.valueOf(e.seq), e.eventId,
                e.timestamp == null ? null : e.timestamp.toString(), e.actor, e.actorType, e.actorRole, e.agentName,
                e.modelVersion, e.rulesetVersion, e.status, e.inputHash, e.outputHash);
    }

    /** Length-prefixed, with -1 for null, so "ab"+"c" and "a"+"bc" can never encode alike and null differs from empty. */
    private static void field(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** A digest of every claim's chain head, to copy somewhere the database's administrators cannot reach. */
    public String anchorDigest() {
        StringBuilder all = new StringBuilder();
        for (AuditChainHeadEntity h : heads.findAllByOrderByClaimIdAsc()) {
            all.append(h.claimId).append(':').append(h.lastSeq).append(':').append(h.headHash).append('\n');
        }
        return hex(sha256(all.toString().getBytes(StandardCharsets.UTF_8)));
    }

    public record AuditTrail(String claimId, boolean chainValid, List<AuditEntity> records, List<String> problems) {}
}
