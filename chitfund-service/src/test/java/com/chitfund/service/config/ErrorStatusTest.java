package com.chitfund.service.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorStatusTest {

    @Test
    void knownCodesMapToTheirStatus() {
        assertEquals(400, ErrorStatus.of("ACTOR_REQUIRED"));
        assertEquals(400, ErrorStatus.of("INVALID_CURSOR"));
        assertEquals(400, ErrorStatus.of("CONFIRMATION_MISSING"));
        assertEquals(400, ErrorStatus.of("IDEMPOTENCY_KEY_REQUIRED"));
        assertEquals(403, ErrorStatus.of("RESET_DISABLED"));
        assertEquals(503, ErrorStatus.of("BUSINESS_DATE_UNAVAILABLE"));
        assertEquals(404, ErrorStatus.of("NOT_FOUND"));
        assertEquals(409, ErrorStatus.of("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(409, ErrorStatus.of("POSTING_KEY_REUSED"));
        assertEquals(409, ErrorStatus.of("ILLEGAL_TRANSITION"));
        assertEquals(409, ErrorStatus.of("RULES_FROZEN"));
        assertEquals(403, ErrorStatus.of("MAKER_CHECKER_VIOLATION"));
        for (String code : new String[]{"GROUP_CODE_EXISTS", "GROUP_FULL", "ENROLMENT_NOT_OPEN", "WITHDRAWAL_NOT_ALLOWED",
                "RULES_LOCKED_BY_ENROLMENT", "ACTIVATION_BLOCKED", "GROUP_NOT_ACTIVE", "TICKET_NOT_PAYABLE",
                "PAYMENT_ALREADY_REVERSED", "BUSINESS_DATE_MISMATCH", "BUSINESS_DATE_BACKWARDS", "BUSINESS_DATE_NOT_MANAGED", "EOD_ALREADY_RUNNING",
                "AUCTION_GATE_CLOSED", "AUCTION_NOT_OPEN", "AUCTION_NOT_DUE", "AUCTION_NOT_CLOSED", "PREVIOUS_CYCLE_OPEN", "PREVIOUS_PAYOUT_PENDING",
                "ROLL_OVER_BLOCKED", "LAST_CYCLE_NO_AUCTION", "LAST_CYCLE_CANDIDATES", "NO_ELIGIBLE_TICKETS", "BIDS_NOT_ACCEPTED", "PAYOUT_EXISTS",
                "PAYOUT_NOT_APPROVED", "PAYMENT_NOT_REVERSIBLE", "DIVIDEND_MODE_MISMATCH", "FLOAT_REPAYMENT_TOO_LARGE"}) {
            assertEquals(409, ErrorStatus.of(code), code);
        }
    }

    @Test
    void businessRuleRefusalsAreUnprocessable() {
        assertEquals(422, ErrorStatus.of("CONFIG_INVALID"));
        assertEquals(422, ErrorStatus.of("PAYMENT_EXCEEDS_OBLIGATION"));
        assertEquals(422, ErrorStatus.of("PAYMENT_ADVANCE_CONSUMED"));
        assertEquals(422, ErrorStatus.of("DISCOUNT_BELOW_COMMISSION"));
        assertEquals(422, ErrorStatus.of("ENROLMENT_AMOUNT_MISMATCH"));
        assertEquals(422, ErrorStatus.of("GUARANTOR_REQUIRED"));
        assertEquals(422, ErrorStatus.of("CALENDAR_OVERLAP"));
        for (String code : new String[]{"BID_OUT_OF_BOUNDS", "BID_NOT_HIGHER", "PAYOUT_LIQUIDITY_SHORT", "TICKET_NOT_ELIGIBLE", "PAYOUT_SECURITY_NOT_VERIFIED",
                "PAYOUT_ARREARS_UNCLEARED", "PAYOUT_ACCOUNT_MISSING", "PAYOUT_TICKET_NOT_IN_GOOD_STANDING", "NO_DIVIDEND_BENEFICIARIES", "NO_BIDS"}) {
            assertEquals(422, ErrorStatus.of(code), code);
        }
        assertEquals(422, ErrorStatus.of("SOMETHING_NEW_NOBODY_LISTED"));
    }

    @Test
    void internalConsistencyFailuresAreServerErrors() {
        assertEquals(500, ErrorStatus.of("JOURNAL_UNBALANCED"));
        assertEquals(500, ErrorStatus.of("ACCOUNT_MISSING"));
        assertEquals(500, ErrorStatus.of("DIVIDEND_SHARES_MISMATCH"));
        assertEquals(500, ErrorStatus.of("SETTLEMENT_UNBALANCED"));
    }
}
