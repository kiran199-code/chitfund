package com.chitfund.service.domain;

/**
 * Security required from a prized subscriber.
 */
public enum SecurityRule {
    NONE,
    GUARANTOR,
    DEPOSIT,
    LIEN_ON_DEPOSIT
}
