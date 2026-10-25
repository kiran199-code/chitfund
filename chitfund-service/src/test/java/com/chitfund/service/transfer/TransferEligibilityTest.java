package com.chitfund.service.transfer;

import com.chitfund.service.common.ChitException;
import com.chitfund.service.domain.GroupStatus;
import com.chitfund.service.domain.TicketStatus;
import com.chitfund.service.transfer.TransferEligibility.Facts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransferEligibilityTest {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /** An ACTIVE, non-prized ticket of customer 10 going to customer 20, nothing owed, nothing pending. */
    private static Facts ok() {
        return new Facts(GroupStatus.ACTIVE, TicketStatus.ACTIVE, false, 10, 20, null, ZERO, ZERO, false, false);
    }

    private static Facts with(GroupStatus g, TicketStatus t, boolean won, Long guarantor, BigDecimal overdue, BigDecimal covers, boolean payout, boolean pending, long to) {
        return new Facts(g, t, won, 10, to, guarantor, overdue, covers, payout, pending);
    }

    private static String code(TransferKind k, Facts f) {
        return assertThrows(ChitException.class, () -> TransferEligibility.check(k, f)).code();
    }

    @Test
    void aCleanActiveTicketCanBeTransferredOrExit() {
        TransferEligibility.check(TransferKind.TRANSFER, ok());
        TransferEligibility.check(TransferKind.REPLACEMENT, ok());
    }

    @Test
    void aPrizedTicketCanBeTransferredButCannotExit() {
        Facts prized = with(GroupStatus.ACTIVE, TicketStatus.PRIZED, true, null, ZERO, ZERO, false, false, 20);
        TransferEligibility.check(TransferKind.TRANSFER, prized);
        assertEquals("EXIT_PRIZED_TICKET", code(TransferKind.REPLACEMENT, prized));
    }

    @Test
    void anActiveTicketThatHasWonCannotExitEvenIfTheStatusLagsBehind() {
        Facts wonButActive = with(GroupStatus.ACTIVE, TicketStatus.ACTIVE, true, null, ZERO, ZERO, false, false, 20);
        assertEquals("EXIT_PRIZED_TICKET", code(TransferKind.REPLACEMENT, wonButActive));
    }

    @Test
    void aDefaultedTicketMayBeTransferredOnceArrearsAreClearedButNeverExitsAsVoluntary() {
        Facts defaulted = with(GroupStatus.ACTIVE, TicketStatus.DEFAULTED, false, null, ZERO, ZERO, false, false, 20);
        TransferEligibility.check(TransferKind.TRANSFER, defaulted);
        assertEquals("TRANSFER_TICKET_NOT_ELIGIBLE", code(TransferKind.REPLACEMENT, defaulted));
    }

    @Test
    void aDefaultedTicketWithArrearsIsRefused() {
        Facts owing = with(GroupStatus.ACTIVE, TicketStatus.DEFAULTED, false, null, new BigDecimal("5000.00"), ZERO, false, false, 20);
        assertEquals("TRANSFER_ARREARS_EXIST", code(TransferKind.TRANSFER, owing));
    }

    @Test
    void ticketsInOtherStatesAreRefused() {
        for (TicketStatus s : new TicketStatus[]{TicketStatus.ENROLLED, TicketStatus.COMPLETED, TicketStatus.EXITED, TicketStatus.TRANSFERRED}) {
            Facts f = with(GroupStatus.ACTIVE, s, false, null, ZERO, ZERO, false, false, 20);
            assertEquals("TRANSFER_TICKET_NOT_ELIGIBLE", code(TransferKind.TRANSFER, f), s.name());
        }
    }

    @Test
    void theGroupMustBeRunning() {
        for (GroupStatus g : new GroupStatus[]{GroupStatus.DRAFT, GroupStatus.OPEN_FOR_ENROLMENT, GroupStatus.FULL, GroupStatus.CLOSED, GroupStatus.CANCELLED}) {
            Facts f = with(g, TicketStatus.ACTIVE, false, null, ZERO, ZERO, false, false, 20);
            assertEquals("GROUP_NOT_ACTIVE", code(TransferKind.TRANSFER, f), g.name());
        }
        TransferEligibility.check(TransferKind.TRANSFER, with(GroupStatus.CLOSING, TicketStatus.ACTIVE, false, null, ZERO, ZERO, false, false, 20));
    }

    @Test
    void theNewHolderMustBeSomeoneElse() {
        assertEquals("TRANSFER_SAME_CUSTOMER", code(TransferKind.TRANSFER, with(GroupStatus.ACTIVE, TicketStatus.ACTIVE, false, null, ZERO, ZERO, false, false, 10)));
    }

    @Test
    void theNewHolderCannotBeTheGuarantorOfTheTicket() {
        assertEquals("TRANSFER_GUARANTOR_CONFLICT", code(TransferKind.TRANSFER, with(GroupStatus.ACTIVE, TicketStatus.ACTIVE, false, 20L, ZERO, ZERO, false, false, 20)));
        TransferEligibility.check(TransferKind.TRANSFER, with(GroupStatus.ACTIVE, TicketStatus.ACTIVE, false, 99L, ZERO, ZERO, false, false, 20));
    }

    @Test
    void arrearsOfEitherKindBlockTheTransfer() {
        assertEquals("TRANSFER_ARREARS_EXIST", code(TransferKind.TRANSFER, with(GroupStatus.ACTIVE, TicketStatus.ACTIVE, false, null, new BigDecimal("0.01"), ZERO, false, false, 20)));
        assertEquals("TRANSFER_ARREARS_EXIST", code(TransferKind.REPLACEMENT, with(GroupStatus.ACTIVE, TicketStatus.ACTIVE, false, null, ZERO, new BigDecimal("5000.00"), false, false, 20)));
    }

    @Test
    void aPrizeStillWaitingToBePaidBlocksTheTransfer() {
        assertEquals("TRANSFER_PAYOUT_PENDING", code(TransferKind.TRANSFER, with(GroupStatus.ACTIVE, TicketStatus.PRIZED, true, null, ZERO, ZERO, true, false, 20)));
    }

    @Test
    void anotherPendingTransferIsReportedAsThat() {
        // the ticket is TRANSFERRED while a request waits, so the pending check must come before the status check
        Facts f = with(GroupStatus.ACTIVE, TicketStatus.TRANSFERRED, false, null, ZERO, ZERO, false, true, 20);
        assertEquals("TRANSFER_PENDING", code(TransferKind.TRANSFER, f));
    }
}
