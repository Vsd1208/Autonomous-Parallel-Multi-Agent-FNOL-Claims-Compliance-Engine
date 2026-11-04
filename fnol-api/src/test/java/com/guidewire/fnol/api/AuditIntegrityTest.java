package com.guidewire.fnol.api;

import com.guidewire.fnol.agents.AuditTrailAgent;
import com.guidewire.fnol.api.persistence.AuditChainHeadEntity;
import com.guidewire.fnol.api.persistence.AuditChainHeadRepository;
import com.guidewire.fnol.api.persistence.AuditEntity;
import com.guidewire.fnol.api.persistence.AuditRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Every field an attacker might edit is covered, and concurrent writers cannot fork a chain. */
@SpringBootTest
class AuditIntegrityTest {

    @Autowired private AuditService service;
    @Autowired private AuditTrailAgent agent;
    @Autowired private AuditRepository audits;
    @Autowired private AuditChainHeadRepository heads;

    private String chain(int events) {
        String id = "CLM-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < events; i++) {
            service.append(id, List.of(agent.record(id, "Step" + i, "in" + i, "out" + i, "SUCCESS")), "ADJ-001");
        }
        return id;
    }

    private AuditEntity event(String claimId, int seq) {
        return audits.findByClaimIdOrderBySeqAscTimestampAsc(claimId).get(seq - 1);
    }

    @Test
    @DisplayName("An untouched chain verifies, with no problems, and is written under the versioned algorithm")
    void untouched() {
        String id = chain(4);
        AuditService.AuditTrail trail = service.trail(id);
        assertThat(trail.chainValid()).isTrue();
        assertThat(trail.problems()).isEmpty();
        assertThat(trail.records()).allSatisfy(e -> assertThat(e.hashAlgorithm).isEqualTo("FNOL-AUDIT-SHA256-V2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"actor", "actorType", "actorRole", "timestamp", "status", "inputHash", "outputHash", "agentName", "modelVersion", "rulesetVersion", "previousHash", "seq", "chainHash"})
    @DisplayName("Editing any one field of any event is detected")
    void tamperingIsDetected(String field) {
        String id = chain(3);
        AuditEntity e = event(id, 2);
        switch (field) {
            case "actor" -> e.actor = "SOMEONE-ELSE";
            case "actorType" -> e.actorType = "SYSTEM_ACTOR";
            case "actorRole" -> e.actorRole = "SUPERVISOR";
            case "timestamp" -> e.timestamp = e.timestamp.minusSeconds(3600);
            case "status" -> e.status = "APPROVED";
            case "inputHash" -> e.inputHash = "0".repeat(64);
            case "outputHash" -> e.outputHash = "0".repeat(64);
            case "agentName" -> e.agentName = "Forged";
            case "modelVersion" -> e.modelVersion = "other-model";
            case "rulesetVersion" -> e.rulesetVersion = "other-rules";
            case "previousHash" -> e.previousHash = "0".repeat(64);
            case "seq" -> e.seq = 9;
            case "chainHash" -> e.chainHash = "0".repeat(64);
            default -> throw new IllegalArgumentException(field);
        }
        audits.save(e);
        AuditService.AuditTrail trail = service.trail(id);
        assertThat(trail.chainValid()).as(field).isFalse();
        assertThat(trail.problems()).isNotEmpty();
    }

    @Test
    @DisplayName("Missing events are detected: from the middle, the end, or all of them")
    void missingEvents() {
        String middle = chain(3);
        audits.delete(event(middle, 2));
        assertThat(service.trail(middle).chainValid()).isFalse();

        String last = chain(3);
        audits.delete(event(last, 3));
        assertThat(service.trail(last).chainValid()).as("only the head row shows this").isFalse();
        assertThat(service.trail(last).problems()).anyMatch(p -> p.contains("head"));

        String all = chain(2);
        audits.deleteAll(audits.findByClaimIdOrderBySeqAscTimestampAsc(all));
        assertThat(service.trail(all).chainValid()).isFalse();
    }

    @Test
    @DisplayName("Rewriting an event and recomputing its own hash still breaks the next link")
    void recomputedHashBreaksTheNextLink() {
        String id = chain(3);
        AuditEntity e = event(id, 2);
        e.actor = "SOMEONE-ELSE";
        e.chainHash = AuditService.linkV2(e.previousHash, e);
        audits.save(e);
        assertThat(service.trail(id).chainValid()).isFalse();
    }

    @Test
    @DisplayName("Encoding is unambiguous: moving characters between fields changes the hash, and null differs from empty")
    void unambiguous() {
        AuditEntity a = fixed();
        a.inputHash = "ab";
        a.outputHash = "c";
        AuditEntity b = fixed();
        b.inputHash = "a";
        b.outputHash = "bc";
        assertThat(AuditService.linkV2("", a)).isNotEqualTo(AuditService.linkV2("", b));
        AuditEntity empty = fixed();
        empty.actorRole = "";
        AuditEntity none = fixed();
        none.actorRole = null;
        assertThat(AuditService.linkV2("", empty)).isNotEqualTo(AuditService.linkV2("", none));
    }

    private static AuditEntity fixed() {
        AuditEntity e = new AuditEntity();
        e.hashAlgorithm = AuditService.ALGORITHM_V2;
        e.claimId = "C";
        e.seq = 1;
        e.eventId = "E";
        e.timestamp = Instant.parse("2026-11-04T00:00:00Z");
        e.actor = "A";
        e.status = "S";
        e.agentName = "N";
        e.inputHash = "i";
        e.outputHash = "o";
        return e;
    }

    @Test
    @DisplayName("Rows from before version 2 keep verifying, and new events chain on from them")
    void legacyRowsStillVerify() {
        String id = "CLM-" + UUID.randomUUID().toString().substring(0, 8);
        AuditEntity old = new AuditEntity();
        old.eventId = UUID.randomUUID().toString();
        old.claimId = id;
        old.agentName = "Old";
        old.timestamp = Instant.now();
        old.inputHash = "a";
        old.outputHash = "b";
        old.status = "SUCCESS";
        old.seq = 1;
        old.actor = "SYSTEM";
        old.chainHash = AuditService.link("", "a", "b");
        audits.save(old);
        service.append(id, List.of(agent.record(id, "New", "in", "out", "SUCCESS")), "SYSTEM");
        assertThat(service.trail(id).chainValid()).isTrue();
        assertThat(event(id, 2).previousHash).isEqualTo(old.chainHash);
    }

    @Test
    @DisplayName("Concurrent appends to one claim cannot fork its chain")
    void concurrentAppends() throws Exception {
        String id = "CLM-" + UUID.randomUUID().toString().substring(0, 8);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Callable<Void>> writers = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            int n = i;
            writers.add(() -> {
                service.append(id, List.of(agent.record(id, "Step" + n, "in", "out", "SUCCESS")), "SYSTEM");
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(writers)) {
            f.get();
        }
        pool.shutdown();
        AuditService.AuditTrail trail = service.trail(id);
        assertThat(trail.records()).hasSize(30);
        assertThat(trail.records()).extracting(e -> e.seq).doesNotHaveDuplicates();
        assertThat(trail.chainValid()).as(String.valueOf(trail.problems())).isTrue();
    }

    @Test
    @DisplayName("The anchor digest changes when any chain changes, so a copy kept elsewhere shows a rewrite")
    void anchor() {
        String id = chain(1);
        String before = service.anchorDigest();
        service.append(id, List.of(agent.record(id, "Later", "in", "out", "SUCCESS")), "SYSTEM");
        assertThat(service.anchorDigest()).isNotEqualTo(before);
        AuditChainHeadEntity head = heads.findById(id).orElseThrow();
        assertThat(head.lastSeq).isEqualTo(2);
    }
}
