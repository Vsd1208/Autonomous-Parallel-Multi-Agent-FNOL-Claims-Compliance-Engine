package com.guidewire.fnol.mock;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.*;import org.springframework.boot.test.context.*;import org.springframework.boot.test.web.client.*;import org.springframework.http.*;import java.util.*;import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockGuidewireApplicationTests {
  @Autowired TestRestTemplate rest;
  @Test void returnsSeededPolicyCoverageAndHistory(){ assertThat(rest.getForEntity("/pc/policies/POL-AUTO-112233",String.class).getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(rest.getForEntity("/pc/policies/POL-AUTO-112233/coverages",String.class).getBody()).contains("COLLISION"); assertThat(rest.getForEntity("/pc/policies/POL-AUTO-112233/history",String.class).getBody()).contains("HIST"); }
  @Test void createsAndRetrievesClaim(){ var body=Map.of("policyNumber","POL-AUTO-112233","incidentDate","2026-08-16","state","CA","estimatedDamage",4200,"reserve",4830); var created=rest.postForEntity("/cc/claims",body,String.class); assertThat(created.getBody()).contains("CLM-2026"); }
}
