package com.guidewire.fnol.enrichment;
import org.springframework.boot.web.client.*;import org.springframework.context.annotation.*;import org.springframework.http.client.*;import org.springframework.web.client.*;import java.time.*;
@Configuration class ClientConfig { @Bean RestTemplate restTemplate(RestTemplateBuilder b){ return b.setConnectTimeout(Duration.ofSeconds(2)).setReadTimeout(Duration.ofSeconds(3)).requestFactory(SimpleClientHttpRequestFactory::new).build(); } }
