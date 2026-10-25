package com.chitfund.portal.site;

import com.chitfund.portal.common.Audit;
import com.chitfund.portal.common.PortalException;
import com.chitfund.portal.member.HowToPay;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The few pieces of text the organiser edits: how to pay, the help line and the office address. Keys are fixed here, so nobody can store
 * anything else. The help line and address are public (the footer); how to pay is shown only to signed-in members.
 */
@Service
public class SiteSettingsService implements HowToPay {

    public static final String PAYMENT_INSTRUCTIONS = "payment_instructions";
    public static final String HELP_PHONE = "help_phone";
    public static final String HELP_HOURS = "help_hours";
    public static final String OFFICE_ADDRESS = "office_address";
    static final Map<String, Integer> LIMITS = Map.of(PAYMENT_INSTRUCTIONS, 1000, HELP_PHONE, 30, HELP_HOURS, 80, OFFICE_ADDRESS, 200);

    public record PublicSite(String helpPhone, String helpHours, String officeAddress) {
    }

    private final JdbcTemplate jdbc;
    private final Audit audit;

    public SiteSettingsService(JdbcTemplate jdbc, Audit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Every setting, with "" for the ones not set. */
    public Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        LIMITS.keySet().stream().sorted().forEach(k -> out.put(k, ""));
        jdbc.query("SELECT setting_key, value FROM portal_setting", (rs, i) -> {
            if (out.containsKey(rs.getString(1))) {
                out.put(rs.getString(1), rs.getString(2));
            }
            return 0;
        });
        return out;
    }

    public PublicSite publicSite() {
        Map<String, String> m = all();
        return new PublicSite(m.get(HELP_PHONE), m.get(HELP_HOURS), m.get(OFFICE_ADDRESS));
    }

    @Override
    public String text() {
        return all().get(PAYMENT_INSTRUCTIONS);
    }

    /** Saves the given keys (a blank value clears one). Unknown keys and over-long values are refused as a whole. */
    @Transactional
    public Map<String, String> save(Map<String, String> changes, String actor) {
        if (changes == null || changes.isEmpty()) {
            throw PortalException.badRequest("VALIDATION_FAILED", "Nothing to save");
        }
        Map<String, String> clean = validate(changes);
        for (Map.Entry<String, String> e : clean.entrySet()) {
            if (e.getValue().isEmpty()) {
                jdbc.update("DELETE FROM portal_setting WHERE setting_key = ?", e.getKey());
            } else {
                jdbc.update("INSERT INTO portal_setting (setting_key, value, updated_by) VALUES (?,?,?) "
                        + "ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by, updated_at = now()", e.getKey(), e.getValue(), actor);
            }
            audit.log("SETTING", e.getKey(), "SETTING_SAVED", actor, null, e.getValue().isEmpty() ? "cleared" : "updated");
        }
        return all();
    }

    static Map<String, String> validate(Map<String, String> changes) {
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : changes.entrySet()) {
            Integer limit = LIMITS.get(e.getKey());
            if (limit == null) {
                throw PortalException.badRequest("VALIDATION_FAILED", "Unknown setting " + e.getKey() + "; allowed: " + List.copyOf(LIMITS.keySet().stream().sorted().toList()));
            }
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (v.length() > limit) {
                throw PortalException.badRequest("VALIDATION_FAILED", e.getKey() + " is at most " + limit + " characters");
            }
            clean.put(e.getKey(), v);
        }
        return clean;
    }
}
