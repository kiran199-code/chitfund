package com.chitfund.admin.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class AuthController {

    private final String timezone;

    public AuthController(@Value("${admin.timezone:Asia/Kolkata}") String timezone) {
        this.timezone = timezone;
    }

    /** Used by the login screen to verify credentials and learn the roles; the menu shows only what the roles may use. */
    @GetMapping("/api/admin/me")
    public Map<String, Object> me(Authentication authentication) {
        // roles without the ROLE_ prefix: ADMIN, CHIT_OPS or BATCH_OPS
        var roles = authentication.getAuthorities().stream().map(a -> a.getAuthority().replaceFirst("^ROLE_", "")).sorted().collect(Collectors.toList());
        return Map.of("username", authentication.getName(), "timezone", timezone, "roles", roles);
    }
}
