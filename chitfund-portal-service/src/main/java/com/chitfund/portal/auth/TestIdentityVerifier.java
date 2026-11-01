package com.chitfund.portal.auth;

import com.chitfund.portal.common.Emails;
import com.chitfund.portal.common.PortalException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * FOR THE END-TO-END TEST SERVICE ONLY. Exists only when the Spring profile {@code e2e} is active (set SPRING_PROFILES_ACTIVE=e2e on a
 * test deployment). It accepts a made-up token of the form {@code e2e|<google subject>|<email>|<display name>} instead of a Google ID
 * token, so the test service can sign in as many mock Gmail customers without Google. Everything after sign-in (sessions, matching of
 * Gmail addresses by Google's rules, seat requests) is the real code.
 *
 * <p>Never switch the {@code e2e} profile on in production: anyone could then sign in as any address. The real verifier carries
 * {@code @Profile("!e2e")}, so the two never exist together; {@code TestIdentityVerifierTest} checks both annotations.
 */
@Component
@Profile("e2e")
public class TestIdentityVerifier implements GoogleIdentityVerifier {

    public static final String PREFIX = "e2e|";

    @Override
    public GoogleIdentity verify(String idToken, OffsetDateTime now) {
        if (idToken == null || !idToken.startsWith(PREFIX) || idToken.length() > 1024) {
            throw new PortalException("INVALID_ID_TOKEN", "The Google sign-in could not be verified (not a test token)");
        }
        String[] p = idToken.split("\\|", 4);
        if (p.length < 3 || p[1].isBlank()) {
            throw new PortalException("INVALID_ID_TOKEN", "The Google sign-in could not be verified (bad test token)");
        }
        String email = Emails.normalise(p[2]);
        if (email == null) {
            throw new PortalException("INVALID_ID_TOKEN", "The Google sign-in could not be verified (no usable email)");
        }
        String name = p.length > 3 && !p[3].isBlank() ? p[3] : null;
        return new GoogleIdentity(p[1], email, name);
    }
}
