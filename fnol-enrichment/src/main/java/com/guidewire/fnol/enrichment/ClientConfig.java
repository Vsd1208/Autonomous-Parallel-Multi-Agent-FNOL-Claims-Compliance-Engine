package com.guidewire.fnol.enrichment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * RestTemplate for Guidewire (PolicyCenter / ClaimCenter) calls. Marked {@code @Primary} so it is the
 * default RestTemplate; other clients (e.g. Gemini) must ask for their own bean by qualifier.
 */
@Configuration
class ClientConfig {

    @Bean
    @Primary
    RestTemplate restTemplate(RestTemplateBuilder b,
                              @Value("${guidewire.auth.bearer-token:}") String bearerToken) {
        return b.setConnectTimeout(Duration.ofSeconds(2))
                .setReadTimeout(Duration.ofSeconds(3))
                .requestFactory(SimpleClientHttpRequestFactory::new)
                .additionalInterceptors(new GuidewireAuthInterceptor(bearerToken))
                .build();
    }
}
