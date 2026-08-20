package com.guidewire.fnol.api;
import com.guidewire.fnol.common.Models.*;import com.guidewire.fnol.orchestrator.*;import com.guidewire.fnol.api.persistence.*;import jakarta.servlet.http.*;import jakarta.validation.*;import org.springframework.http.*;import org.springframework.web.bind.MethodArgumentNotValidException;import org.springframework.web.bind.annotation.*;import java.time.*;import java.util.*;
@CrossOrigin(origins = "*") @RestController @RequestMapping("/api/v1")
public class FNOLController {
  private final FNOLOrchestrator orchestrator; private final ClaimRepository claims; private final AuditRepository audits;
  public FNOLController(FNOLOrchestrator orchestrator,ClaimRepository claims,AuditRepository audits){this.orchestrator=orchestrator;this.claims=claims;this.audits=audits;}
  @GetMapping("/health") Map<String,String> health(){return Map.of("status","UP");}
  @PostMapping("/fnol/intake") FNOLResponse intake(@Valid @RequestBody FNOLPayload payload){ FNOLResponse r=orchestrator.process(payload); claims.save(ClaimEntity.from(r,payload)); r.branchB().audit().forEach(a->audits.save(AuditEntity.from(a))); return r; }
  @GetMapping("/fnol/status/{claimId}") ResponseEntity<?> status(@PathVariable("claimId") String claimId){ return claims.findById(claimId).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(()->ResponseEntity.notFound().build()); }
}

@RestControllerAdvice class ApiExceptionHandler {
  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  ResponseEntity<ApiError> bad(Exception e,HttpServletRequest r){ return ResponseEntity.badRequest().body(new ApiError(Instant.now(),400,"VALIDATION_ERROR",e.getMessage(),r.getRequestURI())); }
  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> server(Exception e,HttpServletRequest r){ return ResponseEntity.status(502).body(new ApiError(Instant.now(),502,"EXTERNAL_OR_PROCESSING_ERROR","FNOL processing could not be completed",r.getRequestURI())); }
}
