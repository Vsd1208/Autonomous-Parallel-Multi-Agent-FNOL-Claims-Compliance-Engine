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
 * adjuster-only endpoints: intake from the workspace, claim status, review, audit, audit PDF, explanation, evidence
 * documents and the claim queue. Policyholders use the separate portal API ({@code /api/v1/portal}), which reports a
 * loss and shows a claimant-safe status, and the one-time upload links ({@code /api/v1/public/upload}).
 *
 * <p>People sign in ({@link AuthService}) and send {@code Authorization: Bearer}: their role decides what they may do and
 * their identity replaces any name in the request. {@code X-Adjuster-Token} is now the service credential, for integrations
 * and scripts with no person behind them; it is not in any browser bundle. Fails closed: with neither configured, every
 * protected request is rejected.
 */
public class AdjusterTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Adjuster-Token";

    private static final Pattern PROTECTED = Pattern.compile(
            "^/api/v1/(auth/(me|logout)|inspectors|ai/(metrics|feedback)|fnol/(queue|intake|report|status/[^/]+|[^/]+/(review|claimcenter|claimcenter-sync|audit|audit/pdf|explanation|analysis|documents"
                    + "|documents/[^/]+/content|inspections|inspections/[^/]+/(report|cancel)|upload-requests"
                    + "|upload-requests/[^/]+/cancel|reserve-approvals|reserve-approvals/[^/]+/decide|drafts|drafts/[^/]+/(accept|discard))))/?$");

    /** The inspector's report is the only change an inspector may make; only an inspector may make it. */
    private static final Pattern INSPECTOR_REPORT = Pattern.compile("^/api/v1/fnol/[^/]+/inspections/[^/]+/report/?$");
    private static final Pattern GOVERNANCE = Pattern.compile("^/api/v1/ai/.*$");

    private final byte[] expected;
    private final AuthService auth;

    public AdjusterTokenFilter(String adjusterToken) {
        this(adjusterToken, null);
    }

    public AdjusterTokenFilter(String adjusterToken, AuthService auth) {
        this.expected = adjusterToken == null ? new byte[0] : adjusterToken.getBytes(StandardCharsets.UTF_8);
        this.auth = auth;
    }

    static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
    }

    /** Null when allowed, else why not. Separation of duties: whoever writes the inspection report cannot decide the claim. */
    static String refusal(Actor actor, HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean report = INSPECTOR_REPORT.matcher(path).matches();
        boolean read = "GET".equalsIgnoreCase(request.getMethod());
        if (AuthService.INSPECTOR.equals(actor.role())) {
            if (report || path.endsWith("/auth/logout") || (read && !GOVERNANCE.matcher(path).matches())) return null;
            return "Inspectors report on inspections; they do not decide claims or see governance figures";
        }
        return report ? "Only the assigned inspector can file an inspection report" : null;
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
        String bearer = bearerToken(request);
        if (bearer != null && auth != null) {
            Actor actor = auth.resolve(bearer).orElse(null);
            if (actor == null) {
                refuse(request, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Your session has ended; sign in again");
                return;
            }
            String refusal = refusal(actor, request);
            if (refusal != null) {
                refuse(request, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", refusal);
                return;
            }
            request.setAttribute(Actor.REQUEST_ATTRIBUTE, actor);
            chain.doFilter(request, response);
            return;
        }
        String supplied = request.getHeader(HEADER);
        boolean ok = expected.length > 0 && supplied != null
                && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8));
        if (ok) {
            chain.doFilter(request, response);
            return;
        }
        refuse(request, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "A valid " + HEADER + " header is required");
    }

    private static void refuse(HttpServletRequest request, HttpServletResponse response, int status, String error,
                               String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"timestamp\":\"" + Instant.now() + "\",\"status\":" + status + ","
                + "\"error\":\"" + error + "\",\"message\":\"" + message + "\","
                + "\"path\":\"" + request.getRequestURI().replace("\"", "") + "\"}");
    }
}
