package com.chitfund.service.customer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** JDBC access to the customer master. Every method runs in the caller's transaction. */
@Repository
public class CustomerRepository {

    public record CustomerRow(long id, String email, String displayName) {
    }

    private static final String COLUMNS = "id, email, display_name";

    private final JdbcTemplate jdbc;

    public CustomerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CustomerRow> byEmail(String email) {
        return jdbc.query("SELECT " + COLUMNS + " FROM chit_customer WHERE email = ?",
                (rs, i) -> new CustomerRow(rs.getLong(1), rs.getString(2), rs.getString(3)), email).stream().findFirst();
    }

    public Optional<CustomerRow> byId(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM chit_customer WHERE id = ?",
                (rs, i) -> new CustomerRow(rs.getLong(1), rs.getString(2), rs.getString(3)), id).stream().findFirst();
    }

    /** True if any ticket, subscriber link, guarantor entry or transfer already uses this customer id. */
    public boolean idInUse(long id) {
        Boolean used = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM chit_ticket WHERE customer_id = ?) "
                        + "OR EXISTS (SELECT 1 FROM chit_subscriber_link WHERE customer_id = ?) "
                        + "OR EXISTS (SELECT 1 FROM chit_security WHERE guarantor_customer_id = ?) "
                        + "OR EXISTS (SELECT 1 FROM chit_ticket_transfer WHERE from_customer_id = ? OR to_customer_id = ?) "
                        + "OR EXISTS (SELECT 1 FROM chit_customer WHERE id = ?)",
                Boolean.class, id, id, id, id, id, id);
        return Boolean.TRUE.equals(used);
    }

    /** The next id from the sequence that nothing uses yet. Ids typed in by hand elsewhere may sit above the sequence, so skip any in use. */
    public long allocateId() {
        for (int i = 0; i < 100000; i++) {
            Long id = jdbc.queryForObject("SELECT nextval('chit_customer_id_seq')", Long.class);
            if (id != null && !idInUse(id)) {
                return id;
            }
        }
        throw new IllegalStateException("No free customer id found");
    }

    /** Inserts the customer with the given id; returns false if another row already holds this e-mail (a concurrent insert). */
    public boolean insert(long id, String email, String name, String source, String actor) {
        List<Long> got = jdbc.query(
                "INSERT INTO chit_customer (id, email, display_name, source, created_by) VALUES (?,?,?,?,?) "
                        + "ON CONFLICT (email) DO NOTHING RETURNING id",
                (rs, i) -> rs.getLong(1), id, email, name, source, actor);
        return !got.isEmpty();
    }

    public void setNameIfMissing(long id, String name) {
        jdbc.update("UPDATE chit_customer SET display_name = ? WHERE id = ? AND display_name IS NULL", name, id);
    }

    public List<CustomerRow> byEmails(List<String> emails) {
        List<CustomerRow> out = new java.util.ArrayList<>();
        for (String e : emails) {
            byEmail(e).ifPresent(out::add);
        }
        return out;
    }
}
