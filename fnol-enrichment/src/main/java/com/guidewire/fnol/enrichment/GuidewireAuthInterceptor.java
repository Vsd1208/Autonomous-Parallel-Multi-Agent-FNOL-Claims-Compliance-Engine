package com.guidewire.fnol.enrichment;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Adds {@code Authorization: Bearer <token>} to every Guidewire call when a token is configured
 * ({@code guidewire.auth.bearer-token}, env {@code GUIDEWIRE_AUTH_BEARER_TOKEN}). The mock needs none.
 *
 * <p>This is the seam for a real tenant. Guidewire Cloud uses OAuth2 tokens; fetching and refreshing them
 * (client credentials) is not built here: a production adapter would replace the static token with a token
 * provider. See docs/guidewire-integration.md, "Path to production".
 */
public class GuidewireAuthInterceptor implements ClientHttpRequestInterceptor {

    private final String token;

    public GuidewireAuthInterceptor(String token) {
        this.token = token == null ? "" : token.trim();
    }

    public boolean isActive() {
        return !token.isEmpty();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (isActive()) {
            request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return execution.execute(request, body);
    }
}
