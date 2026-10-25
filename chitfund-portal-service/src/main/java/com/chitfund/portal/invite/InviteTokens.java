package com.chitfund.portal.invite;

import com.chitfund.portal.common.Hashing;

import java.security.SecureRandom;
import java.util.Base64;

/** The secret in an invite link, and the hash that is stored instead of it. */
public final class InviteTokens {

    /** 32 random bytes = 256 bits, which is more than the 128 bits the spec asks for. */
    private static final int BYTES = 32;

    private InviteTokens() {
    }

    public static String generate(SecureRandom random) {
        byte[] b = new byte[BYTES];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String hash(String token) {
        return Hashing.sha256(token);
    }

    /** A token as the link carries it: URL-safe base64 of 32 bytes, 43 characters. Anything else cannot be one of ours. */
    public static boolean plausible(String token) {
        return token != null && token.length() == 43 && token.chars().allMatch(c ->
                (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_');
    }

    /** What may appear in a log: a short prefix of the hash, never the token. */
    public static String logRef(String token) {
        return hash(token).substring(0, 8);
    }
}
