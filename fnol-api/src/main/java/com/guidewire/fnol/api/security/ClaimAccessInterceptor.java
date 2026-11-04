package com.guidewire.fnol.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies {@link ClaimAccessPolicy} to every claim route, so a new endpoint cannot forget it: a route not named here counts as internal,
 * which an inspector is refused. The claim comes from the path, and the caller from the session.
 */
@Component
class ClaimAccessInterceptor implements HandlerInterceptor {

    private static final Pattern CLAIM_ROUTE = Pattern.compile("^/api/v1/fnol/(?:status/)?([^/]+)(/.*)?$");
    private static final Pattern NOT_A_CLAIM = Pattern.compile("^(queue|intake|report)$");
    private static final Pattern GOVERNANCE_ROUTE = Pattern.compile("^/api/v1/ai/.*$");
    private static final Pattern ADMIN_ROUTE = Pattern.compile("^/api/v1/admin/.*$");
    /** Routes an inspector may reach, on an assigned claim only. */
    private static final Map<String, Pattern> BASIC = Map.of(
            "GET", Pattern.compile("^/(inspection-view|inspections|documents|documents/[^/]+/content)/?$"),
            "POST", Pattern.compile("^/inspections/[^/]+/report/?$"));
    private static final Pattern DECISION = Pattern.compile("^/(review|reserve-approvals/[^/]+/decide)/?$");

    private final ClaimAccessPolicy policy;

    ClaimAccessInterceptor(ClaimAccessPolicy policy) {
        this.policy = policy;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (ADMIN_ROUTE.matcher(path).matches()) {
            policy.requirePermission(Actor.caller(), ClaimAccessPolicy.Permission.ADMINISTER);
            return true;
        }
        if (GOVERNANCE_ROUTE.matcher(path).matches()) {
            policy.requirePermission(Actor.caller(), ClaimAccessPolicy.Permission.GOVERNANCE);
            return true;
        }
        Matcher claim = CLAIM_ROUTE.matcher(path);
        if (!claim.matches() || NOT_A_CLAIM.matcher(claim.group(1)).matches()) {
            return true;
        }
        String rest = claim.group(2) == null ? "/" : claim.group(2);
        policy.requireClaimAccess(Actor.caller(), claim.group(1), permission(request.getMethod(), rest));
        return true;
    }

    static ClaimAccessPolicy.Permission permission(String method, String rest) {
        Pattern basic = BASIC.get(method.toUpperCase(java.util.Locale.ROOT));
        if (basic != null && basic.matcher(rest).matches()) {
            return ClaimAccessPolicy.Permission.CLAIM_BASIC;
        }
        if (DECISION.matcher(rest).matches()) {
            return ClaimAccessPolicy.Permission.CLAIM_DECIDE;
        }
        return "GET".equalsIgnoreCase(method) ? ClaimAccessPolicy.Permission.CLAIM_INTERNAL : ClaimAccessPolicy.Permission.CLAIM_WORK;
    }

    @Configuration
    static class Registration implements WebMvcConfigurer {
        private final ClaimAccessInterceptor interceptor;

        Registration(ClaimAccessInterceptor interceptor) {
            this.interceptor = interceptor;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(interceptor).addPathPatterns("/api/v1/fnol/**", "/api/v1/ai/**", "/api/v1/admin/**");
        }
    }
}
