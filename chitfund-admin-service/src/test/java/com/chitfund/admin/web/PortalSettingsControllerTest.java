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

/** Spring-free: settings are forwarded to the portal service with the acting name; an empty body is refused first. */
class PortalSettingsControllerTest {

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
    private final PortalSettingsController controller = new PortalSettingsController(portal);
    private final Principal user = () -> "chit-ops";

    @Test
    void settingsAreReadAndSavedThroughThePortal() throws Exception {
        controller.get();
        controller.save(new ObjectMapper().readTree("{\"help_phone\":\"98765\"}"), "ravi", user);
        assertThat(portal.calls).extracting(Call::path).containsExactly("/settings", "/settings");
        assertThat(portal.calls.get(1).method()).isEqualTo(HttpMethod.POST);
        assertThat(portal.calls.get(1).actor()).isEqualTo("ravi");
    }

    @Test
    void anEmptyBodyIsRefusedBeforeAnyCall() throws Exception {
        assertThatThrownBy(() -> controller.save(null, "ravi", user)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.save(new ObjectMapper().readTree("{}"), "ravi", user)).isInstanceOf(IllegalArgumentException.class);
        assertThat(portal.calls).isEmpty();
    }
}
