package com.chitfund.service.maintenance;

import com.chitfund.service.businessdate.BusinessDateService;
import com.chitfund.service.common.ChitException;
import com.chitfund.service.group.GroupDtos.CreateGroupRequest;
import com.chitfund.service.group.GroupService;
import com.chitfund.service.maintenance.MaintenanceDtos.ClearResult;
import com.chitfund.service.maintenance.MaintenanceDtos.SampleGroupsRequest;
import com.chitfund.service.maintenance.MaintenanceDtos.SampleGroupsResult;
import com.chitfund.service.maintenance.MaintenanceDtos.StatusView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Start-from-scratch for a non-production environment: empties every chit table (and puts the business date back to today). Called by the admin console's Maintenance screen after
 * it has checked that this switch is on.
 *
 * <p>Refused unless {@code chit.maintenance.reset-enabled} is true (CHIT_RESET_ENABLED) and the caller types the
 * confirmation word. The schema history is kept. The ledger is append-only for normal use; TRUNCATE is not a row
 * operation, so it is the one sanctioned way to empty it, and only behind this switch.
 */
@Service
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    public static final String CONFIRMATION = "RESET";

    private final JdbcTemplate jdbc;
    private final boolean resetEnabled;
    private final BusinessDateService dates;
    private final GroupService groups;

    public MaintenanceService(JdbcTemplate jdbc, BusinessDateService dates, GroupService groups,
                              @Value("${chit.maintenance.reset-enabled:false}") boolean resetEnabled) {
        this.jdbc = jdbc;
        this.dates = dates;
        this.groups = groups;
        this.resetEnabled = resetEnabled;
    }

    @Transactional(readOnly = true)
    public StatusView status() {
        return new StatusView(resetEnabled, rowCounts());
    }

    @Transactional
    public ClearResult clearAll(String confirm, String actor) {
        if (!resetEnabled) {
            throw ChitException.forbidden("RESET_DISABLED",
                    "Clearing data is switched off on this chitfund-service. Set CHIT_RESET_ENABLED=true to allow it "
                            + "(non-production environments only).");
        }
        if (!CONFIRMATION.equals(confirm)) {
            throw ChitException.badRequest("CONFIRMATION_MISSING", "Confirmation missing: send {\"confirm\": \"" + CONFIRMATION + "\"}");
        }
        Map<String, Long> before = rowCounts();
        // One statement naming every table, so no CASCADE is needed (all the foreign keys point inside this list).
        // RESTART IDENTITY puts the ids back to 1.
        jdbc.execute("TRUNCATE TABLE " + String.join(", ", ResetTables.TABLES) + " RESTART IDENTITY");
        dates.resetToToday(actor == null || actor.isBlank() ? "unknown" : actor.trim());
        long total = before.values().stream().mapToLong(Long::longValue).sum();
        String who = actor == null || actor.isBlank() ? "unknown" : actor.trim();
        log.warn("CHIT DATA CLEARED by {}: {} rows removed {}", who, total, before);
        return new ClearResult(before, total, who, OffsetDateTime.now(),
                "All chit groups, tickets, payments and journals were removed and the business date is back to today. Holidays were kept.");
    }

    /**
     * Makes {@code count} new groups SAMPLE-nnn (next free numbers), each in DRAFT with the given chit value, tickets (= cycles) and start date.
     * Same switch as the clear, so it cannot run on a production service. All or none: one transaction.
     */
    @Transactional
    public SampleGroupsResult createSampleGroups(SampleGroupsRequest req, String actor) {
        if (!resetEnabled) {
            throw ChitException.forbidden("RESET_DISABLED",
                    "Creating sample data is switched off on this chitfund-service. Set CHIT_RESET_ENABLED=true to allow it "
                            + "(non-production environments only).");
        }
        if (req == null || req.count() == null || req.ticketCount() == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "count, chitValue, ticketCount and startDate are required");
        }
        SampleGroups.validate(req.count(), req.chitValue(), req.ticketCount(), req.startDate());
        String who = actor == null || actor.isBlank() ? "unknown" : actor.trim();
        int next = SampleGroups.nextNumber(jdbc.query("SELECT group_code FROM chit_group WHERE group_code LIKE 'SAMPLE-%'", (rs, i) -> rs.getString(1)));
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < req.count(); i++) {
            String code = SampleGroups.code(next + i);
            groups.create(new CreateGroupRequest(code, SampleGroups.name(code, req.chitValue(), req.ticketCount()), "SAMPLE-FOREMAN",
                    SampleGroups.rules(req.chitValue(), req.ticketCount(), req.startDate())), "sample-" + UUID.randomUUID(), who);
            codes.add(code);
        }
        BigDecimal sub = SampleGroups.subscription(req.chitValue(), req.ticketCount());
        log.warn("SAMPLE GROUPS created by {}: {}", who, codes);
        return new SampleGroupsResult(codes, req.chitValue(), req.ticketCount(), sub, req.startDate(), who,
                codes.size() + " sample group(s) created in DRAFT: " + String.join(", ", codes) + ".");
    }

    private Map<String, Long> rowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : ResetTables.TABLES) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
            counts.put(table, n == null ? 0L : n);
        }
        return counts;
    }
}
