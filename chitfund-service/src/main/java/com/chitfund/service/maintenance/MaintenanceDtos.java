package com.chitfund.service.maintenance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class MaintenanceDtos {
    private MaintenanceDtos() {
    }

    /** Whether clearing is switched on, and how many rows each table holds now. */
    public record StatusView(boolean resetEnabled, Map<String, Long> rowCounts) {
    }

    /** Request body of the clear: the caller types the confirmation word. */
    public record ClearRequest(String confirm) {
    }

    /** What a clear removed: the rows each table held just before it was emptied. */
    public record ClearResult(Map<String, Long> deleted, long totalRows, String clearedBy, OffsetDateTime clearedAt, String message) {
    }

    /** How many sample groups to make, and the three values they share. {@code ticketCount} is also the number of cycles. */
    public record SampleGroupsRequest(Integer count, BigDecimal chitValue, Integer ticketCount, LocalDate startDate) {
    }

    public record SampleGroupsResult(List<String> groupCodes, BigDecimal chitValue, int ticketCount, BigDecimal subscription, LocalDate startDate,
                                     String createdBy, String message) {
    }
}
