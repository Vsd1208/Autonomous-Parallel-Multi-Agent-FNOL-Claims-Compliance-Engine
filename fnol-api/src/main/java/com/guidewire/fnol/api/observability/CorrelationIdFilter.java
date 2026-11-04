package com.guidewire.fnol.api.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gives each request a correlation ID for the logs and returns it. A caller's ID is kept only when it looks like one, so a header cannot
 * write arbitrary text into the logs. The claim ID in the path, a business key and not personal data, is added too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-ID";
    public static final String CORRELATION_ID = "correlationId";
    public static final String CLAIM_ID = "claimId";

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final Pattern CLAIM_PATH = Pattern.compile("^/api/v1/fnol/(CLM-[A-Za-z0-9-]{1,40})(/.*)?$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String id = supplied != null && SAFE_ID.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
        MDC.put(CORRELATION_ID, id);
        Matcher claim = CLAIM_PATH.matcher(request.getRequestURI());
        if (claim.matches()) {
            MDC.put(CLAIM_ID, claim.group(1));
        }
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CORRELATION_ID);
            MDC.remove(CLAIM_ID);
        }
    }
}
