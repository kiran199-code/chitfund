package com.chitfund.service.ledger;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.chitfund.service.ledger.AccountType.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Phase 6 journals, run through the same arithmetic the ledger uses: recovery, foreman cover, write-off, waived fee, transfer fee. */
class RecoveryJournalTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, d(expected).compareTo(actual), "expected " + expected + " but was " + actual.toPlainString());
    }

    private static void balanced(LedgerBalances b) {
        assertEquals(0, b.totalDebits().compareTo(b.totalCredits()), "the books must balance");
    }

    @Test
    void depositAppliedToArrearsPaysThePoolFromSecurityCash() {
        LedgerBalances b = new LedgerBalances()
                .apply(JournalTemplates.securityReceived("S", 7, d("10000")))
                .apply(JournalTemplates.demand("D", 7, d("5000")))
                .apply(JournalTemplates.securityApplied("SA", 7, d("5000")));
        eq("0", b.balance(SUBSCRIBER_RECEIVABLE, 7));
        eq("5000", b.balance(POOL_CASH));
        eq("5000", b.balance(SECURITY_CASH));
        eq("5000", b.balance(SECURITY_DEPOSIT_LIABILITY, 7));
        balanced(b);
    }

    @Test
    void depositAppliedToTheForemansCoverPaysTheForemanFromSecurityCash() {
        LedgerBalances b = new LedgerBalances()
                .apply(JournalTemplates.securityReceived("S", 7, d("10000")))
                .apply(JournalTemplates.demand("D", 7, d("5000")))
                .apply(JournalTemplates.foremanCovers("C", 7, d("5000")))
                .apply(JournalTemplates.securityAppliedToGuarantee("SG", 7, d("5000")));
        eq("0", b.balance(FOREMAN_GUARANTEE_RECEIVABLE, 7));
        eq("0", b.balance(SUBSCRIBER_RECEIVABLE, 7));
        eq("5000", b.balance(POOL_CASH));      // the foreman's cover put the instalment into the pool
        eq("0", b.balance(FOREMAN_CASH));      // he paid 5000 out and got 5000 back from the deposit
        eq("5000", b.balance(SECURITY_CASH));
        eq("5000", b.balance(SECURITY_DEPOSIT_LIABILITY, 7));
        balanced(b);
    }

    @Test
    void aWriteOffUnderForemanBearsLossLeavesThePoolWholeAndTheForemanOutOfPocket() {
        // the ticket owes 5000 and 150 of late fee; the foreman pays the instalment into the pool and everything is written off
        LedgerBalances b = new LedgerBalances()
                .apply(JournalTemplates.demand("D", 7, d("5000")))
                .apply(JournalTemplates.lateFee("L", 7, d("150")))
                .apply(JournalTemplates.foremanCovers("C", 7, d("5000")))
                .apply(JournalTemplates.lateFeeWaived("W", 7, d("150")))
                .apply(JournalTemplates.guaranteeWrittenOff("G", 7, d("5000")));
        eq("0", b.balance(SUBSCRIBER_RECEIVABLE, 7));
        eq("0", b.balance(FOREMAN_GUARANTEE_RECEIVABLE, 7));
        eq("5000", b.balance(POOL_CASH));               // pool is whole: the instalment arrived
        eq("5000", b.balance(CHIT_POOL));               // and the pool's claim equals its cash
        eq("-5000", b.balance(FOREMAN_CASH));           // foreman is out of pocket by the cover
        eq("5000", b.balance(GUARANTEE_LOSS_EXPENSE));  // recognised as his expense
        eq("0", b.balance(LATE_FEE_INCOME));            // the fee he never received is no longer his income
        balanced(b);
    }

    @Test
    void aCoverRepaidBeforeTheWriteOffCostsTheForemanOnlyTheRest() {
        LedgerBalances b = new LedgerBalances()
                .apply(JournalTemplates.demand("D", 7, d("5000")))
                .apply(JournalTemplates.foremanCovers("C", 7, d("5000")))
                .apply(JournalTemplates.guaranteeRepaid("R", 7, d("2000")))
                .apply(JournalTemplates.guaranteeWrittenOff("G", 7, d("3000")));
        eq("0", b.balance(FOREMAN_GUARANTEE_RECEIVABLE, 7));
        eq("-3000", b.balance(FOREMAN_CASH));
        eq("3000", b.balance(GUARANTEE_LOSS_EXPENSE));
        balanced(b);
    }

    @Test
    void theTransferFeeIsForemanIncome() {
        LedgerBalances b = new LedgerBalances().apply(JournalTemplates.transferFee("T", d("250")));
        eq("250", b.balance(FOREMAN_CASH));
        eq("250", b.balance(TRANSFER_FEE_INCOME));
        balanced(b);
    }
}
