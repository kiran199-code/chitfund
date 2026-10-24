package com.chitfund.service.domain;

/**
 * Where a payment is applied. ADVANCE always comes last and is not part of the configurable order.
 */
public enum AllocationBucket {
    LATE_FEES,
    ARREARS,
    CURRENT,
    ADVANCE
}
