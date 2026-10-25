package com.chitfund.portal.auth;

import com.chitfund.portal.common.PortalException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Verifies the signature of a Google ID token against Google's published signing keys (the JWK set URL in
 * {@code chit.portal.google.jwk-set-uri}) using Spring Security's Nimbus decoder, then applies {@link GoogleClaimsPolicy}.
 *
 * <p>VERIFY BEFORE USE: the decoder calls below ({@code NimbusJwtDecoder.withJwkSetUri(..).build()}, {@code decode}, {@code getClaims})
 * are written from memory of Spring Security 6 and have only been compiled against a stand-in, never run against Google. Google's key
 * URL and the token claims also need confirming in Google's current documentation. The decoder's default validation covers the
 * token's time window; issuer and audience are checked here.
 */
@Component
public class NimbusGoogleIdentityVerifier implements GoogleIdentityVerifier {

    private final String clientId;
    private final String jwkSetUri;
    private volatile JwtDecoder decoder;

    public NimbusGoogleIdentityVerifier(@Value("${chit.portal.google.client-id:}") String clientId,
                                        @Value("${chit.portal.google.jwk-set-uri}") String jwkSetUri) {
        this.clientId = clientId;
        this.jwkSetUri = jwkSetUri;
    }

    @Override
    public GoogleIdentity verify(String idToken, OffsetDateTime now) {
        if (clientId == null || clientId.isBlank()) {
            throw new PortalException("SIGN_IN_NOT_CONFIGURED", "Google sign-in is not configured (GOOGLE_CLIENT_ID)");
        }
        if (idToken == null || idToken.isBlank() || idToken.length() > 4096) {
            throw new PortalException("INVALID_ID_TOKEN", "The Google sign-in could not be verified (no token)");
        }
        Jwt jwt;
        try {
            jwt = decoder().decode(idToken);
        } catch (JwtException e) {
            throw new PortalException("INVALID_ID_TOKEN", "The Google sign-in could not be verified (bad token)");
        }
        return GoogleClaimsPolicy.check(jwt.getClaims(), clientId, now);
    }

    private JwtDecoder decoder() {
        JwtDecoder d = decoder;
        if (d == null) {
            d = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
            decoder = d;
        }
        return d;
    }
}
