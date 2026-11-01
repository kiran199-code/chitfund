package com.chitfund.e2e;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Small JSON helpers: one mapper, object builders, and null-safe readers over {@link JsonNode}. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode arr() {
        return MAPPER.createArrayNode();
    }

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write JSON: " + e.getMessage(), e);
        }
    }

    public static JsonNode read(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not JSON: " + e.getOriginalMessage());
        }
    }

    /** The field, or a missing node (never null), so chains like {@code n.path("a").path("b")} are safe. */
    public static JsonNode at(JsonNode n, String field) {
        return n == null ? MAPPER.missingNode() : n.path(field);
    }

    public static String text(JsonNode n, String field) {
        JsonNode v = at(n, field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    public static int integer(JsonNode n, String field, int dflt) {
        JsonNode v = at(n, field);
        return v.isNumber() || v.isTextual() && !v.asText().isBlank() ? v.asInt(dflt) : dflt;
    }

    public static BigDecimal money(JsonNode n, String field) {
        JsonNode v = at(n, field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        return v.isNumber() ? v.decimalValue() : new BigDecimal(v.asText());
    }

    public static boolean bool(JsonNode n, String field) {
        return at(n, field).asBoolean(false);
    }

    public static LocalDate date(JsonNode n, String field) {
        String t = text(n, field);
        return t == null ? null : LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t);
    }
}
