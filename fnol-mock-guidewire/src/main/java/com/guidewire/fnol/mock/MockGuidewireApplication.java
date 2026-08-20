package com.guidewire.fnol.mock;

import com.guidewire.fnol.common.Models.*;
import org.springframework.boot.*;import org.springframework.boot.autoconfigure.*;import org.springframework.http.*;import org.springframework.web.bind.annotation.*;import org.springframework.web.server.ResponseStatusException;
import java.math.*;import java.time.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;

@SpringBootApplication
public class MockGuidewireApplication { public static void main(String[] args){SpringApplication.run(MockGuidewireApplication.class,args);} }

@RestController
class PolicyCenterController {
  private final Map<String, Policy> policies = Seed.policies();
  @GetMapping("/pc/policies/{policyNumber}") Policy policy(@PathVariable("policyNumber") String policyNumber){ return Optional.ofNullable(policies.get(policyNumber)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Synthetic policy not found")); }
  @GetMapping("/pc/policies/{policyNumber}/coverages") List<Coverage> coverages(@PathVariable("policyNumber") String policyNumber){ return policy(policyNumber).coverages(); }
  @GetMapping("/pc/policies/{policyNumber}/history") List<ClaimHistory> history(@PathVariable("policyNumber") String policyNumber){ policy(policyNumber); return Seed.history(policyNumber); }
}

@RestController
class ClaimCenterController {
  private final AtomicInteger sequence = new AtomicInteger(1); private final Map<String, Claim> claims = new ConcurrentHashMap<>();
  @PostMapping("/cc/claims") ClaimCreateResponse create(@RequestBody ClaimCreateRequest request){
    String id = "CLM-2026-%06d".formatted(sequence.getAndIncrement());
    claims.put(id, new Claim(id,"OPEN",request.policyNumber(),request.incidentDate(),request.state(),request.estimatedDamage(),request.reserve()));
    return new ClaimCreateResponse(id,"OPEN");
  }
  @GetMapping("/cc/claims/{claimId}") Claim get(@PathVariable("claimId") String claimId){ return Optional.ofNullable(claims.get(claimId)).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Synthetic claim not found")); }
}

final class Seed {
  static Map<String, Policy> policies(){ Map<String,Policy> p=new HashMap<>();
    p.put("POL-AUTO-112233", auto("POL-AUTO-112233","ACTIVE","CA","Avery Johnson","2025 Toyota Camry"));
    p.put("POL-AUTO-223344", auto("POL-AUTO-223344","ACTIVE","NY","Morgan Lee","2023 Honda CR-V"));
    p.put("POL-HOME-334455", new Policy("POL-HOME-334455","HOME","ACTIVE",LocalDate.of(2026,1,1),LocalDate.of(2027,1,1),"Riley Smith","TX","Single family property",List.of(new Coverage("DWELLING",bd("350000"),bd("2500")),new Coverage("PERSONAL_PROPERTY",bd("100000"),bd("1000")))));
    return p; }
  static Policy auto(String n,String s,String st,String insured,String risk){ return new Policy(n,"AUTO",s,LocalDate.of(2026,1,1),LocalDate.of(2027,1,1),insured,st,risk,List.of(new Coverage("COLLISION",bd("50000"),bd("1000")),new Coverage("COMPREHENSIVE",bd("40000"),bd("500")),new Coverage("LIABILITY",bd("100000"),bd("0")))); }
  static List<ClaimHistory> history(String p){ return List.of(new ClaimHistory("HIST-"+p.substring(p.length()-4)+"-01",LocalDate.of(2024,5,12),"CLOSED",bd("1850"),"Synthetic prior glass loss"),new ClaimHistory("HIST-"+p.substring(p.length()-4)+"-02",LocalDate.of(2025,3,8),"CLOSED",bd("0"),"Synthetic inquiry only")); }
  static BigDecimal bd(String v){return new BigDecimal(v);}
}
