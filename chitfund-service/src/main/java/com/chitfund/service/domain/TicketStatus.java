package com.chitfund.service.domain;

/**
 * Ticket lifecycle. See Lifecycles.TICKET.
 */
public enum TicketStatus {
    ENROLLED,
    ACTIVE,
    PRIZED,
    COMPLETED,
    DEFAULTED,
    TRANSFERRED,
    EXITED
}
