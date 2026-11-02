package com.guidewire.fnol.enrichment;

import com.guidewire.fnol.common.Models.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The adapter against a stub of what it assumes. This checks the code does what is written, not that a real tenant agrees:
 * that needs a sandbox (tools/guidewire-check/check_sandbox.py).
 */
class GuidewireCloudClaimCenterClientTest {

    private static final String BASE = "https://tenant.example/api";
    private final RestTemplate rest = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(false).build();
    private final GuidewireCloudClaimCenterClient client = new GuidewireCloudClaimCenterClient(rest, BASE,
            "https://auth.example/token", "client", "secret");

    private static FNOLPayload fnol() {
        return new FNOLPayload("POL-AUTO-112233", LocalDate.of(2026, 10, 20), "CA", "Rear-ended", "Avery Johnson", null,
                null, List.of(), "WEB");
    }

    private void token() {
        server.expect(requestTo("https://auth.example/token")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string("grant_type=client_credentials"))
                .andRespond(withSuccess("{\"access_token\":\"t1\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("Draft then submit, with an OAuth2 token reused across calls; the id is kept apart from the open number")
    void draftThenSubmit() {
        token();
        server.expect(requestTo(BASE + "/claim/v1/claims")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer t1"))
                .andExpect(jsonPath("$.data.attributes.policyNumber").value("POL-AUTO-112233"))
                .andExpect(jsonPath("$.data.attributes.lossDate").value("2026-10-20T00:00:00.000Z"))
                .andRespond(withSuccess("{\"data\":{\"attributes\":{\"id\":\"cc:1\",\"claimNumber\":\"DRAFT-9\"}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/claim/v1/claims/cc:1/submit")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer t1"))
                .andRespond(withSuccess("{\"data\":{\"attributes\":{\"id\":\"cc:1\",\"claimNumber\":\"235-53-1\"}}}", MediaType.APPLICATION_JSON));

        ClaimCreateResponse open = client.submitFnol(fnol());
        assertThat(open.claimId()).isEqualTo("cc:1");
        assertThat(open.claimNumber()).isEqualTo("235-53-1");
        assertThat(open.status()).isEqualTo("OPEN");
        server.verify();
    }

    @Test
    @DisplayName("Anything without a verified mapping refuses and says so, rather than guessing")
    void refusesWhatIsNotMapped() {
        assertThatThrownBy(() -> client.addExposure("cc:1", new CcExposure(null, "VehicleDamage", "COLLISION", null, null, null)))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("no verified Cloud API mapping");
        assertThatThrownBy(() -> client.recordDecision("cc:1", new ClaimUpdateRequest("OPEN", null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("A token request that fails stops the call instead of sending it without one")
    void noTokenNoCall() {
        server.expect(requestTo("https://auth.example/token")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.submitFnol(fnol())).isInstanceOf(RuntimeException.class);
        server.verify();
    }
}
