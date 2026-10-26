package com.chitfund.admin.config;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Who may call which admin API. One list, read top to bottom, first match wins; a call that matches no rule is for ADMIN only.
 * ADMIN passes every rule, so a rule only names the other roles that may use it.
 *
 * <ul>
 *   <li>Everyone who signs in can read: the business date, the holiday list, chit groups, tickets, cycles, dues, payments, statements.</li>
 *   <li>CHIT_OPS: every change to chit data: create and activate groups, enrol, verify security, record and reverse payments, cancel, and
 *       edit the holiday list.</li>
 *   <li>CHIT_OPS: also the customer portal: offers, text and seat requests (read and change).</li>
 *   <li>BATCH_OPS: end of day and the batch jobs. Also the three "run now" buttons (demand, late fee, default check), which CHIT_OPS may press too.</li>
 *   <li>ADMIN only: setting the business date by hand, and Maintenance (clear all data).</li>
 * </ul>
 *
 * Patterns are exact paths, or a path ending in {@code /**} (which also matches the path itself), as in Spring's matcher, so the same rules feed
 * {@link SecurityConfig} and the unit test.
 */
public final class AccessPolicy {

    public static final String ADMIN = "ADMIN";
    public static final String CHIT_OPS = "CHIT_OPS";
    public static final String BATCH_OPS = "BATCH_OPS";

    /** {@code method} null = any method; {@code roles} empty = any signed-in user (ADMIN is always allowed in addition). */
    public record Rule(String method, String pattern, List<String> roles) {
        boolean matches(String m, String path) {
            if (method != null && !method.equalsIgnoreCase(m)) {
                return false;
            }
            if (pattern.endsWith("/**")) {
                String base = pattern.substring(0, pattern.length() - 3);
                return path.equals(base) || path.startsWith(base + "/");
            }
            return pattern.equals(path);
        }
    }

    private static final String A = "/api/admin";

    public static final List<Rule> RULES = List.of(
            // inquiries: every signed-in user
            new Rule("GET", A + "/me", List.of()),
            new Rule("GET", A + "/business-date", List.of()),
            new Rule("GET", A + "/calendar/**", List.of()),
            new Rule("GET", A + "/chit/**", List.of()),
            // the business date is set by hand by ADMIN only (a rule naming ADMIN keeps CHIT_OPS out of the /calendar/** rule below)
            new Rule("PUT", A + "/calendar/business-date", List.of(ADMIN)),
            // the daily runs may be pressed by the chit and the batch operators
            new Rule(null, A + "/chit/batch/**", List.of(CHIT_OPS, BATCH_OPS)),
            // actions
            new Rule(null, A + "/chit/**", List.of(CHIT_OPS)),
            new Rule(null, A + "/calendar/**", List.of(CHIT_OPS)),
            // customer portal content and seat requests (the list shows addresses, so reading is limited too)
            new Rule(null, A + "/portal/**", List.of(CHIT_OPS)),
            new Rule(null, A + "/batch/**", List.of(BATCH_OPS)),
            new Rule(null, A + "/eod/**", List.of(BATCH_OPS)));

    private AccessPolicy() {}

    public static Optional<Rule> match(String method, String path) {
        return RULES.stream().filter(r -> r.matches(method, path)).findFirst();
    }

    /**
     * The roles a rule lets through: ADMIN plus whatever the rule names, each once. Spring Security refuses a repeated role
     * ("duplicate element: ROLE_ADMIN"), and a rule that names ADMIN itself (to keep other roles out) would repeat it.
     */
    public static String[] rolesFor(Rule r) {
        java.util.Set<String> roles = new java.util.LinkedHashSet<>();
        roles.add(ADMIN);
        roles.addAll(r.roles());
        return roles.toArray(new String[0]);
    }

    /** @param roles role names without the ROLE_ prefix */
    public static boolean allowed(String method, String path, Collection<String> roles) {
        if (roles.contains(ADMIN)) {
            return true;
        }
        return match(method, path).map(r -> r.roles().isEmpty() || r.roles().stream().anyMatch(roles::contains)).orElse(false);
    }
}
