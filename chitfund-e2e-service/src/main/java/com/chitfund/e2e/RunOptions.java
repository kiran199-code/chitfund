package com.chitfund.e2e;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** What one run is asked to do. Validated here so a bad request is refused before anything is touched. */
public record RunOptions(List<String> scenarios, TimeTravel.Mode dateMode, int tickets, int customers, BigDecimal chitValue, boolean reset, String runId) {

    public static final List<String> ALL = List.of("A", "B", "C", "D");

    public static RunOptions from(JsonNode n) {
        List<String> sc = new ArrayList<>();
        if (n != null && n.path("scenarios").isArray()) {
            n.path("scenarios").forEach(x -> sc.add(x.asText().trim().toUpperCase()));
        }
        if (sc.isEmpty()) {
            sc.addAll(ALL);
        }
        for (String s : sc) {
            if (!ALL.contains(s)) {
                throw new IllegalArgumentException("Unknown scenario '" + s + "'; choose from " + ALL);
            }
        }
        String mode = Json.text(n, "dateMode");
        TimeTravel.Mode m = mode == null || mode.isBlank() ? TimeTravel.Mode.DAY_BY_DAY : TimeTravel.Mode.valueOf(mode.trim().toUpperCase());
        int tickets = Json.integer(n, "tickets", 10);
        int customers = Json.integer(n, "customers", 6);
        BigDecimal value = Json.money(n, "chitValue") == null ? new BigDecimal("100000") : Json.money(n, "chitValue");
        if (tickets < 4 || tickets > 40) throw new IllegalArgumentException("tickets must be between 4 and 40");
        if (customers < 2 || customers > 40) throw new IllegalArgumentException("customers must be between 2 and 40");
        if (tickets > customers * 3) throw new IllegalArgumentException("each customer may ask for at most 3 seats: tickets cannot exceed 3 x customers");
        if (value.signum() <= 0 || value.remainder(BigDecimal.valueOf(tickets)).signum() != 0) {
            throw new IllegalArgumentException("chitValue must be positive and divide evenly by the number of tickets");
        }
        boolean reset = !n.path("reset").isBoolean() || n.path("reset").asBoolean();
        String id = Json.text(n, "runId");
        if (id == null || id.isBlank()) id = Long.toString(System.currentTimeMillis() / 1000, 36);
        if (!id.matches("[a-z0-9]{1,12}")) throw new IllegalArgumentException("runId may contain only lower-case letters and digits (up to 12)");
        return new RunOptions(List.copyOf(sc), m, tickets, customers, value, reset, id);
    }
}
