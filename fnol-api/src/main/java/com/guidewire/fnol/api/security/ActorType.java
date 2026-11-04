package com.guidewire.fnol.api.security;

/** Who is behind an action. Only a person can make a human decision or satisfy maker-checker. */
public enum ActorType {
    /** A signed-in person. */
    HUMAN_ACTOR,
    /** An integration using the service credential. It has one fixed identity and cannot claim another. */
    SERVICE_ACTOR,
    /** The engine itself: recommendations and automatic steps. */
    SYSTEM_ACTOR
}
