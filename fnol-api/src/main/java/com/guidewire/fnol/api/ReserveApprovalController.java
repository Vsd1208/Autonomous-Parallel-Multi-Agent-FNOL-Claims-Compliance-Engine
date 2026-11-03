package com.guidewire.fnol.api;

import com.guidewire.fnol.api.persistence.ReserveApprovalEntity;
import com.guidewire.fnol.api.persistence.ReserveApprovalRepository;
import com.guidewire.fnol.api.security.Actor;
import com.guidewire.fnol.common.Models.AdjusterReviewRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Reserves waiting for someone with enough authority. */
@RestController
@RequestMapping("/api/v1/fnol/{claimId}/reserve-approvals")
class ReserveApprovalController {

    record ApprovalDecision(boolean approve, String note) {}

    /** An approval as the workspace shows it, including whether the person asking could act on it. */
    record ApprovalView(ReserveApprovalEntity approval, boolean youCanDecide, String whyNot) {}

    private final ReserveApprovalRepository approvals;
    private final ReserveApprovalService service;
    private final FNOLController decisions;

    ReserveApprovalController(ReserveApprovalRepository approvals, ReserveApprovalService service, FNOLController decisions) {
        this.approvals = approvals;
        this.service = service;
        this.decisions = decisions;
    }

    @GetMapping
    List<ApprovalView> list(@PathVariable("claimId") String claimId) {
        Actor actor = Actor.current().orElse(null);
        return approvals.findByClaimIdOrderByRequestedAtDesc(claimId).stream().map(a -> {
            String whyNot = ReserveApprovalService.PENDING.equals(a.status) ? service.refusal(a, actor) : "Already decided";
            return new ApprovalView(a, whyNot == null, whyNot);
        }).toList();
    }

    @PostMapping("/{approvalId}/decide")
    @Transactional
    ResponseEntity<?> decide(@PathVariable("claimId") String claimId, @PathVariable("approvalId") String approvalId,
                             @RequestBody ApprovalDecision body) {
        ReserveApprovalEntity a = approvals.findById(approvalId).filter(x -> x.claimId.equals(claimId)).orElse(null);
        if (a == null) {
            return ResponseEntity.notFound().build();
        }
        if (!ReserveApprovalService.PENDING.equals(a.status)) {
            throw new InvalidStateException("This approval was already " + a.status);
        }
        Actor actor = Actor.current().orElse(null);
        String refusal = service.refusal(a, actor);
        if (refusal != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "FORBIDDEN", "message", refusal));
        }
        if (!body.approve()) {
            service.decline(a, actor, body.note());
            return ResponseEntity.ok(Map.of("claimId", claimId, "status", a.previousStatus, "approval", ReserveApprovalService.DECLINED));
        }
        service.approve(a, actor, body.note());
        // The held decision is applied as it was made, recorded under the person who made it; the audit chain names the approver.
        String notes = (a.notes == null || a.notes.isBlank() ? "" : a.notes + " ") + "Reserve of $" + a.amount + " approved by " + actor.id() + ".";
        AdjusterReviewRequest held = new AdjusterReviewRequest(a.decision, a.requestedBy, notes, a.adjustedReserve,
                a.overrideReason, a.confirmedDamageType, a.finalRepairCost, a.repairCostSource, a.handlingSeconds, a.advisoryRating);
        return decisions.apply(claimId, held, false);
    }
}
