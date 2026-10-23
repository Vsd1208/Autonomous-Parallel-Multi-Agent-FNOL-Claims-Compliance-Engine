package com.guidewire.fnol.mock;

import com.guidewire.fnol.common.Models.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootApplication
public class MockGuidewireApplication {
    public static void main(String[] args) {
        SpringApplication.run(MockGuidewireApplication.class, args);
    }
}

@RestController
class PolicyCenterController {
    private final Map<String, Policy> policies = Seed.policies();

    @GetMapping("/pc/policies/{policyNumber}")
    Policy policy(@PathVariable("policyNumber") String policyNumber) {
        return Optional.ofNullable(policies.get(policyNumber))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Synthetic policy not found"));
    }

    /**
     * MOCK_PC_TIMEOUT=true delays this endpoint, which only Branch A calls, by 12 s. The API's
     * 3 s Guidewire read timeout fires first, so the branch fails and the claim is escalated with
     * 504 PROCESSING_TIMEOUT. (Demo scenario 4 hits the 10 s orchestrator deadline instead, via a
     * slow mock photo; see MockVisionProvider.)
     */
    @GetMapping("/pc/policies/{policyNumber}/coverages")
    List<Coverage> coverages(@PathVariable("policyNumber") String policyNumber) throws InterruptedException {
        if ("true".equalsIgnoreCase(System.getenv("MOCK_PC_TIMEOUT"))) {
            Thread.sleep(12000);
        }
        return policy(policyNumber).coverages();
    }

    @GetMapping("/pc/policies/{policyNumber}/history")
    List<ClaimHistory> history(@PathVariable("policyNumber") String policyNumber) {
        policy(policyNumber);
        return Seed.history(policyNumber);
    }
}

@RestController
class ClaimCenterController {
    private final AtomicInteger sequence;
    private final Map<String, Claim> claims = new ConcurrentHashMap<>();
    private final Map<String, List<ClaimAttachment>> attachments = new ConcurrentHashMap<>();

    /**
     * The counter is in memory, so a restart would reissue IDs that the FNOL database already holds.
     * Set MOCK_CC_START_SEQUENCE above the highest issued number to continue after a restart.
     */
    ClaimCenterController(@Value("${MOCK_CC_START_SEQUENCE:1}") int startSequence) {
        this.sequence = new AtomicInteger(startSequence);
    }

    @PostMapping("/cc/claims")
    ClaimCreateResponse create(@RequestBody ClaimCreateRequest request) throws InterruptedException {
        if ("true".equalsIgnoreCase(System.getenv("MOCK_GW_TIMEOUT"))) {
            Thread.sleep(11000);
        }
        String id = "CLM-2026-%06d".formatted(sequence.getAndIncrement());
        claims.put(id, new Claim(id, "OPEN", request.policyNumber(), request.incidentDate(), request.state(),
                request.estimatedDamage(), request.reserve()));
        return new ClaimCreateResponse(id, "OPEN");
    }

    @GetMapping("/cc/claims/{claimId}")
    Claim get(@PathVariable("claimId") String claimId) {
        Claim c = Optional.ofNullable(claims.get(claimId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Synthetic claim not found"));
        return new Claim(c.claimId(), c.status(), c.policyNumber(), c.incidentDate(), c.state(),
                c.estimatedDamage(), c.reserve(), c.notes(), List.copyOf(attachmentsOf(claimId)));
    }

    @PutMapping("/cc/claims/{claimId}")
    Claim update(@PathVariable("claimId") String claimId, @RequestBody ClaimUpdateRequest request) {
        Claim updated = claims.computeIfPresent(claimId, (id, c) -> new Claim(id,
                request.status() != null ? request.status() : c.status(),
                c.policyNumber(), c.incidentDate(), c.state(), c.estimatedDamage(), c.reserve(),
                request.notes(), List.of()));
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Synthetic claim not found");
        }
        return get(claimId);
    }

    @PostMapping("/cc/claims/{claimId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    ClaimAttachment attach(@PathVariable("claimId") String claimId, @RequestBody ClaimAttachment attachment) {
        if (!claims.containsKey(claimId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Synthetic claim not found");
        }
        attachmentsOf(claimId).add(attachment);
        return attachment;
    }

    private List<ClaimAttachment> attachmentsOf(String claimId) {
        return attachments.computeIfAbsent(claimId, id -> Collections.synchronizedList(new ArrayList<>()));
    }
}

/**
 * Synthetic seed data. Policy terms and claim dates are relative to today so the
 * data never goes stale: every policy started six months ago and runs for a year.
 */
final class Seed {

    static Map<String, Policy> policies() {
        Map<String, Policy> p = new HashMap<>();
        p.put("POL-AUTO-112233", auto("POL-AUTO-112233", "CA", "Avery Johnson", "2025 Toyota Camry"));
        p.put("POL-AUTO-223344", auto("POL-AUTO-223344", "NY", "Morgan Lee", "2023 Honda CR-V"));
        // High claim-frequency policy: two claims in the last 150 days (see history()).
        p.put("POL-AUTO-445566", auto("POL-AUTO-445566", "CA", "Jordan Rivera", "2022 Ford Escape"));
        p.put("POL-HOME-334455", new Policy("POL-HOME-334455", "HOME", "ACTIVE", effective(), effective().plusYears(1),
                "Riley Smith", "TX", "Single family property",
                List.of(new Coverage("DWELLING", bd("350000"), bd("2500")),
                        new Coverage("PERSONAL_PROPERTY", bd("100000"), bd("1000")))));
        return p;
    }

    static Policy auto(String n, String st, String insured, String risk) {
        return new Policy(n, "AUTO", "ACTIVE", effective(), effective().plusYears(1), insured, st, risk,
                List.of(new Coverage("COLLISION", bd("50000"), bd("1000")),
                        new Coverage("COMPREHENSIVE", bd("40000"), bd("500")),
                        new Coverage("LIABILITY", bd("100000"), bd("0"))));
    }

    static List<ClaimHistory> history(String p) {
        String prefix = "HIST-" + p.substring(p.length() - 4);
        LocalDate today = LocalDate.now();
        if (p.equals("POL-AUTO-445566")) {
            return List.of(
                    new ClaimHistory(prefix + "-01", today.minusDays(40), "CLOSED", bd("2200"), "Synthetic rear bumper loss"),
                    new ClaimHistory(prefix + "-02", today.minusDays(130), "OPEN", bd("3100"), "Synthetic side panel loss"));
        }
        // Older than 12 months, so frequency risk stays LOW.
        return List.of(
                new ClaimHistory(prefix + "-01", today.minusMonths(29), "CLOSED", bd("1850"), "Synthetic prior glass loss"),
                new ClaimHistory(prefix + "-02", today.minusMonths(19), "CLOSED", bd("0"), "Synthetic inquiry only"));
    }

    static LocalDate effective() {
        return LocalDate.now().minusMonths(6);
    }

    static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
