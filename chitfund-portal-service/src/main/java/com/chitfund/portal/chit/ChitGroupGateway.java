package com.chitfund.portal.chit;

import java.util.Optional;

/** The portal's way into chitfund-service. An interface so tests use a fake; the real one calls REST. */
public interface ChitGroupGateway {

    /** The group, or empty if chitfund-service says there is none. Throws PortalException CHIT_SERVICE_UNAVAILABLE if it cannot be reached. */
    Optional<GroupSummary> group(String groupCode);
}
