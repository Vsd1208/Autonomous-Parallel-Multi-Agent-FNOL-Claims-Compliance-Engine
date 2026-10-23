package com.guidewire.fnol.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS runs first so that even a 401 from the token filter reaches the browser readable;
 * the adjuster-token filter runs next.
 */
@Configuration
public class SecurityConfig {

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter(
            @Value("${fnol.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", AdjusterTokenFilter.HEADER));
        cors.setExposedHeaders(List.of("Content-Disposition"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);

        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    FilterRegistrationBean<AdjusterTokenFilter> adjusterTokenFilter(
            @Value("${fnol.security.adjuster-token:}") String adjusterToken) {
        FilterRegistrationBean<AdjusterTokenFilter> bean =
                new FilterRegistrationBean<>(new AdjusterTokenFilter(adjusterToken));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return bean;
    }
}
