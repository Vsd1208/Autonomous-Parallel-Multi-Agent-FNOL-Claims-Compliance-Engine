package com.guidewire.fnol.enrichment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * RestTemplate for Guidewire (PolicyCenter / ClaimCenter) calls. Marked {@code @Primary} so it is the
 * default RestTemplate; other clients (e.g. Gemini) must ask for their own bean by qualifier.
 *
 * <p>Built on the JDK HttpClient (HTTP/1.1), which supports PATCH: the claim API updates a claim with PATCH, which
 * HttpURLConnection cannot send. Connect timeout 2 s, read timeout 3 s.
 */
@Configuration
class ClientConfig {

    @Bean
    @Primary
    RestTemplate restTemplate(RestTemplateBuilder b,
                              @Value("${guidewire.auth.bearer-token:}") String bearerToken) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        return b.requestFactory(() -> factory)
                .additionalInterceptors(new GuidewireAuthInterceptor(bearerToken))
                .build();
    }
}
