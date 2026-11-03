package com.guidewire.fnol.api.security;

import com.guidewire.fnol.api.persistence.AppUserEntity;
import com.guidewire.fnol.api.persistence.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * How much reserve each role may post, modelled on ClaimCenter's authority-limit concept (not verified against a tenant). The
 * amounts are synthetic prototype values, set by configuration. An inspector has no authority over reserves, and a service
 * credential, having no person behind it, gets the lowest limit that decides claims and can never approve.
 */
@Component
public class ReserveAuthority {

    public static final String SENIOR_ADJUSTER = "SENIOR_ADJUSTER";
    public static final String SUPERVISOR = "SUPERVISOR";

    private final BigDecimal adjuster;
    private final BigDecimal senior;
    private final BigDecimal supervisor;
    private final AppUserRepository users;

    public ReserveAuthority(@Value("${fnol.authority.adjuster:10000}") BigDecimal adjuster,
                            @Value("${fnol.authority.senior-adjuster:50000}") BigDecimal senior,
                            @Value("${fnol.authority.supervisor:#{null}}") BigDecimal supervisor,
                            AppUserRepository users) {
        this.adjuster = adjuster;
        this.senior = senior;
        this.supervisor = supervisor;
        this.users = users;
    }

    /** True if this role may post this amount. */
    public boolean allows(String role, BigDecimal amount) {
        if (amount == null) return true;
        return switch (role == null ? "" : role) {
            case SUPERVISOR -> supervisor == null || amount.compareTo(supervisor) <= 0;
            case SENIOR_ADJUSTER -> amount.compareTo(senior) <= 0;
            case AuthService.INSPECTOR -> false;
            default -> amount.compareTo(adjuster) <= 0;
        };
    }

    /** The role's limit, for showing; null means unlimited. */
    public BigDecimal limitOf(String role) {
        return switch (role == null ? "" : role) {
            case SUPERVISOR -> supervisor;
            case SENIOR_ADJUSTER -> senior;
            case AuthService.INSPECTOR -> BigDecimal.ZERO;
            default -> adjuster;
        };
    }

    /** The active people who could approve this amount, other than the person asking, lowest authority first. */
    public List<AppUserEntity> approversFor(BigDecimal amount, String except) {
        return users.findAll().stream()
                .filter(u -> u.active && !u.username.equals(except) && !AuthService.INSPECTOR.equals(u.role)
                        && allows(u.role, amount))
                .sorted(Comparator.comparingInt((AppUserEntity u) -> rank(u.role)).thenComparing(u -> u.username))
                .toList();
    }

    private static int rank(String role) {
        return switch (role) {
            case AuthService.ADJUSTER -> 0;
            case SENIOR_ADJUSTER -> 1;
            default -> 2;
        };
    }
}
