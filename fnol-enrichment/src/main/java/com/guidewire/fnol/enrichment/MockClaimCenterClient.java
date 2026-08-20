package com.guidewire.fnol.enrichment;
import com.guidewire.fnol.common.Models.*;import org.springframework.beans.factory.annotation.*;import org.springframework.context.annotation.*;import org.springframework.stereotype.*;import org.springframework.web.client.*;
@Component @Profile("!guidewire")
public class MockClaimCenterClient implements ClaimCenterClient {
  private final RestTemplate rest; private final String baseUrl;
  public MockClaimCenterClient(RestTemplate rest,@Value("${mock-guidewire.base-url:http://localhost:9090}") String baseUrl){this.rest=rest;this.baseUrl=baseUrl;}
  public ClaimCreateResponse createClaim(ClaimCreateRequest request){ return rest.postForObject(baseUrl+"/cc/claims",request,ClaimCreateResponse.class); }
  public Claim getClaim(String claimId){ return rest.getForObject(baseUrl+"/cc/claims/{claimId}",Claim.class,claimId); }
}
