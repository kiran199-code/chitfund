package com.chitfund.admin.web;

import com.chitfund.admin.service.PortalClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spring-free: seat-request decisions go to the portal service under the right path and acting name; bad input is refused first. */
@SuppressWarnings("unchecked")
class PortalJoinRequestControllerTest {

    record Call(HttpMethod method, String path, Map<String, Object> query, Object body, String actor) {}

    static class Recording extends PortalClient {
        final List<Call> calls = new ArrayList<>();

        Recording() {
            super("", "portal-admin", "");
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public JsonNode get(String path, Map<String, ?> query) {
            calls.add(new Call(HttpMethod.GET, path, new LinkedHashMap<String, Object>(query), null, null));
            return null;
        }

        @Override
        public JsonNode exchange(HttpMethod method, String path, Map<String, ?> query, Object body, String actor, String idempotencyKey) {
            calls.add(new Call(method, path, new LinkedHashMap<String, Object>(query), body, actor));
            return null;
        }
    }

    private final Recording portal = new Recording();
    private final PortalJoinRequestController controller = new PortalJoinRequestController(portal);
    private final Principal user = () -> "chit-ops";

    @Test
    void listFiltersByStatusAndDecisionsCarryNoteAndActor() throws Exception {
        JsonNode note = new ObjectMapper().readTree("{\"note\":\" KYC done \"}");
        assertThat(controller.status()).containsEntry("enabled", true);
        controller.list(null);
        controller.list("PENDING");
        controller.approve(5, note, "ravi", user);
        controller.decline(6, null, null, user);
        assertThat(portal.calls).extracting(Call::path).containsExactly("/join-requests", "/join-requests", "/join-requests/5/approve", "/join-requests/6/decline");
        assertThat(portal.calls.get(0).query()).isEmpty();
        assertThat(portal.calls.get(1).query()).containsEntry("status", "PENDING");
        assertThat(portal.calls.get(2).actor()).isEqualTo("ravi");
        assertThat((Map<String, Object>) portal.calls.get(2).body()).containsEntry("note", "KYC done");
        assertThat(portal.calls.get(3).actor()).isEqualTo("chit-ops");
        assertThat((Map<String, Object>) portal.calls.get(3).body()).isEmpty();
    }

    @Test
    void badStatusAndLongNoteAreRefused() throws Exception {
        assertThatThrownBy(() -> controller.list("x;drop")).isInstanceOf(IllegalArgumentException.class);
        JsonNode long301 = new ObjectMapper().readTree("{\"note\":\"" + "a".repeat(301) + "\"}");
        assertThatThrownBy(() -> controller.approve(1, long301, null, user)).isInstanceOf(IllegalArgumentException.class);
        assertThat(portal.calls).isEmpty();
    }
}
