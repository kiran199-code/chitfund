package com.chitfund.portal.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Audit trail for sign-ins and decisions. Runs inside the caller's transaction. */
@Component
public class Audit {
    private final JdbcTemplate jdbc;

    public Audit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(String entityType, String entityRef, String action, String actor, String sourceIp, String detail) {
        jdbc.update("INSERT INTO portal_audit (entity_type, entity_ref, action, actor, source_ip, detail) VALUES (?,?,?,?,?,?)",
                entityType, entityRef, action, actor, sourceIp, detail == null ? null : (detail.length() > 500 ? detail.substring(0, 500) : detail));
    }
}
