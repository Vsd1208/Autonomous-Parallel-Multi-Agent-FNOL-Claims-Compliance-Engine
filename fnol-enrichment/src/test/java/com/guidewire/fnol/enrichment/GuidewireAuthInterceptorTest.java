package com.guidewire.fnol.enrichment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GuidewireAuthInterceptorTest {

    private static final String POLICY_JSON = "{\"policyNumber\":\"POL-AUTO-112233\",\"status\":\"ACTIVE\"}";

    @Test
    @DisplayName("A configured token is sent as a bearer token on Guidewire calls")
    void sendsBearerToken() {
        RestTemplate rest = new RestTemplate();
        rest.getInterceptors().add(new GuidewireAuthInterceptor(" secret-token "));
        MockRestServiceServer server = MockRestServiceServer.createServer(rest);
        server.expect(requestTo("https://tenant.example/pc/policies/POL-AUTO-112233"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret-token"))
                .andRespond(withSuccess(POLICY_JSON, MediaType.APPLICATION_JSON));

        new MockPolicyCenterClient(rest, "https://tenant.example").getPolicy("POL-AUTO-112233");
        server.verify();
    }

    @Test
    @DisplayName("Without a token no Authorization header is sent (the mock needs none)")
    void noTokenNoHeader() {
        RestTemplate rest = new RestTemplate();
        rest.getInterceptors().add(new GuidewireAuthInterceptor(""));
        MockRestServiceServer server = MockRestServiceServer.createServer(rest);
        server.expect(requestTo("http://localhost:9090/pc/policies/POL-AUTO-112233"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess(POLICY_JSON, MediaType.APPLICATION_JSON));

        new MockPolicyCenterClient(rest, "http://localhost:9090").getPolicy("POL-AUTO-112233");
        server.verify();
    }
}
