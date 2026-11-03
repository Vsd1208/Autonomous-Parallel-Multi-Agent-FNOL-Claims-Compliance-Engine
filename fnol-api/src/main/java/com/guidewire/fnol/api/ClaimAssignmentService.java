package com.guidewire.fnol.api;

import com.guidewire.fnol.agents.AuditTrailAgent;
import com.guidewire.fnol.api.persistence.AppUserEntity;
import com.guidewire.fnol.api.persistence.AppUserRepository;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.api.security.ReserveAuthority;
import com.guidewire.fnol.common.Models.CcActivity;
import com.guidewire.fnol.common.Models.ClaimPatch;
import com.guidewire.fnol.common.Models.FNOLResponse;
import com.guidewire.fnol.enrichment.ClaimCenterClient;
import com.guidewire.fnol.orchestrator.FNOLOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Segments a claim and assigns it to a group's queue and, within the group, the person with the fewest open claims (whoever was
 * assigned least recently breaks a tie, so it goes round). A claim whose recommended reserve is above every group member's
 * authority also goes to a supervisor. Modelled on ClaimCenter's segmentation and assignment, not verified against a tenant.
 * Best effort and never decides anything: a failure here leaves the claim in its queue, unassigned.
 */
@Service
public class ClaimAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(ClaimAssignmentService.class);
    /** Statuses in which a claim still needs its assignee. */
    static final Set<String> OPEN_STATUSES = Set.of("HUMAN_REVIEW", "PENDING_ADVISORY_REVIEW", "NEEDS_MORE_INFO",
            "INVESTIGATION_REQUIRED", "INSPECTION_ASSIGNED", "PENDING_APPROVAL", "BLOCKED_COMPLIANCE");
    static final String SUBROGATION = "SUBROGATION";

    private final ClaimSegmenter segmenter;
    private final AppUserRepository users;
    private final ClaimRepository claims;
    private final ReserveAuthority authority;
    private final AuditService audit;
    private final AuditTrailAgent auditAgent;
    private final ClaimCenterClient claimCenter;

    public ClaimAssignmentService(ClaimSegmenter segmenter, AppUserRepository users, ClaimRepository claims,
                                  ReserveAuthority authority, AuditService audit, AuditTrailAgent auditAgent,
                                  ClaimCenterClient claimCenter) {
        this.segmenter = segmenter;
        this.users = users;
        this.claims = claims;
        this.authority = authority;
        this.audit = audit;
        this.auditAgent = auditAgent;
        this.claimCenter = claimCenter;
    }

    /** For a claim whose analysis has just been saved. */
    public void assign(FNOLResponse response) {
        try {
            Set<String> flags = new HashSet<>(response.inputFlags() == null ? List.of() : response.inputFlags());
            flags.addAll(response.gateFlags() == null ? List.of() : response.gateFlags());
            if (FNOLOrchestrator.recoveryLikely(response.branchB().subrogation())) {
                flags.add(SUBROGATION);
            }
            String policy = response.branchA().coverage().policyNumber();
            String line = policy != null && policy.toUpperCase(Locale.ROOT).startsWith("POL-HOME") ? "HOME" : "AUTO";
            BigDecimal reserve = response.branchA().reserve().recommendedReserve();
            assign(response.claimId(), new ClaimSegmenter.Input(line, response.explanation().severityGate(), flags, reserve));
        } catch (RuntimeException e) {
            log.warn("[Assignment] Claim {} stays unassigned: {}", response.claimId(), e.getMessage());
        }
    }

    /** Who gets it and who else must know. */
    private record Choice(String assignee, boolean noOneInGroup, boolean aboveGroup, String supervisor) {}

    void assign(String claimId, ClaimSegmenter.Input input) {
        if (!claims.existsById(claimId)) {
            return;
        }
        ClaimSegmenter.Segment segment = segmenter.segment(input);
        BigDecimal reserve = input.reserve();
        Choice choice = choose(claimId, segment, reserve);
        String supervisor = choice.supervisor();
        audit.append(claimId, List.of(auditAgent.record(claimId, "SegmentationAndAssignment",
                "line=" + input.line() + ";severity=" + input.severity() + ";flags=" + new java.util.TreeSet<>(input.flags()) + ";reserve=" + reserve,
                "segment=" + segment.segment() + ";why=" + segment.why() + ";group=" + segment.group() + ";assignedTo=" + choice.assignee()
                        + ";routedToSupervisor=" + supervisor, choice.assignee() == null ? "UNASSIGNED" : "ASSIGNED")), AuditService.SYSTEM_ACTOR);

        if (!claimId.startsWith("REJ-")) {
            try {
                claimCenter.patchClaim(claimId, new ClaimPatch(null, null, null, null, segment.segment(), choice.assignee(), segment.group()));
                if (choice.noOneInGroup()) {
                    claimCenter.addActivity(claimId, new CcActivity(null, "No one is available to take this claim",
                            "Nobody active holds a role in " + segment.group() + ", so this claim is unassigned. Please assign it.",
                            "high", LocalDate.now().plusDays(1), supervisor != null ? supervisor : "Supervisors", null, null));
                } else if (choice.aboveGroup()) {
                    claimCenter.addActivity(claimId, new CcActivity(null, "Reserve above the assigned group's authority",
                            "The recommended reserve of $" + reserve + " is above the authority of everyone in " + segment.group()
                                    + ". Please take or support this claim.", "high", LocalDate.now().plusDays(1),
                            supervisor != null ? supervisor : "Supervisors", null, null));
                }
            } catch (RuntimeException e) {
                log.warn("[Assignment] Claims system not told about {}'s assignment: {}", claimId, e.getMessage());
            }
        }
        log.info("[Assignment] {} -> {} ({}) assigned to {}", claimId, segment.segment(), segment.why(), choice.assignee());
    }

    /**
     * One at a time, so two claims arriving together cannot both pick the same "least busy" person. Calls to the claims system stay
     * outside this lock. Only the assignment columns are written, so a decision arriving meanwhile is not overwritten.
     */
    private synchronized Choice choose(String claimId, ClaimSegmenter.Segment segment, BigDecimal reserve) {
        List<AppUserEntity> members = users.findAll().stream()
                .filter(u -> u.active && segment.roles().contains(u.role)).toList();
        AppUserEntity assignee = members.stream()
                .min(Comparator.comparingLong((AppUserEntity u) -> claims.countByAssignedToAndStatusIn(u.username, OPEN_STATUSES))
                        .thenComparing(u -> lastAssigned(u.username))
                        .thenComparing(u -> u.username))
                .orElse(null);
        boolean noOneInGroup = members.isEmpty();
        boolean aboveGroup = !noOneInGroup && members.stream().noneMatch(u -> authority.allows(u.role, reserve));
        String username = assignee == null ? null : assignee.username;
        String supervisor = noOneInGroup || aboveGroup ? supervisorFor(reserve, username) : null;
        claims.assign(claimId, segment.segment(), segment.group(), username, Instant.now());
        return new Choice(username, noOneInGroup, aboveGroup, supervisor);
    }

    private Instant lastAssigned(String username) {
        return claims.findFirstByAssignedToOrderByAssignedAtDesc(username).map(c -> c.assignedAt).orElse(Instant.EPOCH);
    }

    /** A supervisor with room for this reserve and the fewest open claims, other than the assignee. */
    private String supervisorFor(BigDecimal reserve, String except) {
        return users.findAll().stream()
                .filter(u -> u.active && ReserveAuthority.SUPERVISOR.equals(u.role) && !u.username.equals(except)
                        && authority.allows(u.role, reserve))
                .min(Comparator.comparingLong((AppUserEntity u) -> claims.countByAssignedToAndStatusIn(u.username, OPEN_STATUSES))
                        .thenComparing(u -> u.username))
                .map(u -> u.username).orElse(null);
    }
}
