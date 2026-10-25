package com.chitfund.portal.auth;

/** The signed-in member of the current request. Every member-facing call is scoped to {@code memberId}, never to an id the caller sends. */
public record SessionMember(long memberId, String email, String displayName) {
}
