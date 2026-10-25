package com.chitfund.portal.member;

/** The organiser's payment instructions (free text), shown to signed-in members on the Payments page. Empty when not set. */
public interface HowToPay {
    String text();
}
