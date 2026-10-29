package com.guidewire.fnol.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Requires header {@code X-Adjuster-Token} equal to {@code fnol.security.adjuster-token} on the
 * adjuster-only endpoints: review, audit, audit PDF, explanation, evidence documents and the claim queue.
 *
 * <p>Fails closed: if no token is configured, every protected request is rejected. This is a
 * shared-secret prototype control, not user authentication; production needs per-adjuster
 * identity (e.g. OIDC) so adjusterId comes from the token rather than the request body.
 */
public class AdjusterTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Adjuster-Token";

    private static final Pattern PROTECTED = Pattern.compile(
            "^/api/v1/(inspectors|ai/(metrics|feedback)|fnol/(queue|[^/]+/(review|claimcenter|claimcenter-sync|audit|audit/pdf|explanation|analysis|documents"
                    + "|documents/[^/]+/content|inspections|inspections/[^/]+/(report|cancel)|upload-requests"
                    + "|upload-requests/[^/]+/cancel)))/?$");

    private final byte[] expected;

    public AdjusterTokenFilter(String adjusterToken) {
        this.expected = adjusterToken == null ? new byte[0] : adjusterToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // CORS preflight carries no custom headers; it is answered by the CORS filter.
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !PROTECTED.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        boolean ok = expected.length > 0 && supplied != null
                && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8));
        if (ok) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"timestamp\":\"" + Instant.now() + "\",\"status\":401,"
                + "\"error\":\"UNAUTHORIZED\",\"message\":\"A valid " + HEADER + " header is required\","
                + "\"path\":\"" + request.getRequestURI().replace("\"", "") + "\"}");
    }
}
