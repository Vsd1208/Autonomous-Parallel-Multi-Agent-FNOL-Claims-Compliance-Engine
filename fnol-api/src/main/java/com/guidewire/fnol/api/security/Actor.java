package com.guidewire.fnol.api.security;

import com.guidewire.fnol.api.persistence.AppUserEntity;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;

/** The signed-in person behind a request. A request made with the service token has none. */
public record Actor(String id, String displayName, String role) {

    static final String REQUEST_ATTRIBUTE = "fnol.actor";

    static Actor of(AppUserEntity user) {
        return new Actor(user.username, user.displayName, user.role);
    }

    /** The signed-in person, if any. */
    public static Optional<Actor> current() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        return request == null ? Optional.empty() : Optional.ofNullable(
                (Actor) request.getAttribute(REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
    }

    /**
     * Who is acting: the signed-in person, whatever the request body claims, so nobody can record a decision under
     * another name. Only a service-token caller (an integration, with no person behind it) states its own actor.
     */
    public static String idOr(String claimed) {
        return current().map(Actor::id).orElse(claimed);
    }
}
