package com.chitfund.service.maintenance;

import com.chitfund.service.common.ChitException;
import com.chitfund.service.domain.rules.ChitRules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * What a sample group looks like, from the four numbers the Maintenance screen asks for. Pure, so the rules are tested without a database.
 *
 * <p>Everything not asked for is fixed to the same ordinary values: monthly, instalment due on the 5th, auction on the 10th, 5% commission,
 * discount between 5% and 40%, no fees, no security, no late fee. The subscription is the chit value divided by the tickets and must come out
 * to a whole number of paise.
 */
public final class SampleGroups {

    public static final int MAX_GROUPS = 20;
    public static final int MIN_TICKETS = 2;
    public static final int MAX_TICKETS = 100;
    public static final String CODE_PREFIX = "SAMPLE-";

    private SampleGroups() {
    }

    /** Throws VALIDATION_FAILED, naming the field, when an input cannot make a group. */
    public static void validate(int count, BigDecimal chitValue, int tickets, LocalDate startDate) {
        if (count < 1 || count > MAX_GROUPS) {
            throw ChitException.badRequest("VALIDATION_FAILED", "count must be between 1 and " + MAX_GROUPS);
        }
        if (tickets < MIN_TICKETS || tickets > MAX_TICKETS) {
            throw ChitException.badRequest("VALIDATION_FAILED", "tickets must be between " + MIN_TICKETS + " and " + MAX_TICKETS);
        }
        if (chitValue == null || chitValue.signum() <= 0 || chitValue.scale() > 2) {
            throw ChitException.badRequest("VALIDATION_FAILED", "chitValue must be a positive amount with at most two decimals");
        }
        if (startDate == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "startDate is required");
        }
        if (subscription(chitValue, tickets) == null) {
            throw ChitException.badRequest("VALIDATION_FAILED",
                    "chitValue must divide evenly by the number of tickets (to the paisa); " + chitValue.toPlainString() + " / " + tickets + " does not");
        }
    }

    /** Chit value divided by tickets to two decimals, or null if that is not exact. */
    public static BigDecimal subscription(BigDecimal chitValue, int tickets) {
        BigDecimal s = chitValue.divide(BigDecimal.valueOf(tickets), 2, RoundingMode.DOWN);
        return s.multiply(BigDecimal.valueOf(tickets)).compareTo(chitValue) == 0 && s.signum() > 0 ? s : null;
    }

    public static ChitRules rules(BigDecimal chitValue, int tickets, LocalDate startDate) {
        return ChitRules.builder()
                .chitValue(chitValue.setScale(2, RoundingMode.UNNECESSARY))
                .ticketCount(tickets).cycleCount(tickets)
                .subscription(subscription(chitValue, tickets))
                .instalmentDueDay(5).auctionDay(10).startDate(startDate)
                .commissionPct(new BigDecimal("5")).maxCommissionPct(new BigDecimal("5"))
                .minDiscountPct(new BigDecimal("5")).maxDiscountPct(new BigDecimal("40"))
                .build();
    }

    /** SAMPLE-001, SAMPLE-002, ... */
    public static String code(int number) {
        return CODE_PREFIX + String.format("%03d", number);
    }

    /** The number after the highest existing SAMPLE-nnn code (1 when there is none). Codes that do not fit the pattern are ignored. */
    public static int nextNumber(Iterable<String> existingCodes) {
        int max = 0;
        for (String c : existingCodes) {
            if (c != null && c.startsWith(CODE_PREFIX)) {
                try {
                    max = Math.max(max, Integer.parseInt(c.substring(CODE_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                    // some other group that merely starts with SAMPLE-
                }
            }
        }
        return max + 1;
    }

    public static String name(String code, BigDecimal chitValue, int tickets) {
        return "Sample group " + code + " (" + chitValue.toPlainString() + " x " + tickets + " tickets)";
    }
}
