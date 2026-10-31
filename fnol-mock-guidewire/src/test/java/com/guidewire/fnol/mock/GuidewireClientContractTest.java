package com.guidewire.fnol.mock;

import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.enrichment.GuidewireAuthInterceptor;
import com.guidewire.fnol.enrichment.MockClaimCenterClient;
import com.guidewire.fnol.enrichment.MockPolicyCenterClient;
import com.guidewire.fnol.enrichment.PolicyCenterClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contract the FNOL engine relies on from PolicyCenter and ClaimCenter, exercised through the engine's own
 * client classes ({@link MockPolicyCenterClient}, {@link MockClaimCenterClient}).
 *
 * <p>By default it runs against this mock on a random port. To run it against another server, set
 * {@code GUIDEWIRE_CONTRACT_BASE_URL} (and {@code GUIDEWIRE_CONTRACT_TOKEN} for a bearer token,
 * {@code GUIDEWIRE_CONTRACT_POLICY} for a policy number that exists there). A real ClaimCenter tenant needs an
 * adapter for its Cloud API paths first: the mock's paths are modelled on, not verified against, the Cloud API
 * (docs/guidewire-integration.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GuidewireClientContractTest {

    @LocalServerPort
    int port;

    private PolicyCenterClient policyCenter;
    private ClaimCenterClient claimCenter;
    private String policyNumber;

    @BeforeEach
    void clients() {
        String baseUrl = setting("GUIDEWIRE_CONTRACT_BASE_URL", "http://localhost:" + port);
        RestTemplate rest = new RestTemplate();
        rest.getInterceptors().add(new GuidewireAuthInterceptor(setting("GUIDEWIRE_CONTRACT_TOKEN", "")));
        policyCenter = new MockPolicyCenterClient(rest, baseUrl);
        claimCenter = new MockClaimCenterClient(rest, baseUrl);
        policyNumber = setting("GUIDEWIRE_CONTRACT_POLICY", "POL-AUTO-112233");
    }

    private static String setting(String name, String fallback) {
        String v = System.getProperty(name, System.getenv(name));
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    @Test
    @DisplayName("PolicyCenter: an in-force policy with its term, state and line of business")
    void policyInForce() {
        Policy p = policyCenter.getPolicy(policyNumber);
        assertThat(p.policyNumber()).isEqualTo(policyNumber);
        assertThat(p.status()).isEqualTo("ACTIVE");
        assertThat(p.policyType()).isIn("AUTO", "HOME");
        assertThat(p.state()).isNotBlank();
        assertThat(p.effectiveDate()).isBeforeOrEqualTo(LocalDate.now());
        assertThat(p.expirationDate()).isAfter(LocalDate.now());
    }

    @Test
    @DisplayName("PolicyCenter: coverages carry a positive limit and a non-negative deductible; history is a list")
    void coveragesAndHistory() {
        List<Coverage> coverages = policyCenter.getCoverages(policyNumber);
        assertThat(coverages).isNotEmpty().allSatisfy(c -> {
            assertThat(c.type()).isNotBlank();
            assertThat(c.limit()).isPositive();
            assertThat(c.deductible()).isNotNegative();
        });
        assertThat(policyCenter.getPolicyClaimHistory(policyNumber)).isNotNull();
    }

    @Test
    @DisplayName("PolicyCenter: an unknown policy is a 404, which the engine turns into a human-review claim")
    void unknownPolicyIs404() {
        assertThatThrownBy(() -> policyCenter.getPolicy("POL-AUTO-000000"))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("ClaimCenter: a claim created as a draft stays a draft until a person decides")
    void draftUntilDecision() {
        ClaimCreateResponse created = claimCenter.createClaim(new ClaimCreateRequest(policyNumber,
                LocalDate.now().minusDays(3), "CA", new BigDecimal("4200.00"), new BigDecimal("3200.00"),
                "DRAFT_PENDING_REVIEW"));
        assertThat(created.claimId()).isNotBlank();
        assertThat(created.status()).isEqualTo("DRAFT_PENDING_REVIEW");

        Claim claim = claimCenter.getClaim(created.claimId());
        assertThat(claim.status()).isEqualTo("DRAFT_PENDING_REVIEW");
        assertThat(claim.policyNumber()).isEqualTo(policyNumber);

        claimCenter.updateClaim(created.claimId(),
                new ClaimUpdateRequest("OPEN", "Photos match the description.", "APPROVE", "ADJ-001", null));
        assertThat(claimCenter.getClaim(created.claimId()).status()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("ClaimCenter: exposures, activities, notes and documents are stored on the claim with server ids")
    void childObjects() {
        String claimId = claimCenter.createClaim(new ClaimCreateRequest(policyNumber, LocalDate.now().minusDays(2),
                "CA", new BigDecimal("4200.00"), new BigDecimal("3200.00"), "DRAFT_PENDING_REVIEW")).claimId();

        CcExposure exposure = claimCenter.addExposure(claimId, new CcExposure(null, "VehicleDamage", "COLLISION",
                new BigDecimal("3200.00"), "claimcost", null));
        CcActivity activity = claimCenter.addActivity(claimId, new CcActivity(null, "Confirm AI advisory",
                "Advisory only: the adjuster decides.", "normal", LocalDate.now().plusDays(15), "FNOL adjuster queue",
                null, null));
        CcNote note = claimCenter.addNote(claimId, new CcNote(null, "AI advisory", "Recommendation", "Advisory only.",
                null));
        claimCenter.attachDocument(claimId, new ClaimAttachment("why-panel.json", "application/json",
                Map.of("decision", "STRAIGHT_THROUGH")));

        assertThat(exposure.exposureId()).isNotBlank();
        assertThat(exposure.reserve()).isEqualByComparingTo("3200.00");
        assertThat(activity.activityId()).isNotBlank();
        assertThat(note.noteId()).isNotBlank();

        Claim claim = claimCenter.getClaim(claimId);
        assertThat(claim.exposures()).extracting(CcExposure::coverageType).contains("COLLISION");
        assertThat(claim.activities()).extracting(CcActivity::subject).contains("Confirm AI advisory");
        assertThat(claim.claimNotes()).extracting(CcNote::subject).contains("Recommendation");
        assertThat(claim.attachments()).extracting(ClaimAttachment::name).contains("why-panel.json");
    }

    @Test
    @DisplayName("ClaimCenter: writes to an unknown claim are a 404, never a silent success")
    void unknownClaimIs404() {
        assertThatThrownBy(() -> claimCenter.addNote("CLM-0000-000000",
                new CcNote(null, "general", "x", "x", null)))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
