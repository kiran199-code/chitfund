package com.chitfund.admin.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Spring Security throws "duplicate element" when a role is listed twice, which stopped the console from starting. */
class RolesForTest {

    @Test
    void aRuleNamingAdminDoesNotRepeatIt() {
        var rule = new AccessPolicy.Rule("PUT", "/x", List.of(AccessPolicy.ADMIN));
        assertEquals(List.of("ADMIN"), Arrays.asList(AccessPolicy.rolesFor(rule)));
    }

    @Test
    void adminComesFirstThenTheNamedRoles() {
        var rule = new AccessPolicy.Rule(null, "/x", List.of("CHIT_OPS", "BATCH_OPS"));
        assertEquals(List.of("ADMIN", "CHIT_OPS", "BATCH_OPS"), Arrays.asList(AccessPolicy.rolesFor(rule)));
    }

    @Test
    void everyRealRuleYieldsDistinctRoles() {
        for (AccessPolicy.Rule r : AccessPolicy.RULES) {
            if (r.roles().isEmpty()) {
                continue;
            }
            String[] roles = AccessPolicy.rolesFor(r);
            assertEquals(roles.length, new HashSet<>(Arrays.asList(roles)).size(), r.pattern());
        }
    }
}
