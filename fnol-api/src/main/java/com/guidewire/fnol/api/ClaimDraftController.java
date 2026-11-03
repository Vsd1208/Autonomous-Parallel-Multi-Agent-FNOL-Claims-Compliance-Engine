package com.guidewire.fnol.api;

import com.guidewire.fnol.api.persistence.ClaimDraftEntity;
import com.guidewire.fnol.api.persistence.ClaimDraftRepository;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.guidewire.fnol.api.persistence.ClaimRepository;
import com.guidewire.fnol.api.security.Actor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import java.util.Set;

/** Drafts for a person to review. With fnol.drafting-agent.enabled off these endpoints do not exist. */
@RestController
@RequestMapping("/api/v1/fnol/{claimId}/drafts")
@ConditionalOnProperty(name = "fnol.drafting-agent.enabled", havingValue = "true")
class ClaimDraftController {

    record NewDraft(String kind) {}

    record Acceptance(String text) {}

    private final ClaimDraftingService service;
    private final ClaimDraftRepository drafts;
    private final ClaimRepository claims;

    ClaimDraftController(ClaimDraftingService service, ClaimDraftRepository drafts, ClaimRepository claims) {
        this.service = service;
        this.drafts = drafts;
        this.claims = claims;
    }

    @GetMapping
    List<ClaimDraftEntity> list(@PathVariable("claimId") String claimId) {
        return service.list(claimId);
    }

    @PostMapping
    ResponseEntity<?> create(@PathVariable("claimId") String claimId, @RequestBody NewDraft body) {
        ClaimEntity claim = claims.findById(claimId).orElse(null);
        if (claim == null) {
            return ResponseEntity.notFound().build();
        }
        if (body == null || !Set.of(ClaimDraftingService.ADVISORY_NOTE, ClaimDraftingService.ACKNOWLEDGEMENT_LETTER).contains(body.kind())) {
            throw new IllegalArgumentException("kind must be ADVISORY_NOTE or ACKNOWLEDGEMENT_LETTER");
        }
        return ResponseEntity.ok(service.create(claim, body.kind(), Actor.idOr("SERVICE")));
    }

    @PostMapping("/{draftId}/accept")
    @Transactional
    ResponseEntity<?> accept(@PathVariable("claimId") String claimId, @PathVariable("draftId") String draftId, @RequestBody Acceptance body) {
        return decide(claimId, draftId, (draft, who) -> service.accept(draft, body == null ? null : body.text(), who));
    }

    @PostMapping("/{draftId}/discard")
    @Transactional
    ResponseEntity<?> discard(@PathVariable("claimId") String claimId, @PathVariable("draftId") String draftId) {
        return decide(claimId, draftId, service::discard);
    }

    private ResponseEntity<?> decide(String claimId, String draftId, java.util.function.BiFunction<ClaimDraftEntity, String, ClaimDraftEntity> action) {
        ClaimDraftEntity draft = drafts.findById(draftId).filter(d -> d.claimId.equals(claimId)).orElse(null);
        if (draft == null) {
            return ResponseEntity.notFound().build();
        }
        Actor actor = Actor.current().orElse(null);
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "FORBIDDEN", "message", "A person must review a draft; a service credential cannot"));
        }
        if (!ClaimDraftingService.DRAFT.equals(draft.status)) {
            throw new InvalidStateException("This draft was already " + draft.status);
        }
        return ResponseEntity.ok(action.apply(draft, actor.id()));
    }
}
