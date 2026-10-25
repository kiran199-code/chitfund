package harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in for Spring's JdbcTemplate that sends every statement to a real PostgreSQL through pgbridge.py (psycopg2). It is NOT Spring:
 * there are no @Transactional proxies, so the harness opens and commits transactions by hand with {@link #inTx}. Constraint triggers that are
 * deferred to commit therefore run at {@link #commit()}, exactly as in production.
 */
public class PgJdbcTemplate extends JdbcTemplate {

    private static final ObjectMapper M = new ObjectMapper();
    private final Process proc;
    private final BufferedWriter out;
    private final BufferedReader in;
    public int statements;

    public PgJdbcTemplate(String db, String socketDir, int port) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("python3", "" + System.getProperty("bridge", "/home/claude/cf/chitfund-portal-service/harness/pgbridge.py") + "", db, socketDir, String.valueOf(port));
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        proc = pb.start();
        out = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
        in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
    }

    public static class SqlError extends RuntimeException {
        public final String pgcode;
        public SqlError(String m, String pgcode) { super(m); this.pgcode = pgcode; }
    }

    private JsonNode call(String op, String sql, Object[] args) {
        try {
            ObjectNode req = M.createObjectNode();
            req.put("op", op);
            if (sql != null) req.put("sql", sql);
            ArrayNode a = req.putArray("args");
            if (args != null) for (Object o : args) a.add(tag(o));
            out.write(M.writeValueAsString(req)); out.write('\n'); out.flush();
            String line = in.readLine();
            if (line == null) throw new IllegalStateException("bridge closed");
            statements++;
            JsonNode resp = M.readTree(line);
            if (resp.has("error")) throw new SqlError(resp.get("error").asText(), resp.path("pgcode").asText(null));
            return resp;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode tag(Object o) {
        if (o == null) return com.fasterxml.jackson.databind.node.NullNode.instance;
        ObjectNode n = M.createObjectNode();
        if (o instanceof BigDecimal b) { n.put("t", "dec"); n.put("v", b.toPlainString()); }
        else if (o instanceof Long || o instanceof Integer || o instanceof Short) { n.put("t", "int"); n.put("v", o.toString()); }
        else if (o instanceof Boolean b) { n.put("t", "bool"); n.put("v", b.toString()); }
        else if (o instanceof java.sql.Date d) { n.put("t", "date"); n.put("v", d.toString()); }
        else if (o instanceof LocalDate d) { n.put("t", "date"); n.put("v", d.toString()); }
        else if (o instanceof OffsetDateTime d) { n.put("t", "ts"); n.put("v", d.toString()); }
        else if (o instanceof Enum<?> e) { n.put("t", "str"); n.put("v", e.name()); }
        else { n.put("t", "str"); n.put("v", o.toString()); }
        return n;
    }

    private static Object untag(JsonNode n) {
        if (n == null || n.isNull()) return null;
        String v = n.get("v").asText();
        return switch (n.get("t").asText()) {
            case "int" -> Long.valueOf(v);
            case "dec" -> new BigDecimal(v);
            case "bool" -> Boolean.valueOf(v);
            case "date" -> LocalDate.parse(v);
            case "ts" -> OffsetDateTime.parse(v);
            default -> v;
        };
    }

    // ---- transactions ---------------------------------------------------------------------------------------------

    public void commit() { call("commit", null, null); }

    public void rollback() { call("rollback", null, null); }

    /** Runs the work as one transaction: commit on success, rollback and rethrow on any exception. */
    public <T> T inTx(java.util.function.Supplier<T> work) {
        try {
            T r = work.get();
            commit();
            return r;
        } catch (RuntimeException e) {
            try { rollback(); } catch (RuntimeException ignored) { }
            throw e;
        }
    }

    // ---- JdbcTemplate surface used by the repositories ------------------------------------------------------------

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rm, Object... args) {
        JsonNode r = call("query", sql, args);
        if (!r.has("rows")) throw new IllegalStateException("statement returned no result set: " + sql);
        List<String> cols = new ArrayList<>();
        r.get("cols").forEach(c -> cols.add(c.asText().toLowerCase()));
        List<T> out = new ArrayList<>();
        int i = 0;
        for (JsonNode row : r.get("rows")) {
            List<Object> vals = new ArrayList<>();
            row.forEach(c -> vals.add(untag(c)));
            try {
                out.add(rm.mapRow(rs(cols, vals), i++));
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T queryForObject(String sql, Class<T> type, Object... args) {
        JsonNode r = call("query", sql, args);
        JsonNode rows = r.get("rows");
        if (rows == null || rows.size() != 1) throw new IllegalStateException("expected one row for: " + sql);
        Object v = untag(rows.get(0).get(0));
        if (v == null) return null;
        if (type == Integer.class) return (T) Integer.valueOf(((Number) v).intValue());
        if (type == Long.class) return (T) Long.valueOf(((Number) v).longValue());
        if (type == BigDecimal.class) return (T) (v instanceof BigDecimal ? v : new BigDecimal(v.toString()));
        if (type == String.class) return (T) v.toString();
        return type.cast(v);
    }

    @Override
    public <T> T queryForObject(String sql, RowMapper<T> rm, Object... args) {
        List<T> l = query(sql, rm, args);
        if (l.size() != 1) throw new IllegalStateException("expected one row for: " + sql);
        return l.get(0);
    }

    @Override
    public int update(String sql, Object... args) {
        return call("update", sql, args).path("count").asInt();
    }

    @Override
    public void execute(String sql) {
        call("update", sql, null);
    }

    // ---- ResultSet over one row -----------------------------------------------------------------------------------

    private static ResultSet rs(List<String> cols, List<Object> vals) {
        boolean[] wasNull = {false};
        return (ResultSet) Proxy.newProxyInstance(PgJdbcTemplate.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (proxy, method, a) -> {
            String m = method.getName();
            if (m.equals("wasNull")) return wasNull[0];
            if (a == null || a.length == 0) throw new UnsupportedOperationException(m);
            int idx = a[0] instanceof Integer i ? i - 1 : cols.indexOf(((String) a[0]).toLowerCase());
            if (idx < 0) throw new java.sql.SQLException("no column " + a[0] + " in " + cols);
            Object v = vals.get(idx);
            wasNull[0] = v == null;
            switch (m) {
                case "getString": return v == null ? null : v instanceof BigDecimal b ? b.toPlainString() : v.toString();
                case "getLong": return v == null ? 0L : ((Number) v).longValue();
                case "getInt": return v == null ? 0 : ((Number) v).intValue();
                case "getBoolean": return v != null && (Boolean) v;
                case "getBigDecimal": return v == null ? null : v instanceof BigDecimal ? v : new BigDecimal(v.toString());
                case "getDate": return v == null ? null : java.sql.Date.valueOf((LocalDate) v);
                case "getObject":
                    if (a.length == 1) return v;
                    Class<?> c = (Class<?>) a[1];
                    if (v == null) return null;
                    if (c == LocalDate.class && v instanceof OffsetDateTime o) return o.toLocalDate();
                    return c.cast(v);
                default: throw new UnsupportedOperationException(m);
            }
        });
    }

    public void close() { proc.destroy(); }
}
