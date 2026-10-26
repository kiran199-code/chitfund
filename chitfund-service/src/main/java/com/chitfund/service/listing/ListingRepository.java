package com.chitfund.service.listing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Reads and writes how a group is shown on the customer portal's plan shelf (plan category, branch, state). */
@Repository
public class ListingRepository {

    /** A group open for enrolment, as the plan shelf shows it. Category, branch and state may be null for groups nobody has labelled. */
    public record OpenPlan(String groupCode, String name, String planCategory, String branch, String state, BigDecimal chitValue, int ticketCount,
                           int cycleCount, BigDecimal subscription, String frequency, LocalDate startDate, int seatsLeft) {
    }

    private final JdbcTemplate jdbc;

    public ListingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<OpenPlan> openPlans() {
        return jdbc.query("SELECT g.group_code, g.name, g.plan_category, g.branch, g.state, g.chit_value, g.ticket_count, g.cycle_count, g.subscription, "
                + "g.frequency, g.start_date, "
                + "g.ticket_count - (SELECT count(*) FROM chit_ticket t WHERE t.group_id = g.id AND t.status <> 'EXITED') AS seats_left "
                + "FROM chit_group g WHERE g.status = 'OPEN_FOR_ENROLMENT' "
                + "ORDER BY g.state NULLS LAST, g.plan_category NULLS LAST, g.chit_value, g.group_code LIMIT 500",
                (rs, i) -> new OpenPlan(rs.getString("group_code"), rs.getString("name"), rs.getString("plan_category"), rs.getString("branch"),
                        rs.getString("state"), rs.getBigDecimal("chit_value"), rs.getInt("ticket_count"), rs.getInt("cycle_count"),
                        rs.getBigDecimal("subscription"), rs.getString("frequency"), rs.getObject("start_date", LocalDate.class),
                        Math.max(0, rs.getInt("seats_left"))));
    }

    /** Sets the three labels; returns how many groups matched (0 or 1). */
    public int setListing(String groupCode, String planCategory, String branch, String state, String actor) {
        return jdbc.update("UPDATE chit_group SET plan_category = ?, branch = ?, state = ?, updated_by = ? WHERE group_code = ?",
                planCategory, branch, state, actor, groupCode);
    }

    public int count(String groupCode) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chit_group WHERE group_code = ?", Integer.class, groupCode);
        return n == null ? 0 : n;
    }
}
