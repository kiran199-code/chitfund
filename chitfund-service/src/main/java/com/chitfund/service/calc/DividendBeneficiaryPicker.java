package com.chitfund.service.calc;

import com.chitfund.service.domain.DividendBeneficiaries;

import java.util.List;

/**
 * Which tickets share a cycle's dividend pool.
 *
 * <p>Assumptions (to confirm, see BUILD_STATUS.md): ALL_TICKETS means every ticket still in the group, the winner of the
 * cycle included. NON_PRIZED_ONLY means tickets that have not won, and the winner of this cycle does not count as
 * non-prized. A defaulted ticket gets nothing when {@code defaulterLosesDividend} is on, and its share is then split among
 * the others. An exited ticket never shares.
 */
public final class DividendBeneficiaryPicker {

    /** A ticket as the picker sees it. */
    public record Candidate(int ticketNo, boolean exited, boolean defaulted, boolean hasWon) {
    }

    private DividendBeneficiaryPicker() {
    }

    public static List<Integer> pick(List<Candidate> tickets, DividendBeneficiaries rule, boolean defaulterLosesDividend, int winnerTicketNo) {
        return tickets.stream()
                .filter(t -> !t.exited())
                .filter(t -> !(defaulterLosesDividend && t.defaulted()))
                .filter(t -> rule == DividendBeneficiaries.ALL_TICKETS || (!t.hasWon() && t.ticketNo() != winnerTicketNo))
                .map(Candidate::ticketNo)
                .sorted()
                .toList();
    }
}
