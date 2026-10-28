package com.guidewire.fnol.mock;

import com.guidewire.fnol.common.Models.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
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
    private static final Logger log = LoggerFactory.getLogger(ClaimCenterController.class);

    private final ClaimCenterStore store;
    private final AtomicInteger sequence;

    /**
     * Claims live in the database, so they survive a restart like in a real ClaimCenter, and numbering continues
     * after the highest stored claim. MOCK_CC_START_SEQUENCE only raises the first number (e.g. for an empty
     * in-memory database while the FNOL database already holds claims).
     */
    ClaimCenterController(ClaimCenterStore store, @Value("${MOCK_CC_START_SEQUENCE:1}") int startSequence) {
        this.store = store;
        int highest = store.highestClaimNumber();
        this.sequence = new AtomicInteger(Math.max(startSequence, highest + 1));
        log.info("[MockCC] {} stored claim number(s) up to {}; next is {}", highest > 0 ? "Found" : "No",
                highest, sequence.get());
    }

    @PostMapping("/cc/claims")
    ClaimCreateResponse create(@RequestBody ClaimCreateRequest request) throws InterruptedException {
        if ("true".equalsIgnoreCase(System.getenv("MOCK_GW_TIMEOUT"))) {
            Thread.sleep(11000);
        }
        String id = "CLM-2026-%06d".formatted(sequence.getAndIncrement());
        // The FNOL engine pre-populates a draft until a human decides; OPEN enters the standard claim process.
        String status = request.initialStatus() != null ? request.initialStatus() : "OPEN";
        store.insert(id, status, request);
        return new ClaimCreateResponse(id, status);
    }

    @GetMapping("/cc/claims/{claimId}")
    Claim get(@PathVariable("claimId") String claimId) {
        return store.find(claimId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Synthetic claim not found"));
    }

    /** Applies a status/notes/reserve change and tracks it as a review action. */
    @PutMapping("/cc/claims/{claimId}")
    Claim update(@PathVariable("claimId") String claimId, @RequestBody ClaimUpdateRequest request) {
        if (!store.update(claimId, request)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Synthetic claim not found");
        }
        return get(claimId);
    }

    @PostMapping("/cc/claims/{claimId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    ClaimAttachment attach(@PathVariable("claimId") String claimId, @RequestBody ClaimAttachment attachment) {
        if (!store.exists(claimId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Synthetic claim not found");
        }
        store.attach(claimId, attachment);
        return attachment;
    }

    // ── Claim child objects, on paths shaped like the ClaimCenter Cloud API claim resources ──

    @PostMapping("/cc/rest/claim/v1/claims/{claimId}/exposures")
    @ResponseStatus(HttpStatus.CREATED)
    CcExposure addExposure(@PathVariable("claimId") String claimId, @RequestBody CcExposure exposure) {
        requireClaim(claimId);
        return store.addExposure(claimId, exposure);
    }

    @GetMapping("/cc/rest/claim/v1/claims/{claimId}/exposures")
    List<CcExposure> exposures(@PathVariable("claimId") String claimId) {
        requireClaim(claimId);
        return store.exposures(claimId);
    }

    @PostMapping("/cc/rest/claim/v1/claims/{claimId}/activities")
    @ResponseStatus(HttpStatus.CREATED)
    CcActivity addActivity(@PathVariable("claimId") String claimId, @RequestBody CcActivity activity) {
        requireClaim(claimId);
        return store.addActivity(claimId, activity);
    }

    @GetMapping("/cc/rest/claim/v1/claims/{claimId}/activities")
    List<CcActivity> activities(@PathVariable("claimId") String claimId) {
        requireClaim(claimId);
        return store.activities(claimId);
    }

    @PostMapping("/cc/rest/claim/v1/claims/{claimId}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    CcNote addNote(@PathVariable("claimId") String claimId, @RequestBody CcNote note) {
        requireClaim(claimId);
        return store.addNote(claimId, note);
    }

    @GetMapping("/cc/rest/claim/v1/claims/{claimId}/notes")
    List<CcNote> notes(@PathVariable("claimId") String claimId) {
        requireClaim(claimId);
        return store.notes(claimId);
    }

    @GetMapping("/cc/rest/claim/v1/claims/{claimId}/documents")
    List<ClaimAttachment> documents(@PathVariable("claimId") String claimId) {
        requireClaim(claimId);
        return store.documents(claimId);
    }

    private void requireClaim(String claimId) {
        if (!store.exists(claimId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Synthetic claim not found");
        }
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
