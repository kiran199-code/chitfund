package com.chitfund.admin.web;

import com.chitfund.admin.service.PortalClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spring-free: offers are forwarded to the portal service under the right path and acting name; a missing body is refused first. */
class PortalOfferControllerTest {

    record Call(HttpMethod method, String path, Object body, String actor) {}

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
            calls.add(new Call(HttpMethod.GET, path, null, null));
            return null;
        }

        @Override
        public JsonNode exchange(HttpMethod method, String path, Map<String, ?> query, Object body, String actor, String idempotencyKey) {
            calls.add(new Call(method, path, body, actor));
            return null;
        }
    }

    private final Recording portal = new Recording();
    private final PortalOfferController controller = new PortalOfferController(portal);
    private final Principal user = () -> "chit-ops";

    @Test
    void offersAreForwardedWithTheActingName() throws Exception {
        JsonNode body = new ObjectMapper().readTree("{\"title\":\"Diwali\",\"audience\":\"PUBLIC\",\"startsOn\":\"2026-10-25\"}");
        controller.list();
        controller.create(body, "ravi", user);
        controller.update(7, body, null, user);
        controller.hide(7, "ravi", user);
        controller.show(7, "ravi", user);
        assertThat(portal.calls).extracting(Call::path).containsExactly("/offers", "/offers", "/offers/7", "/offers/7/hide", "/offers/7/show");
        assertThat(portal.calls.get(1).actor()).isEqualTo("ravi");
        assertThat(portal.calls.get(2).actor()).isNotBlank();
    }

    @Test
    void aMissingOrNonObjectBodyIsRefusedBeforeAnyCall() throws Exception {
        assertThatThrownBy(() -> controller.create(null, "ravi", user)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.update(1, new ObjectMapper().readTree("[1]"), "ravi", user)).isInstanceOf(IllegalArgumentException.class);
        assertThat(portal.calls).isEmpty();
    }
}
