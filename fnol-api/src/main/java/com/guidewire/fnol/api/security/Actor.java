package com.guidewire.fnol.api.security;

import com.guidewire.fnol.api.persistence.AppUserEntity;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;

/** Who is behind a request: a signed-in person, or the service credential, which has one fixed identity and is not a person. */
public record Actor(String id, String displayName, String role) {

    static final String REQUEST_ATTRIBUTE = "fnol.actor";
    static final String SERVICE_ATTRIBUTE = "fnol.service";
    public static final String SERVICE_ROLE = "SERVICE";
    /** The one identity of the service credential. No request field can change it. */
    public static final Actor SERVICE = new Actor("service-credential", "Service credential", SERVICE_ROLE);

    public ActorType type() {
        return SERVICE_ROLE.equals(role) ? ActorType.SERVICE_ACTOR : ActorType.HUMAN_ACTOR;
    }

    static Actor of(AppUserEntity user) {
        return new Actor(user.username, user.displayName, user.role);
    }

    /** The signed-in person, if any. */
    public static Optional<Actor> current() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        return request == null ? Optional.empty() : Optional.ofNullable(
                (Actor) request.getAttribute(REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
    }

    /** The authenticated caller, person or service credential; null when the request carried no credential. */
    public static Actor caller() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return null;
        }
        Actor person = (Actor) request.getAttribute(REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (person != null) {
            return person;
        }
        return request.getAttribute(SERVICE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) != null ? SERVICE : null;
    }

    /**
     * Who is acting: the signed-in person, whatever the request body claims, so nobody can record a decision under
     * another name. A service-token caller is always the service credential: a name it sends establishes nothing.
     */
    public static String idOr(String claimed) {
        Actor caller = caller();
        return caller != null ? caller.id() : claimed;
    }
}
