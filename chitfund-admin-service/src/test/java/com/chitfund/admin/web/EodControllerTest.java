package com.chitfund.admin.web;

import com.chitfund.admin.common.UpstreamException;
import com.chitfund.admin.support.FakeBatch;
import com.chitfund.admin.support.FakeChit;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spring-free: the console checks the date on screen against the batch service's before it starts end of day. */
class EodControllerTest {

    private final FakeBatch batch = new FakeBatch();
    private final FakeChit chit = new FakeChit();
    private final EodController controller = new EodController(batch, chit);

    private static final String CHIT_DAY = "{\"businessDate\":\"2026-11-02\",\"status\":\"OPEN\",\"mode\":\"MANAGED\"}";

    @Test
    void startingWithoutTheDateOnScreenIsRefused() {
        assertThatThrownBy(() -> controller.run(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.run(Map.of("expectedBusinessDate", "02/11/2026"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(batch.calls).isEmpty();
    }

    @Test
    void startingWhenTheBatchServiceSaysTheDateHasMovedOnIsRefusedWith409() {
        batch.failWith = new UpstreamException(409, "The business date is now 2026-11-03, not 2026-11-02.");
        assertThatThrownBy(() -> controller.run(Map.of("expectedBusinessDate", "2026-11-02")))
                .isInstanceOfSatisfying(UpstreamException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("2026-11-03");
                });
    }

    @Test
    void startingForwardsTheDateOnScreenToTheBatchService() {
        batch.eodRunAnswer = FakeBatch.json("{\"started\":true}");
        JsonNode r = controller.run(Map.of("expectedBusinessDate", "2026-11-02"));
        assertThat(r.path("started").asBoolean()).isTrue();
        assertThat(batch.calls).containsExactly("eodRun 2026-11-02");
    }

    @Test
    void statusCarriesTheBatchProgressAndTheChitServicesDate() {
        chit.answer(HttpMethod.GET, "/business-date", CHIT_DAY);
        batch.eodStatus = FakeBatch.json("{\"running\":false,\"businessDate\":{\"businessDate\":\"2026-11-02\",\"status\":\"OPEN\"},"
                + "\"progress\":{\"state\":\"IDLE\"},\"lastRun\":null}");

        JsonNode s = controller.status();

        assertThat(s.path("reachable").asBoolean()).isTrue();
        assertThat(s.path("progress").path("state").asText()).isEqualTo("IDLE");
        assertThat(s.path("businessDate").path("businessDate").asText()).isEqualTo("2026-11-02");
        assertThat(s.path("chitBusinessDate").asText()).isEqualTo("2026-11-02");
        assertThat(s.path("chitStatus").asText()).isEqualTo("OPEN");
        assertThat(s.path("chitMode").asText()).isEqualTo("MANAGED");
    }

    @Test
    void statusStillShowsTheChitDateWhenTheBatchServiceCannotBeReached() {
        chit.answer(HttpMethod.GET, "/business-date", CHIT_DAY);
        batch.failWith = new UpstreamException(502, "Could not reach chitfund-batch-service");

        JsonNode s = controller.status();

        assertThat(s.path("reachable").asBoolean()).isFalse();
        assertThat(s.path("error").asText()).contains("Could not reach");
        assertThat(s.path("chitBusinessDate").asText()).isEqualTo("2026-11-02");
    }

    @Test
    void statusStillAnswersWhenTheChitServiceIsDown() {
        chit.failWith = new UpstreamException(502, "Could not reach chitfund-service");
        batch.eodStatus = FakeBatch.json("{\"running\":false,\"progress\":{\"state\":\"IDLE\"}}");

        JsonNode s = controller.status();

        assertThat(s.path("reachable").asBoolean()).isTrue();
        assertThat(s.path("chitBusinessDate").isNull()).isTrue();
        assertThat(s.path("chitError").asText()).contains("chitfund-service");
    }

    @Test
    void withNoBatchServiceConfiguredTheStatusSaysSo() {
        batch.enabled = false;
        chit.answer(HttpMethod.GET, "/business-date", CHIT_DAY);

        JsonNode s = controller.status();

        assertThat(s.path("configured").asBoolean()).isFalse();
        assertThat(s.path("reachable").asBoolean()).isFalse();
        assertThat(s.path("error").asText()).contains("BATCH_SERVICE_URL");
        assertThat(batch.calls).isEmpty();
    }
}
