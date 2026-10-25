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

/** Spring-free: invitations are forwarded to the portal service under the right path, acting name and key, and malformed input is refused first. */
class PortalInviteControllerTest {

    record Call(HttpMethod method, String path, Map<String, ?> query, Object body, String actor, String key) {}

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
        public JsonNode exchange(HttpMethod method, String path, Map<String, ?> query, Object body, String actor, String idempotencyKey) {
            calls.add(new Call(method, path, query, body, actor, idempotencyKey));
            return null;
        }

        Call last() {
            return calls.get(calls.size() - 1);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Recording portal = new Recording();
    private final PortalInviteController controller = new PortalInviteController(portal);
    private final Principal user = () -> "chit-ops";

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void invitingNeedsPeopleAndForwardsTheKeyAndActor() {
        assertThatThrownBy(() -> controller.invite("G1", json("{\"invitees\":[]}"), "k1", null, user)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.invite("G 1", json("{\"invitees\":[{\"email\":\"a@x.com\"}]}"), "k1", null, user))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("code");
        assertThat(portal.calls).isEmpty();

        controller.invite("G1", json("{\"invitees\":[{\"email\":\"a@x.com\",\"tickets\":2}]}"), "k1", "ravi", user);
        assertThat(portal.last().method()).isEqualTo(HttpMethod.POST);
        assertThat(portal.last().path()).isEqualTo("/groups/G1/invitations");
        assertThat(portal.last().key()).isEqualTo("k1");
        assertThat(portal.last().actor()).isEqualTo("ravi");
    }

    @Test
    void aMissingKeyIsGeneratedAndTheLoginIsTheActorByDefault() {
        controller.resend("G1", 7, null, null, user);
        assertThat(portal.last().path()).isEqualTo("/groups/G1/invitations/7/resend");
        assertThat(portal.last().key()).isNotBlank();
        assertThat(portal.last().actor()).isEqualTo("chit-ops");
    }

    @Test
    void enrolNeedsTheFeesToBeConfirmed() {
        assertThatThrownBy(() -> controller.enrol("G1", 7, json("{\"feesReceived\":false}"), null, user)).isInstanceOf(IllegalArgumentException.class);
        assertThat(portal.calls).isEmpty();
        controller.enrol("G1", 7, json("{\"feesReceived\":true,\"payoutAccountRef\":\"A1\"}"), null, user);
        assertThat(portal.last().path()).isEqualTo("/groups/G1/invitations/7/enrol");
    }

    @Test
    void revokeAndListGoToTheirPaths() {
        controller.revoke("G1", 7, null, null, user);
        assertThat(portal.last().path()).isEqualTo("/groups/G1/invitations/7/revoke");
        controller.list("G1", "PENDING");
        assertThat(portal.last().method()).isEqualTo(HttpMethod.GET);
        assertThat(portal.last().query()).containsEntry("status", "PENDING");
        assertThatThrownBy(() -> controller.list("G1", "pending; drop")).isInstanceOf(IllegalArgumentException.class);
    }
}
