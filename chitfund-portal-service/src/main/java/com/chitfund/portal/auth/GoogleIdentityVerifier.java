package com.chitfund.portal.auth;

import java.time.OffsetDateTime;

/** Turns the ID token the browser got from Google into a verified identity, or throws PortalException INVALID_ID_TOKEN. */
public interface GoogleIdentityVerifier {

    GoogleIdentity verify(String idToken, OffsetDateTime now);
}
