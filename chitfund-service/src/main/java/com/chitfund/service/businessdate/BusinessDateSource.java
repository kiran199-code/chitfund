package com.chitfund.service.businessdate;

import java.time.LocalDate;

/**
 * The chitfund business date. Every date the service stamps on a posting or checks a rule against comes from here,
 * never from the wall clock directly, so a test environment can move the date and every rule follows it.
 */
public interface BusinessDateSource {

    /** The current business date. */
    LocalDate today();
}
