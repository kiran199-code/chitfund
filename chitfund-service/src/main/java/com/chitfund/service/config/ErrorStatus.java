package com.chitfund.service.config;

import java.util.Map;

/**
 * The one place that turns a business error code into an HTTP status. Plain ints, so it needs no Spring and is unit
 * tested. Codes not listed are business-rule refusals and answer 422. Internal-consistency codes (an unbalanced journal,
 * a missing account) mean a bug, not bad input, and answer 500.
 */
public final class ErrorStatus {

    private static final Map<String, Integer> BY_CODE = Map.ofEntries(
            // bad request
            Map.entry("ACTOR_REQUIRED", 400),
            Map.entry("INVALID_CURSOR", 400),
            Map.entry("CONFIRMATION_MISSING", 400),
            Map.entry("VALIDATION_FAILED", 400),
            Map.entry("HEADER_REQUIRED", 400),
            Map.entry("MALFORMED_BODY", 400),
            Map.entry("IDEMPOTENCY_KEY_REQUIRED", 400),
            // forbidden
            Map.entry("RESET_DISABLED", 403),
            Map.entry("MAKER_CHECKER_VIOLATION", 403),
            // not found
            Map.entry("NOT_FOUND", 404),
            // conflict
            Map.entry("IDEMPOTENCY_KEY_REUSED", 409),
            Map.entry("POSTING_KEY_REUSED", 409),
            Map.entry("ILLEGAL_TRANSITION", 409),
            Map.entry("RULES_FROZEN", 409),
            Map.entry("DATA_CONFLICT", 409),
            Map.entry("GROUP_CODE_EXISTS", 409),
            Map.entry("GROUP_FULL", 409),
            Map.entry("ENROLMENT_NOT_OPEN", 409),
            Map.entry("WITHDRAWAL_NOT_ALLOWED", 409),
            Map.entry("RULES_LOCKED_BY_ENROLMENT", 409),
            Map.entry("ACTIVATION_BLOCKED", 409),
            Map.entry("GROUP_NOT_ACTIVE", 409),
            Map.entry("TICKET_NOT_PAYABLE", 409),
            Map.entry("PAYMENT_ALREADY_REVERSED", 409),
            Map.entry("BUSINESS_DATE_MISMATCH", 409),
            Map.entry("BUSINESS_DATE_BACKWARDS", 409),
            Map.entry("BUSINESS_DATE_NOT_MANAGED", 409),
            Map.entry("EOD_ALREADY_RUNNING", 409),
            // auctions, payouts, dividends
            Map.entry("AUCTION_GATE_CLOSED", 409),
            Map.entry("AUCTION_NOT_OPEN", 409),
            Map.entry("AUCTION_NOT_DUE", 409),
            Map.entry("AUCTION_NOT_CLOSED", 409),
            Map.entry("PREVIOUS_CYCLE_OPEN", 409),
            Map.entry("PREVIOUS_PAYOUT_PENDING", 409),
            Map.entry("ROLL_OVER_BLOCKED", 409),
            Map.entry("LAST_CYCLE_NO_AUCTION", 409),
            Map.entry("LAST_CYCLE_CANDIDATES", 409),
            Map.entry("NO_ELIGIBLE_TICKETS", 409),
            Map.entry("BIDS_NOT_ACCEPTED", 409),
            Map.entry("PAYOUT_EXISTS", 409),
            Map.entry("PAYOUT_NOT_APPROVED", 409),
            Map.entry("PAYMENT_NOT_REVERSIBLE", 409),
            Map.entry("DIVIDEND_MODE_MISMATCH", 409),
            Map.entry("FLOAT_REPAYMENT_TOO_LARGE", 409),
            // the business date could not be read: nothing was changed, try again
            Map.entry("BUSINESS_DATE_UNAVAILABLE", 503),
            // internal consistency: these should never come from user input
            Map.entry("JOURNAL_UNBALANCED", 500),
            Map.entry("JOURNAL_INVALID", 500),
            Map.entry("JOURNAL_LINE_INVALID", 500),
            Map.entry("ACCOUNT_INVALID", 500),
            Map.entry("ACCOUNT_MISSING", 500),
            Map.entry("DIVIDEND_SHARES_MISMATCH", 500),
            Map.entry("SETTLEMENT_UNBALANCED", 500),
            Map.entry("TICKET_UNKNOWN", 500));

    private ErrorStatus() {
    }

    public static int of(String code) {
        return BY_CODE.getOrDefault(code, 422);
    }
}
