package com.chitfund.portal.offer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class OfferRepository {

    public record OfferRow(long id, String title, String body, String linkUrl, String imageUrl, String audience, LocalDate startsOn, LocalDate endsOn,
                           boolean active, String createdBy) {
    }

    private static final String COLUMNS = "id, title, body, link_url, image_url, audience, starts_on, ends_on, active, created_by";

    private static final RowMapper<OfferRow> MAP = (rs, i) -> new OfferRow(rs.getLong("id"), rs.getString("title"), rs.getString("body"),
            rs.getString("link_url"), rs.getString("image_url"), rs.getString("audience"), rs.getObject("starts_on", LocalDate.class),
            rs.getObject("ends_on", LocalDate.class), rs.getBoolean("active"), rs.getString("created_by"));

    private final JdbcTemplate jdbc;

    public OfferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String title, String body, String link, String image, String audience, LocalDate starts, LocalDate ends, String actor) {
        return jdbc.query("INSERT INTO portal_offer (title, body, link_url, image_url, audience, starts_on, ends_on, created_by) VALUES (?,?,?,?,?,?,?,?) RETURNING id",
                (rs, i) -> rs.getLong(1), title, body, link, image, audience, Date.valueOf(starts), ends == null ? null : Date.valueOf(ends), actor).get(0);
    }

    public int update(long id, String title, String body, String link, String image, String audience, LocalDate starts, LocalDate ends, boolean active, String actor) {
        return jdbc.update("UPDATE portal_offer SET title=?, body=?, link_url=?, image_url=?, audience=?, starts_on=?, ends_on=?, active=?, updated_by=?, updated_at=now() WHERE id=?",
                title, body, link, image, audience, Date.valueOf(starts), ends == null ? null : Date.valueOf(ends), active, actor, id);
    }

    public int setActive(long id, boolean active, String actor) {
        return jdbc.update("UPDATE portal_offer SET active=?, updated_by=?, updated_at=now() WHERE id=?", active, actor, id);
    }

    public Optional<OfferRow> byId(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_offer WHERE id=?", MAP, id).stream().findFirst();
    }

    /** All offers, newest first (admin screen). */
    public List<OfferRow> all() {
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_offer ORDER BY active DESC, starts_on DESC, id DESC LIMIT 500", MAP);
    }

    /** Offers live on {@code today}; {@code publicOnly} keeps only those meant for visitors who have not signed in. */
    public List<OfferRow> live(LocalDate today, boolean publicOnly) {
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_offer WHERE active AND starts_on <= ? AND (ends_on IS NULL OR ends_on >= ?) "
                + (publicOnly ? "AND audience = 'PUBLIC' " : "") + "ORDER BY starts_on DESC, id DESC LIMIT 50", MAP, Date.valueOf(today), Date.valueOf(today));
    }
}
