package com.chitfund.service.businessdate;

import com.chitfund.service.common.ChitException;

import java.time.LocalDate;

/**
 * The pure rules for moving the business date, kept apart from the database so they can be tested on their own.
 * End of day for day D closes D and opens D+1. A repeat of the same call (the batch service retrying after a lost
 * answer) must not move the date a second time, so the caller says which day it is closing.
 */
public final class BusinessDateRules {

    public static final String OPEN = "OPEN";
    public static final String EOD_RUNNING = "EOD_RUNNING";

    private BusinessDateRules() {
    }

    /** What an advance call should do. */
    public enum Outcome {
        /** Move the date to the next day and set the status to OPEN. */
        MOVE,
        /** The date is already the day after the one the caller is closing: the call was repeated; change nothing. */
        ALREADY_DONE
    }

    public static Outcome advance(LocalDate current, LocalDate expected) {
        if (expected == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "expectedBusinessDate is required: the day being closed");
        }
        if (current.equals(expected)) {
            return Outcome.MOVE;
        }
        if (current.equals(expected.plusDays(1))) {
            return Outcome.ALREADY_DONE;
        }
        throw ChitException.conflict("BUSINESS_DATE_MISMATCH",
                "The business date is " + current + ", not " + expected + ". Check the screen and try again.");
    }

    /** Start of end of day: only an OPEN day can start; a day already in EOD_RUNNING is refused. */
    public static void requireOpenToStart(String status, LocalDate current) {
        if (EOD_RUNNING.equals(status)) {
            throw ChitException.conflict("EOD_ALREADY_RUNNING", "End of day for " + current + " has already started.");
        }
    }

    /** Setting the date by hand (test environments only): forward or the same day; going back is done by clearing the data. */
    public static void requireNotBackwards(LocalDate current, LocalDate requested) {
        if (requested == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "businessDate is required");
        }
        if (requested.isBefore(current)) {
            throw ChitException.conflict("BUSINESS_DATE_BACKWARDS",
                    "The business date is " + current + "; it cannot be moved back to " + requested
                            + ". Clear all data to start again from today.");
        }
    }
}
