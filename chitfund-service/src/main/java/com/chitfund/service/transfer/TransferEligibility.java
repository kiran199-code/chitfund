package com.chitfund.service.transfer;

import com.chitfund.service.common.ChitException;
import com.chitfund.service.domain.GroupStatus;
import com.chitfund.service.domain.TicketStatus;

import java.math.BigDecimal;

/**
 * The rules for handing a ticket to someone else, as one pure check so that request and approval apply the same ones.
 * The first rule that fails is reported, in the order below (the most basic first).
 *
 * <ol>
 *   <li>The group must be running (ACTIVE or CLOSING). Before activation a member withdraws and the seat is enrolled again.</li>
 *   <li>No other transfer is waiting for a decision on the ticket.</li>
 *   <li>The ticket must be ACTIVE or PRIZED, or DEFAULTED (a defaulted ticket can only go to a replacement once its arrears have
 *       been cleared or written off). A REPLACEMENT exit is for a ticket that is ACTIVE and has not won.</li>
 *   <li>The new holder must be someone else, and not the guarantor named on the ticket's security.</li>
 *   <li>No arrears: nothing overdue on the ticket's demands (instalments and late fees) and nothing owed to the foreman on covers.</li>
 *   <li>The prize of a cycle this ticket won must not still be waiting to be paid (it would be unclear who is paid).</li>
 * </ol>
 */
public final class TransferEligibility {

    private TransferEligibility() {
    }

    public record Facts(GroupStatus group, TicketStatus ticket, boolean hasWon, long currentCustomerId, long newCustomerId,
                        Long guarantorCustomerId, BigDecimal overdueOutstanding, BigDecimal openCoverBalance,
                        boolean prizePayoutInProgress, boolean otherTransferPending) {
    }

    public static void check(TransferKind kind, Facts f) {
        if (f.group() != GroupStatus.ACTIVE && f.group() != GroupStatus.CLOSING) {
            throw ChitException.conflict("GROUP_NOT_ACTIVE", "The group is " + f.group() + "; tickets are handed over while it is ACTIVE or CLOSING"
                    + (f.group() == GroupStatus.OPEN_FOR_ENROLMENT || f.group() == GroupStatus.FULL
                    ? " (before it starts, withdraw the ticket and enrol the new member)" : ""));
        }
        if (f.otherTransferPending()) {
            throw ChitException.conflict("TRANSFER_PENDING", "Another transfer of this ticket is waiting for a decision");
        }
        if (kind == TransferKind.REPLACEMENT) {
            if (f.hasWon() || f.ticket() == TicketStatus.PRIZED) {
                throw ChitException.conflict("EXIT_PRIZED_TICKET", "A ticket that has won its prize cannot exit through a replacement; "
                        + "it can be transferred once its arrears are cleared");
            }
            if (f.ticket() != TicketStatus.ACTIVE) {
                throw ChitException.conflict("TRANSFER_TICKET_NOT_ELIGIBLE", "A ticket exits through a replacement while it is ACTIVE; this one is " + f.ticket());
            }
        } else if (f.ticket() != TicketStatus.ACTIVE && f.ticket() != TicketStatus.PRIZED && f.ticket() != TicketStatus.DEFAULTED) {
            throw ChitException.conflict("TRANSFER_TICKET_NOT_ELIGIBLE", "A ticket can be transferred while it is ACTIVE, PRIZED or DEFAULTED; this one is " + f.ticket());
        }
        if (f.newCustomerId() == f.currentCustomerId()) {
            throw new ChitException("TRANSFER_SAME_CUSTOMER", "The new holder is already the holder of this ticket");
        }
        if (f.guarantorCustomerId() != null && f.guarantorCustomerId() == f.newCustomerId()) {
            throw new ChitException("TRANSFER_GUARANTOR_CONFLICT", "The new holder is the guarantor named on this ticket's security; a holder cannot guarantee their own ticket");
        }
        if (f.overdueOutstanding().signum() > 0 || f.openCoverBalance().signum() > 0) {
            throw new ChitException("TRANSFER_ARREARS_EXIST", "The ticket still owes " + f.overdueOutstanding().toPlainString()
                    + " on overdue instalments and late fees and " + f.openCoverBalance().toPlainString()
                    + " to the foreman on covers; arrears are cleared (or written off) before the ticket changes hands");
        }
        if (f.prizePayoutInProgress()) {
            throw ChitException.conflict("TRANSFER_PAYOUT_PENDING", "A prize won by this ticket has not been paid out yet; finish the payout first");
        }
    }
}
