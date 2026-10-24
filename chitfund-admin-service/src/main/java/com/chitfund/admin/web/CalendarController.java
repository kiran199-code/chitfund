package com.chitfund.admin.web;

import com.chitfund.admin.service.ChitClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The business date and the holiday list, both owned by chitfund-service. This controller only forwards: the chit service applies the
 * rules (forward-only date, weekend days, holiday names) and its message comes back unchanged.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Business date", description = "The date every chit rule uses, and the working-day calendar (owned by chitfund-service)")
public class CalendarController {

    private final ChitClient client;

    public CalendarController(ChitClient client) {
        this.client = client;
    }

    @Operation(summary = "Current business date, its status (OPEN or EOD_RUNNING), whether it is a working day, and the mode (MANAGED or SYSTEM)")
    @GetMapping("/business-date")
    public JsonNode businessDate() {
        return client.get("/business-date", Map.of());
    }

    @Operation(summary = "Set the business date forward by hand (test environments; chit service needs CHIT_RESET_ENABLED=true)")
    @PutMapping("/calendar/business-date")
    public JsonNode setBusinessDate(@RequestBody(required = false) Map<String, String> body,
                                    @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        LocalDate d = day(body == null ? null : body.get("businessDate"), "businessDate");
        return client.exchange(HttpMethod.PUT, "/business-date", Map.of(), Map.of("businessDate", d.toString()),
                ChitController.actor(acting, user), null);
    }

    @Operation(summary = "Is the date a working day")
    @GetMapping("/calendar/working-day")
    public JsonNode workingDay(@RequestParam("date") String date) {
        return client.get("/business-date/working-day", Map.of("date", day(date, "date").toString()));
    }

    @Operation(summary = "The weekend days and the holidays, optionally for one year")
    @GetMapping("/calendar/holidays")
    public JsonNode holidays(@RequestParam(required = false) Integer year) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("year", year);
        return client.get("/holidays", q);
    }

    @Operation(summary = "Add a holiday, or rename it")
    @PutMapping("/calendar/holidays/{date}")
    public JsonNode putHoliday(@PathVariable String date, @RequestBody(required = false) Map<String, String> body,
                               @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        LocalDate d = day(date, "date");
        String name = body == null ? null : body.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        return client.exchange(HttpMethod.PUT, "/holidays/" + d, Map.of(), Map.of("name", name.trim()), ChitController.actor(acting, user), null);
    }

    @Operation(summary = "Remove a holiday")
    @DeleteMapping("/calendar/holidays/{date}")
    public JsonNode deleteHoliday(@PathVariable String date,
                                  @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        LocalDate d = day(date, "date");
        return client.exchange(HttpMethod.DELETE, "/holidays/" + d, Map.of(), null, ChitController.actor(acting, user), null);
    }

    static LocalDate day(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(name + " is required, like 2026-11-02");
        }
        try {
            return LocalDate.parse(v.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must look like 2026-11-02");
        }
    }
}
