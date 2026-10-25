package com.chitfund.portal.chit;

import java.math.BigDecimal;
import java.time.LocalDate;

/** What the portal needs to know about a chit group, as chitfund-service reports it. */
public record GroupSummary(String groupCode, String name, String status, BigDecimal chitValue, Integer ticketCount, Integer cycleCount,
                           BigDecimal subscription, LocalDate startDate, Integer seatsLeft) {

    public boolean openForEnrolment() {
        return "OPEN_FOR_ENROLMENT".equals(status);
    }
}
