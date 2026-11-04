package com.guidewire.fnol.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guidewire.fnol.agents.AuditTrailAgent;
import com.guidewire.fnol.agents.DraftGuard;
import com.guidewire.fnol.agents.DraftingAgent;
import com.guidewire.fnol.api.persistence.ClaimDraftEntity;
import com.guidewire.fnol.api.persistence.ClaimDraftRepository;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.common.Models.ClaimAttachment;
import com.guidewire.fnol.common.Models.CcNote;
import com.guidewire.fnol.common.Models.DeadlineResult;
import com.guidewire.fnol.common.Models.FNOLPayload;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.orchestrator.AcknowledgementLetter;
import com.guidewire.fnol.orchestrator.IntakeReviewService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Drafts the adjuster advisory note and the acknowledgement letter for a person to review. Tools are built from an allow-list of
 * fields of the saved analysis, which holds no description text, so only redacted facts reach the model. Nothing reaches the claims
 * system until a person accepts the draft, and both steps are audited. It writes no decision, reserve, status or assignment.
 */
@Service
@ConditionalOnProperty(name = "fnol.drafting-agent.enabled", havingValue = "true")
class ClaimDraftingService {

    static final String ADVISORY_NOTE = "ADVISORY_NOTE";
    static final String ACKNOWLEDGEMENT_LETTER = "ACKNOWLEDGEMENT_LETTER";
    static final String DRAFT = "DRAFT";
    static final String ACCEPTED = "ACCEPTED";
    static final String DISCARDED = "DISCARDED";
    private static final String RULES = """
            You draft text for a licensed claims adjuster, who will review and edit it. Call the tools you need, then reply with only the text.
            Use only facts the tools returned. Never state or imply that a claim is accepted, denied or covered, that anyone is at fault, or
            that a payment will be made. Give a date, amount or period only if a tool returned it, and write dates as YYYY-MM-DD.
            Never include a person's name, phone number, email or date of birth; address the reader as "Policyholder".
            """;

    private final ClaimDraftRepository drafts;
    private final ClaimRepository claims;
    private final DraftingAgent agent;
    private final AuditService audit;
    private final AuditTrailAgent auditAgent;
    private final ClaimCenterClient claimCenter;
    private final ObjectMapper mapper;

    ClaimDraftingService(ClaimDraftRepository drafts, ClaimRepository claims, DraftingAgent agent, AuditService audit,
                         AuditTrailAgent auditAgent, ClaimCenterClient claimCenter, ObjectMapper mapper) {
        this.drafts = drafts;
        this.claims = claims;
        this.agent = agent;
        this.audit = audit;
        this.auditAgent = auditAgent;
        this.claimCenter = claimCenter;
        this.mapper = mapper;
    }

    List<ClaimDraftEntity> list(String claimId) {
        return drafts.findByClaimIdOrderByCreatedAtDesc(claimId);
    }

    ClaimDraftEntity create(ClaimEntity claim, String kind, String requestedBy) {
        Map<String, DraftingAgent.Tool> tools = tools(claim);
        String template = ADVISORY_NOTE.equals(kind) ? advisoryTemplate(claim) : letterTemplate(claim);
        String task = ADVISORY_NOTE.equals(kind)
                ? "Draft a short advisory note for the adjuster: what the checks found and what to look at first."
                : "Draft the written acknowledgement of this claim to the policyholder, from the letter template.";
        DraftingAgent.Draft result = agent.draft(RULES + "\n" + task, tools, template);

        ClaimDraftEntity d = new ClaimDraftEntity();
        d.draftId = UUID.randomUUID().toString();
        d.claimId = claim.claimId;
        d.kind = kind;
        d.status = DRAFT;
        d.body = result.text();
        d.fromModel = result.fromModel();
        d.fallbackReason = result.fallbackReason() == null ? null : truncate(result.fallbackReason());
        d.toolCalls = truncate(String.join(",", result.toolCalls()));
        d.requestedBy = requestedBy;
        d.createdAt = Instant.now();
        drafts.save(d);
        audit.append(claim.claimId, List.of(auditAgent.record(claim.claimId, "DraftingAgent",
                "kind=" + kind + ";toolCalls=" + result.toolCalls() + ";inputSha256=" + AuditTrailAgent.sha256(tools.toString()),
                "draftId=" + d.draftId + ";outputSha256=" + AuditTrailAgent.sha256(d.body) + ";fromModel=" + d.fromModel
                        + ";fallback=" + Objects.toString(result.fallbackReason(), "none"), DRAFT)), requestedBy);
        return d;
    }

    /** A person accepts it, edited or not; only then does it become the note or the letter. */
    ClaimDraftEntity accept(ClaimDraftEntity d, String text, String who) {
        String body = text == null || text.isBlank() ? d.body : text.strip();
        List<String> problems = DraftGuard.personalData(body);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Remove personal data before accepting: the text " + String.join(", ", problems));
        }
        boolean edited = !body.equals(d.body);
        if (!d.claimId.startsWith("REJ-")) {
            try {
                if (ADVISORY_NOTE.equals(d.kind)) {
                    claimCenter.addNote(d.claimId, new CcNote(null, "AI advisory", "Advisory note, reviewed by " + who, body, null));
                } else {
                    claimCenter.attachDocument(d.claimId, new ClaimAttachment("acknowledgement-letter-reviewed.txt", "text/plain", body));
                }
            } catch (RuntimeException e) {
                throw new ClaimsSystemUnavailableException();
            }
        }
        d.body = body;
        close(d, ACCEPTED, who);
        audit.append(d.claimId, List.of(auditAgent.record(d.claimId, "DraftAccepted", "draftId=" + d.draftId + ";kind=" + d.kind + ";edited=" + edited,
                "outputSha256=" + AuditTrailAgent.sha256(body) + ";acceptedBy=" + who, ACCEPTED)), who);
        return d;
    }

    ClaimDraftEntity discard(ClaimDraftEntity d, String who) {
        close(d, DISCARDED, who);
        audit.append(d.claimId, List.of(auditAgent.record(d.claimId, "DraftDiscarded", "draftId=" + d.draftId + ";kind=" + d.kind,
                "discardedBy=" + who, DISCARDED)), who);
        return d;
    }

    private void close(ClaimDraftEntity d, String status, String who) {
        d.status = status;
        d.decidedBy = who;
        d.decidedAt = Instant.now();
        drafts.save(d);
    }

    /** The read-only tools, by name. There is deliberately none that writes: each only returns text computed here. */
    Map<String, DraftingAgent.Tool> tools(ClaimEntity claim) {
        JsonNode analysis = analysis(claim);
        JsonNode reserve = analysis.path("branchA").path("reserve");
        JsonNode damage = analysis.path("branchA").path("damageAssessment");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("claimId", claim.claimId);
        summary.put("status", claim.status);
        summary.put("lossDate", String.valueOf(claim.incidentDate));
        summary.put("state", claim.state);
        summary.put("severity", claim.severityGate);
        summary.put("damagedArea", damage.path("damageType").asText(null));
        summary.put("recommendedReserve", reserve.path("recommendedReserve").asText(null));
        summary.put("deductible", reserve.path("deductible").asText(null));
        summary.put("intakeFlags", claim.inputFlags);
        summary.put("gateFlags", claim.gateFlags);
        JsonNode deadline = analysis.path("branchB").path("deadline");
        Map<String, DraftingAgent.Tool> tools = new LinkedHashMap<>();
        tools.put("claim_summary", new DraftingAgent.Tool("The claim's status, severity, flags and recommended reserve, with no personal data", json(summary)));
        tools.put("why_panel", new DraftingAgent.Tool("The six factors behind the recommendation", Objects.toString(claim.explanationJson, "{}")));
        tools.put("claim_clock", new DraftingAgent.Tool("Regulatory dates and citations for this claim", deadline.isMissingNode() ? "{}" : deadline.toString()));
        tools.put("coverage_result", new DraftingAgent.Tool("The result of the coverage check", analysis.path("branchA").path("coverage").toString()));
        tools.put("letter_template", new DraftingAgent.Tool("The standard acknowledgement letter for this claim", letterTemplate(claim)));
        return tools;
    }

    private JsonNode analysis(ClaimEntity claim) {
        try {
            return claim.analysisJson == null ? mapper.createObjectNode() : mapper.readTree(claim.analysisJson);
        } catch (java.io.IOException e) {
            return mapper.createObjectNode();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    private static String advisoryTemplate(ClaimEntity claim) {
        return "Status " + claim.status + ", severity " + claim.severityGate + ". Intake flags: " + Objects.toString(claim.inputFlags, "none")
                + ". Consistency flags: " + Objects.toString(claim.gateFlags, "none") + ". See the WHY panel.\n\nAdvisory only: the adjuster decides.";
    }

    /** The existing template, with no name: the engine holds none. */
    private String letterTemplate(ClaimEntity claim) {
        DeadlineResult deadline = null;
        try {
            JsonNode node = analysis(claim).path("branchB").path("deadline");
            deadline = node.isObject() ? mapper.treeToValue(node, DeadlineResult.class) : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // no clock to quote: the fixed fallback below
        }
        if (deadline == null) {
            deadline = new DeadlineResult(claim.state, claim.acknowledgeDue, null, null, claim.acknowledgeDue, claim.decisionDue, null, null, null, null, null, null, null);
        }
        boolean represented = claim.inputFlags != null && claim.inputFlags.contains(IntakeReviewService.ATTORNEY_REPRESENTED);
        FNOLPayload letterFor = new FNOLPayload(claim.policyNumber, claim.incidentDate, claim.state, "", null, null, null, List.of(), "API");
        return AcknowledgementLetter.write(claim.claimId, letterFor, deadline, List.of(), represented,
                claim.createdAt == null ? LocalDate.now() : LocalDate.ofInstant(claim.createdAt, ZoneOffset.UTC));
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
