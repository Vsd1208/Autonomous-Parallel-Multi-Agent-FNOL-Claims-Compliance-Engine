package com.guidewire.fnol.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Sign-in for the adjuster workspace. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        try {
            return auth.login(request.username(), request.password())
                    .<ResponseEntity<?>>map(s -> ResponseEntity.ok(Map.of("token", s.token(), "username", s.actor().id(),
                            "displayName", s.actor().displayName(), "role", s.actor().role(), "expiresAt", s.expiresAt())))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "BAD_CREDENTIALS",
                            "message", "The name or password is wrong.")));
        } catch (AuthService.LockedOutException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "LOCKED_OUT", "message", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    Map<String, String> logout(HttpServletRequest request) {
        auth.logout(AdjusterTokenFilter.bearerToken(request));
        return Map.of("status", "SIGNED_OUT");
    }

    @GetMapping("/me")
    ResponseEntity<?> me() {
        return Actor.current().<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "UNAUTHORIZED", "message", "Nobody is signed in on this request")));
    }
}
