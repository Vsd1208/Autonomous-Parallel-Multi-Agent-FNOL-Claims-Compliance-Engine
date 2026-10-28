package com.guidewire.fnol.orchestrator;

import com.guidewire.fnol.agents.SecondOpinionAgent;
import com.guidewire.fnol.agents.VisionProvider;
import com.guidewire.fnol.common.Models.*;
import com.guidewire.fnol.common.ProcessingTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FNOLOrchestratorTest {

    private OrchestratorFixture fixture;
    private FNOLOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        fixture = new OrchestratorFixture();
        orchestrator = fixture.build();
    }

    @Test
    @DisplayName("FNOLOrchestrator executes end-to-end parallel workflow and produces explainable result")
    void process_executesEndToEndSuccessfully() {
        FNOLPayload payload = new FNOLPayload(
                "POL-AUTO-112233",
                LocalDate.now().minusDays(2),
                "CA",
                "Rear-ended by other driver at intersection. Police report filed. Avery Johnson SSN 123-45-6789 reported.",
                "Avery Johnson",
                "01/02/1980",
                "123-45-6789",
                List.of("mock://photo/front-bumper.jpg"),
                null
        );

        FNOLResponse response = orchestrator.process(payload);

        assertThat(response.claimId()).isEqualTo("CLM-2026-000001");
        assertThat(response.status()).isEqualTo("HUMAN_REVIEW");

        BranchAResult branchA = response.branchA();
        assertThat(branchA.damageAssessment().damageType()).isEqualTo("FRONT_BUMPER");
        assertThat(branchA.damageAssessment().severity()).isEqualTo("MODERATE");
        assertThat(branchA.coverage().covered()).isTrue();
        assertThat(branchA.coverage().coverageType()).isEqualTo("COLLISION");
        // $4,200 AI estimate − $1,000 deductible: the reserve is what the insurer owes, never above the estimate.
        assertThat(branchA.reserve().recommendedReserve()).isEqualByComparingTo("3200");

        BranchBResult branchB = response.branchB();
        assertThat(branchB.pii().piiDetected()).isTrue();
        assertThat(branchB.pii().redactedDescription()).doesNotContain("123-45-6789");
        assertThat(branchB.deadline().state()).isEqualTo("CA");
        assertThat(branchB.subrogation().recommendedAction()).isEqualTo("INVESTIGATE");
        assertThat(branchB.audit()).isNotEmpty();

        assertThat(response.policyContext().claimFrequencyRisk()).isEqualTo("LOW");
        assertThat(response.explanation().factors()).hasSize(6);
        assertThat(response.notes()).anyMatch(s -> s.contains("ADVISORY copilot only"));
        assertThat(response.notes()).anyMatch(s -> s.contains("final authority"));
        assertThat(response.severityGate()).isNotNull();
        assertThat(response.eligibleForAdvisoryPrefill()).isNotNull();
        assertThat(response.requiresHumanValidation()).isTrue();
    }

    @Test
    @DisplayName("FNOLOrchestrator halts when input fails guardrail validation")
    void process_failsOnInvalidInput() {
        FNOLPayload badPayload = new FNOLPayload(
                "BAD-POLICY-ID", LocalDate.now(), "CA", "Valid description",
                null, null, null, List.of(), null);

        assertThatThrownBy(() -> orchestrator.process(badPayload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Parallel branches execute successfully with both results available")
    void process_parallelExecutionCompletes() {
        FNOLResponse response = orchestrator.process(
                OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson", "Minor fender bender in parking lot"));

        assertThat(response.branchA().damageAssessment()).isNotNull();
        assertThat(response.branchA().coverage()).isNotNull();
        assertThat(response.branchA().reserve()).isNotNull();
        assertThat(response.branchB().pii()).isNotNull();
        assertThat(response.branchB().deadline()).isNotNull();
        assertThat(response.branchB().subrogation()).isNotNull();
        assertThat(response.policyContext()).isNotNull();
        assertThat(response.explanation().factors()).hasSize(6);
    }

    // ── P1 / D1: claim history drives the HIGH-risk gate ─────────────────────────────

    @Test
    @DisplayName("D1: HIGH claim-frequency policy POL-AUTO-445566 always goes to HUMAN_REVIEW")
    void process_highFrequencyPolicyRequiresHumanValidation() {
        FNOLResponse response = orchestrator.process(
                OrchestratorFixture.payload("POL-AUTO-445566", "Jordan Rivera", "Minor fender bender in parking lot"));

        assertThat(response.policyContext().claimFrequencyRisk()).isEqualTo("HIGH");
        assertThat(response.requiresHumanValidation()).isTrue();
        assertThat(response.status()).isEqualTo("HUMAN_REVIEW").isNotEqualTo("STRAIGHT_THROUGH_PROCESSING");
        assertThat(response.notes()).anyMatch(n -> n.contains("HIGH-RISK POLICY"));
    }

    @Test
    @DisplayName("D1: POL-AUTO-112233 with no recent claims stays LOW risk")
    void process_lowFrequencyPolicyStaysLow() {
        FNOLResponse response = orchestrator.process(
                OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson", "Minor fender bender in parking lot"));

        assertThat(response.policyContext().claimFrequencyRisk()).isEqualTo("LOW");
    }

    // ── P6: advisory review before STP counts as filed ──────────────────────────────

    @Test
    @DisplayName("P6: with require-advisory-review on (default), an STP-eligible claim is PENDING_ADVISORY_REVIEW")
    void process_stpEligibleWaitsForAdvisoryReviewByDefault() {
        FNOLResponse response = orchestrator.process(
                OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson", "Scraped a pole in a parking garage."));

        assertThat(response.explanation().decision()).isEqualTo("STRAIGHT_THROUGH");
        assertThat(response.status()).isEqualTo("PENDING_ADVISORY_REVIEW");
        assertThat(fixture.claimCenter.created).hasSize(1);   // still pre-filed in ClaimCenter
        assertThat(response.notes()).anyMatch(n -> n.contains("adjuster confirmation is required"));
    }

    @Test
    @DisplayName("P6: with require-advisory-review off, an STP-eligible claim is STRAIGHT_THROUGH_PROCESSING")
    void process_stpWithoutAdvisoryReview() {
        fixture.stp.setRequireAdvisoryReview(false);
        FNOLResponse response = fixture.build().process(
                OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson", "Scraped a pole in a parking garage."));

        assertThat(response.status()).isEqualTo("STRAIGHT_THROUGH_PROCESSING");
        assertThat(response.requiresHumanValidation()).isFalse();
    }

    // ── P10: Human Review Point 1 flags block STP ───────────────────────────────────

    @Test
    @DisplayName("P10: an otherwise STP-eligible claim with an intake flag goes to HUMAN_REVIEW")
    void process_inputFlagBlocksStp() {
        FNOLResponse response = orchestrator.process(
                OrchestratorFixture.payload("POL-AUTO-112233", "Demo User", "Scraped a pole in a parking garage."));

        assertThat(response.inputFlags()).containsExactly(IntakeReviewService.CLAIMANT_NAME_MISMATCH);
        assertThat(response.explanation().factors()).allMatch(f -> "PASS".equals(f.status()));
        assertThat(response.status()).isEqualTo("HUMAN_REVIEW");
        assertThat(response.requiresHumanValidation()).isTrue();
        assertThat(response.explanation().summary()).contains("CLAIMANT_NAME_MISMATCH");
    }

    @Test
    @DisplayName("P10: MISSING_EVIDENCE and INCIDENT_OUTSIDE_POLICY_PERIOD add adjuster notes")
    void process_flagsAddAdjusterNotes() {
        FNOLPayload payload = new FNOLPayload("POL-AUTO-112233",
                OrchestratorFixture.POLICY_START.minusDays(10), "CA", "Scraped a pole.",
                "Avery Johnson", null, null, List.of(), "WEB");

        FNOLResponse response = orchestrator.process(payload);

        assertThat(response.inputFlags()).contains(
                IntakeReviewService.MISSING_EVIDENCE, IntakeReviewService.INCIDENT_OUTSIDE_POLICY_PERIOD);
        assertThat(response.notes()).anyMatch(n -> n.startsWith("MISSING EVIDENCE"));
        assertThat(response.notes()).anyMatch(n -> n.startsWith("INCIDENT OUTSIDE POLICY PERIOD"));
        assertThat(response.status()).isEqualTo("HUMAN_REVIEW");
    }

    @Test
    @DisplayName("P10: HIGH claim frequency is reported as an input flag")
    void process_highFrequencyIsInputFlag() {
        FNOLResponse response = orchestrator.process(
                OrchestratorFixture.payload("POL-AUTO-445566", "Jordan Rivera", "Scraped a pole in a parking garage."));

        assertThat(response.inputFlags()).containsExactly(IntakeReviewService.HIGH_CLAIM_FREQUENCY);
    }

    @Test
    @DisplayName("An unknown policy is refused as not eligible before any branch runs")
    void process_unknownPolicyIsNotEligible() {
        assertThatThrownBy(() -> orchestrator.process(
                OrchestratorFixture.payload("POL-AUTO-999999", "Avery Johnson", "Scrape")))
                .isInstanceOf(com.guidewire.fnol.common.CoverageNotEligibleException.class);
        assertThat(fixture.claimCenter.created).isEmpty();
    }

    // ── P8: subrogation is scored at the gate with Branch A's real severity ─────────

    @Test
    @DisplayName("D12: a rear-end claim with a police report scores 35 + 20 + 15 = 70 with MODERATE damage")
    void process_subrogationUsesBranchASeverity() {
        FNOLResponse response = orchestrator.process(OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson",
                "Rear-ended by other driver at a light. Police report filed."));

        assertThat(response.branchA().damageAssessment().severity()).isEqualTo("MODERATE");
        assertThat(response.branchB().subrogation().factors().get("damageSeverity")).isEqualTo(15);
        assertThat(response.branchB().subrogation().score()).isEqualTo(70);
        assertThat(response.status()).isEqualTo("HUMAN_REVIEW");
    }

    @Test
    @DisplayName("D11: a description with redacted PII can still be STP-eligible")
    void process_redactedPiiCanBeStp() {
        FNOLResponse response = orchestrator.process(OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson",
                "Avery Johnson (SSN 123-45-6789) scraped a pole in a parking garage."));

        assertThat(response.branchB().pii().piiDetected()).isTrue();
        assertThat(response.status()).isEqualTo("PENDING_ADVISORY_REVIEW");
    }

    // ── P11: advisory and evidence attached in ClaimCenter ──────────────────────────

    @Test
    @DisplayName("P11: after createClaim the WHY panel and evidence list are attached in ClaimCenter")
    void process_attachesAdvisoryAndEvidence() {
        FNOLResponse response = orchestrator.process(
                OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson", "Scraped a pole."));

        assertThat(response.ccSyncStatus()).isEqualTo("SYNCED");
        assertThat(fixture.claimCenter.attachments.get(response.claimId()))
                .extracting(ClaimAttachment::name)
                .containsExactly("ai-advisory-why-panel.json", "evidence-uris.json");
        assertThat(fixture.claimCenter.attachments.get(response.claimId()).get(0).body())
                .isEqualTo(response.explanation());
    }

    @Test
    @DisplayName("P11: an attachment failure does not fail the intake; ccSyncStatus is FAILED")
    void process_attachmentFailureIsNotFatal() {
        fixture.claimCenter.attachmentsFail = true;

        FNOLResponse response = orchestrator.process(
                OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson", "Scraped a pole."));

        assertThat(response.claimId()).isNotNull();
        assertThat(response.ccSyncStatus()).isEqualTo("FAILED");
        assertThat(response.notes()).anyMatch(n -> n.contains("marked for retry"));
    }

    // ── Diagram alignment ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Diagram: Branch B's subrogation carries compliance notes; Branch A's reserve carries a confidence score")
    void process_subrogationNotesAndReserveConfidence() {
        FNOLResponse response = orchestrator.process(OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson",
                "Rear-ended by other driver. Police report filed."));

        assertThat(response.branchB().subrogation().complianceNotes())
                .anyMatch(n -> n.contains("other party's insurer"))
                .anyMatch(n -> n.contains("refer to the subrogation unit"));
        assertThat(response.branchA().reserve().confidence()).isEqualByComparingTo("0.91");
    }

    @Test
    @DisplayName("Diagram: a consistency flag at the gate blocks STP and is explained")
    void process_gateFlagBlocksStp() {
        fixture.policyCenter.add(OrchestratorFixture.autoPolicy("POL-AUTO-778899", "Avery Johnson"),
                List.of(new Coverage("COLLISION", new BigDecimal("3000"), new BigDecimal("500"))), List.of());

        FNOLResponse response = orchestrator.process(
                OrchestratorFixture.payload("POL-AUTO-778899", "Avery Johnson", "Scraped a pole in a parking garage."));

        assertThat(response.gateFlags()).containsExactly(GateConsistencyChecker.RESERVE_EXCEEDS_COVERAGE_LIMIT);
        assertThat(response.branchA().reserve().recommendedReserve()).isEqualByComparingTo("3000");   // capped at the limit
        assertThat(response.explanation().factors()).allMatch(f -> "PASS".equals(f.status()));
        assertThat(response.status()).isEqualTo("HUMAN_REVIEW");
        assertThat(response.notes()).anyMatch(n -> n.startsWith("CONSISTENCY CHECK RESERVE_EXCEEDS_COVERAGE_LIMIT"));
    }

    @Test
    @DisplayName("Settlement: a claim below the AI estimate is STP-eligible and pays the claimed amount less the deductible")
    void process_claimBelowEstimatePaysClaimedAmount() {
        FNOLResponse response = orchestrator.process(OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson",
                "Scraped a pole in a parking garage.", "3000"));

        assertThat(response.gateFlags()).isEmpty();
        assertThat(response.status()).isEqualTo("PENDING_ADVISORY_REVIEW");
        assertThat(response.branchA().reserve().settlementBasis()).isEqualByComparingTo("3000");
        assertThat(response.branchA().reserve().recommendedReserve()).isEqualByComparingTo("2000");
        assertThat(fixture.claimCenter.created.get(0).reserve()).isEqualByComparingTo("2000");
        assertThat(response.notes()).anyMatch(n -> n.startsWith("RESERVE: the claimant asks $3000"));
    }

    @Test
    @DisplayName("Settlement: a claim above the AI estimate is never auto-approved; it goes to an adjuster with options")
    void process_claimAboveEstimateGoesToAdjuster() {
        FNOLResponse response = orchestrator.process(OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson",
                "Scraped a pole in a parking garage.", "9000"));

        assertThat(response.gateFlags()).containsExactly(GateConsistencyChecker.CLAIMED_EXCEEDS_AI_ESTIMATE);
        assertThat(response.status()).isEqualTo("HUMAN_REVIEW");
        assertThat(response.branchA().reserve().recommendedReserve()).isEqualByComparingTo("3200");
        assertThat(response.notes()).anyMatch(n -> n.contains("request more photos, send to investigation"));
    }

    @Test
    @DisplayName("Property: a HOME claim is assessed as property damage; flood goes to an adjuster as a possible exclusion")
    void process_homeClaimUsesPropertyAssessmentAndFlagsFlood() {
        LocalDate start = OrchestratorFixture.POLICY_START;
        fixture.policyCenter.add(new Policy("POL-HOME-334455", "HOME", "ACTIVE", start, start.plusYears(1),
                "Riley Smith", "TX", "Single family property", List.of()), List.of(
                new Coverage("DWELLING", new BigDecimal("350000"), new BigDecimal("2500"))), List.of());
        List<String> angles = List.of("OVERVIEW", "CLOSE_UP");

        // An uploaded photo on a home policy: property damage, not a bumper, so no line-of-business mismatch.
        FNOLResponse pipe = orchestrator.process(new FNOLPayload("POL-HOME-334455", LocalDate.now().minusDays(2), "TX",
                "A pipe burst behind the bathroom wall.", "Riley Smith", "01/01/1990", null,
                List.of("data:image/jpeg;base64,/9j/4AAQ", "data:image/jpeg;base64,/9j/4AAQ"), "WEB", null, null,
                new BigDecimal("3500"), angles));
        assertThat(pipe.branchA().damageAssessment().damageType()).isEqualTo("WATER_DAMAGE");
        assertThat(pipe.gateFlags()).isEmpty();

        FNOLResponse flood = orchestrator.process(new FNOLPayload("POL-HOME-334455", LocalDate.now().minusDays(3), "TX",
                "The river rose and water came into the house.", "Riley Smith", "01/01/1990", null,
                List.of("mock://photo/flood-overview.jpg", "mock://photo/flood-close-up.jpg"), "WEB", null, null,
                new BigDecimal("38000"), angles));
        assertThat(flood.gateFlags()).contains(GateConsistencyChecker.POSSIBLE_FLOOD_EXCLUSION);
        assertThat(flood.status()).isEqualTo("HUMAN_REVIEW");
        assertThat(flood.notes()).anyMatch(n -> n.contains("standard homeowners policies exclude"));
    }

    @Test
    @DisplayName("A house photo on an auto policy goes to an adjuster with a note saying what the photos show")
    void process_photoOfWrongPropertyGoesToAdjuster() {
        VisionProvider sees = new VisionProvider() {
            @Override public DamageAssessment assess(FNOLPayload p) {
                return new DamageAssessment("UNKNOWN", "MODERATE", new BigDecimal("3000"), new BigDecimal("0.90"),
                        "CLEAR", Map.of("UNKNOWN", new BigDecimal("0.90")), null, "BUILDING");
            }
        };
        fixture.vision = sees;
        FNOLResponse response = fixture.build().process(OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson",
                "Scraped a pole in a parking garage.", "3000"));

        assertThat(response.gateFlags()).contains(GateConsistencyChecker.PHOTO_SUBJECT_MISMATCH);
        assertThat(response.status()).isEqualTo("HUMAN_REVIEW");
        assertThat(response.notes()).anyMatch(n -> n.contains("the photos show a building, but the policy line is AUTO"));
        // The mock's own photos match the policy, so ordinary claims are not flagged.
        assertThat(orchestrator.process(OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson",
                "Scraped a pole in a parking garage.", "3000")).gateFlags()).isEmpty();
    }

    @Test
    @DisplayName("ClaimCenter gets an exposure with the reserve, the AI advisory note and one activity per flag, due by the statutory date")
    void process_populatesClaimCenterLikeAnAdjusterWorksIt() {
        FNOLResponse flagged = orchestrator.process(OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson",
                "Scraped a pole in a parking garage.", "9000"));
        String id = flagged.claimId();

        assertThat(fixture.claimCenter.exposures.get(id)).singleElement().satisfies(e -> {
            assertThat(e.exposureType()).isEqualTo("VehicleDamage");
            assertThat(e.coverageType()).isEqualTo("COLLISION");
            assertThat(e.reserve()).isEqualByComparingTo(flagged.branchA().reserve().recommendedReserve());
        });
        assertThat(fixture.claimCenter.notes.get(id)).singleElement().satisfies(n -> {
            assertThat(n.topic()).isEqualTo("AI advisory");
            assertThat(n.body()).contains("CLAIMED_EXCEEDS_AI_ESTIMATE").contains("the adjuster decides")
                    .doesNotContain("Scraped a pole");   // the claimant's description stays out of ClaimCenter notes
        });
        assertThat(fixture.claimCenter.activities.get(id)).singleElement().satisfies(a -> {
            assertThat(a.subject()).isEqualTo("Review AI flag: claimed exceeds ai estimate");
            assertThat(a.description()).contains("request more photos, send to investigation");
            assertThat(a.dueDate()).isEqualTo(flagged.branchB().deadline().acknowledgeBy());
            assertThat(a.assignedTo()).isEqualTo(FNOLOrchestrator.FNOL_QUEUE);
        });

        // An STP-eligible claim gets one activity: confirm the advisory.
        FNOLResponse clean = orchestrator.process(OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson",
                "Scraped a pole in a parking garage.", "3000"));
        assertThat(clean.status()).isEqualTo("PENDING_ADVISORY_REVIEW");
        assertThat(fixture.claimCenter.activities.get(clean.claimId())).extracting(CcActivity::subject)
                .containsExactly("Confirm AI advisory (straight-through eligible)");
        assertThat(FNOLOrchestrator.exposureType("HOME", "DWELLING")).isEqualTo("Dwelling");
    }

    @Test
    @DisplayName("Second opinion: asked in Branch A; disagreement sends the claim to an adjuster with both models' answers")
    void process_secondOpinionDisagreementGoesToAdjuster() {
        SecondOpinion disagrees = new SecondOpinion("second-opinion v1",
                Map.of("FRONT_BUMPER", new BigDecimal("0.13"), "SIDE_PANEL", new BigDecimal("0.84"),
                        "REAR_BUMPER", new BigDecimal("0.02"), "WINDSHIELD", new BigDecimal("0.01")),
                Map.of("VEHICLE", new BigDecimal("0.97")));
        FNOLOrchestrator withModel = fixture.build();
        withModel.setSecondOpinion(new SecondOpinionAgent("http://vision-ml:8090", null, null) {
            @Override
            public CompletableFuture<SecondOpinion> start(FNOLPayload payload, String line) {
                assertThat(line).isEqualTo("AUTO");
                return CompletableFuture.completedFuture(disagrees);
            }
        });

        FNOLResponse response = withModel.process(OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson",
                "Scraped a pole in a parking garage.", "3000"));

        assertThat(response.branchA().secondOpinion()).isEqualTo(disagrees);
        assertThat(response.gateFlags()).containsExactly(GateConsistencyChecker.MODEL_DISAGREEMENT);
        assertThat(response.status()).isEqualTo("HUMAN_REVIEW");
        assertThat(response.notes()).anyMatch(n -> n.contains("Gemini says FRONT_BUMPER")
                && n.contains("gives that only 13.0%") && n.contains("favours SIDE_PANEL (84%)"));
        // Without the model the same claim is STP-eligible.
        assertThat(orchestrator.process(OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson",
                "Scraped a pole in a parking garage.", "3000")).gateFlags()).isEmpty();
    }

    @Test
    @DisplayName("Settlement: up to 10% above the AI estimate is tolerated, but only the AI estimate is reserved")
    void process_claimWithinToleranceIsReservedAtEstimate() {
        FNOLResponse response = orchestrator.process(OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson",
                "Scraped a pole in a parking garage.", "4500"));

        assertThat(response.gateFlags()).isEmpty();
        assertThat(response.branchA().reserve().recommendedReserve()).isEqualByComparingTo("3200");
    }

    @Test
    @DisplayName("Diagram: ClaimCenter gets a draft until a human decides")
    void process_claimCenterDraftUntilHumanDecides() {
        orchestrator.process(OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson", "Scraped a pole."));
        orchestrator.process(OrchestratorFixture.payload("POL-AUTO-112233", "Demo User", "Scraped a pole."));

        assertThat(fixture.claimCenter.created).extracting(ClaimCreateRequest::initialStatus)
                .containsExactly("DRAFT_PENDING_REVIEW", "DRAFT_PENDING_REVIEW");
    }

    @Test
    @DisplayName("Diagram: STP with no advisory review goes straight to OPEN in ClaimCenter")
    void process_unreviewedStpIsOpenInClaimCenter() {
        fixture.stp.setRequireAdvisoryReview(false);
        fixture.build().process(OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson", "Scraped a pole."));

        assertThat(fixture.claimCenter.created.get(0).initialStatus()).isEqualTo("OPEN");
    }

    // ── P5: complete audit trail, no raw PII hashed ─────────────────────────────────

    @Test
    @DisplayName("P5: one audit record per agent plus the ReconciliationGate decision")
    void process_writesOneAuditRecordPerAgent() {
        FNOLResponse response = orchestrator.process(
                OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson", "Minor fender bender in parking lot"));

        assertThat(response.branchB().audit())
                .extracting(AuditRecord::agentName)
                .containsExactly("VisionDamageAgent", "PolicyValidatorAgent", "ReserveCalculatorAgent",
                        "PiiRedactionAgent", "StatutoryDeadlineAgent", "SubrogationScorerAgent", "ReconciliationGate");
        AuditRecord gate = response.branchB().audit().get(6);
        assertThat(gate.status()).isEqualTo(response.status());
    }

    @Test
    @DisplayName("P5: no audit input contains the claimant's SSN, DOB or name")
    void process_neverHashesRawPii() {
        List<String> hashedInputs = new CopyOnWriteArrayList<>();
        fixture.audit = new com.guidewire.fnol.agents.AuditTrailAgent() {
            @Override
            public AuditRecord record(String claimId, String agentName, Object input, Object output, String status) {
                hashedInputs.add(String.valueOf(input));
                hashedInputs.add(String.valueOf(output));
                return super.record(claimId, agentName, input, output, status);
            }
        };
        FNOLPayload payload = new FNOLPayload("POL-AUTO-112233", LocalDate.now().minusDays(2), "CA",
                "Avery Johnson, SSN 123-45-6789, DOB 01/02/1980, scraped a pole.",
                "Avery Johnson", "01/02/1980", "123-45-6789", List.of("mock://photo/normal.jpg"), "WEB");

        fixture.build().process(payload);

        assertThat(hashedInputs).hasSize(14);
        assertThat(hashedInputs).noneMatch(s -> s.contains("123-45-6789"));
        assertThat(hashedInputs).noneMatch(s -> s.contains("01/02/1980"));
        assertThat(hashedInputs).noneMatch(s -> s.contains("Avery Johnson"));
    }

    // ── P3 / D4: bounded executor, real interrupts ──────────────────────────────────

    @Test
    @DisplayName("D4: two concurrent process() calls both finish")
    void process_concurrentCallsBothFinish() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<FNOLResponse> first = callers.submit(() -> orchestrator.process(
                    OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson", "Scrape in parking lot")));
            Future<FNOLResponse> second = callers.submit(() -> orchestrator.process(
                    OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson", "Dent in garage")));

            assertThat(first.get(10, TimeUnit.SECONDS).claimId()).isNotNull();
            assertThat(second.get(10, TimeUnit.SECONDS).claimId()).isNotNull();
            assertThat(fixture.claimCenter.created).hasSize(2);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    @DisplayName("D4: a branch that sleeps past the timeout raises ProcessingTimeoutException and its thread is interrupted")
    void process_timeoutInterruptsStuckBranch() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        fixture.vision = sleepyVision(interrupted);
        fixture.properties.setTimeoutSeconds(1);
        FNOLOrchestrator timeoutOrch = fixture.build();

        assertThatThrownBy(() -> timeoutOrch.process(
                OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson", "Minor fender bender in parking lot")))
                .isInstanceOf(ProcessingTimeoutException.class)
                .hasMessageContaining("timed out");

        // cancel(true) on the submitted task must interrupt the sleeping branch, freeing its thread.
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(fixture.claimCenter.created).isEmpty();
    }

    @Test
    @DisplayName("D9: demo scenario 4's slow mock photo trips the orchestrator deadline, not an HTTP timeout")
    void process_slowMockPhotoTripsOrchestratorDeadline() {
        fixture.properties.setTimeoutSeconds(1);
        FNOLOrchestrator timeoutOrch = fixture.build();
        FNOLPayload payload = new FNOLPayload("POL-AUTO-112233", LocalDate.now().minusDays(40), "CA",
                "Front bumper damaged.", "Avery Johnson", null, null, List.of("mock://photo/slow-damage.jpg"), "API");

        long start = System.nanoTime();
        assertThatThrownBy(() -> timeoutOrch.process(payload))
                .isInstanceOf(ProcessingTimeoutException.class)
                .hasMessageContaining("timed out after 1 seconds");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5_000);
    }

    @Test
    @DisplayName("D4: a saturated executor refuses new work with ProcessingTimeoutException")
    void process_saturatedExecutorRefusesWork() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        fixture.vision = sleepyVision(interrupted);
        fixture.properties.setPoolSize(1);
        fixture.properties.setQueueSize(1);
        fixture.properties.setTimeoutSeconds(5);
        FNOLOrchestrator small = fixture.build();
        FNOLPayload payload = OrchestratorFixture.payload("POL-AUTO-112233", "Avery Johnson", "Scrape");

        ExecutorService callers = Executors.newSingleThreadExecutor();
        try {
            // First intake occupies the only thread (Branch A sleeps) and the only queue slot (Branch B).
            Future<?> first = callers.submit(() -> small.process(payload));
            Thread.sleep(300);

            assertThatThrownBy(() -> small.process(payload))
                    .isInstanceOf(ProcessingTimeoutException.class)
                    .hasMessageContaining("capacity");
            first.cancel(true);
        } finally {
            callers.shutdownNow();
            small.shutdown();
        }
    }

    private static VisionProvider sleepyVision(CountDownLatch interrupted) {
        return payload -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return DamageAssessment.of("FRONT_BUMPER", "MODERATE", new BigDecimal("4200"), new BigDecimal("0.91"));
        };
    }
}
