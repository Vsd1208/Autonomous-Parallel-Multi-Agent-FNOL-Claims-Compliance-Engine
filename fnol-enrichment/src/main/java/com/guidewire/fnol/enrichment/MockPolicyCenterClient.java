package com.guidewire.fnol.enrichment;
import com.guidewire.fnol.common.Models.*;import org.springframework.beans.factory.annotation.*;import org.springframework.context.annotation.*;import org.springframework.stereotype.*;import org.springframework.web.client.*;import java.util.*;
@Component @Profile("!guidewire")
public class MockPolicyCenterClient implements PolicyCenterClient {
  private final RestTemplate rest; private final String baseUrl;
  public MockPolicyCenterClient(RestTemplate rest,@Value("${mock-guidewire.base-url:http://localhost:9090}") String baseUrl){this.rest=rest;this.baseUrl=baseUrl;}
  public Policy getPolicy(String policyNumber){ return rest.getForObject(baseUrl+"/pc/policies/{policyNumber}",Policy.class,policyNumber); }
  public List<Coverage> getCoverages(String policyNumber){ Coverage[] c=rest.getForObject(baseUrl+"/pc/policies/{policyNumber}/coverages",Coverage[].class,policyNumber); return List.of(Objects.requireNonNullElse(c,new Coverage[0])); }
  public List<ClaimHistory> getPolicyHistory(String policyNumber){ ClaimHistory[] h=rest.getForObject(baseUrl+"/pc/policies/{policyNumber}/history",ClaimHistory[].class,policyNumber); return List.of(Objects.requireNonNullElse(h,new ClaimHistory[0])); }
}
