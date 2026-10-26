package com.chitfund.portal.chit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The groups chitfund-service has open for enrolment, with how they are labelled for the plan shelf. An interface so tests use a fake. */
public interface ChitPlanGateway {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OpenPlan(String groupCode, String name, String planCategory, String branch, String state, BigDecimal chitValue, int ticketCount, int cycleCount,
                    BigDecimal subscription, String frequency, LocalDate startDate, int seatsLeft) {
    }

    /** Throws PortalException CHIT_SERVICE_UNAVAILABLE if chitfund-service cannot be reached. */
    List<OpenPlan> openPlans();
}
