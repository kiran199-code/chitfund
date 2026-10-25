package com.chitfund.admin.service;

import com.chitfund.admin.common.FeatureDisabledException;
import com.chitfund.admin.common.UpstreamException;
import com.chitfund.admin.support.FakeBatch;
import com.chitfund.admin.support.FakeChit;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spring-free: clearing the chit data and the batch history together, in a safe order. */
class MaintenanceServiceTest {

    private final FakeChit chit = new FakeChit();
    private final FakeBatch batch = new FakeBatch();
    private final MaintenanceService service = new MaintenanceService(chit, batch);

    private void chitAnswers(String dateStatus) {
        chit.answer(HttpMethod.GET, "/business-date", "{\"businessDate\":\"2026-11-02\",\"status\":\"" + dateStatus + "\",\"mode\":\"MANAGED\"}");
        chit.answer(HttpMethod.GET, "/maintenance/status", "{\"resetEnabled\":true,\"rowCounts\":{\"chit_group\":2}}");
        chit.answer(HttpMethod.POST, "/maintenance/clear-data", "{\"totalRows\":9,\"message\":\"Chit data cleared.\"}");
    }

    private void batchIdle(boolean resetEnabled) {
        batch.maintenanceStatus = FakeBatch.json("{\"resetEnabled\":" + resetEnabled + ",\"busy\":false,\"rowCounts\":{\"batch_job_instance\":3}}");
        batch.clearAnswer = FakeBatch.json("{\"totalRows\":4,\"message\":\"Job history cleared.\"}");
    }

    private long clearCalls() {
        return chit.calls.stream().filter(c -> c.path().equals("/maintenance/clear-data")).count();
    }

    @Test
    void statusCombinesChitStatusTheDateAndTheBatchServicesState() {
        chitAnswers("OPEN");
        batchIdle(true);

        JsonNode s = service.status();

        assertThat(s.path("resetEnabled").asBoolean()).isTrue();
        assertThat(s.path("rowCounts").path("chit_group").asInt()).isEqualTo(2);
        assertThat(s.path("businessDate").asText()).isEqualTo("2026-11-02");
        assertThat(s.path("businessDateStatus").asText()).isEqualTo("OPEN");
        assertThat(s.path("batch").path("configured").asBoolean()).isTrue();
        assertThat(s.path("batch").path("reachable").asBoolean()).isTrue();
        assertThat(s.path("batch").path("resetEnabled").asBoolean()).isTrue();
        assertThat(s.path("batch").path("rowCounts").path("batch_job_instance").asInt()).isEqualTo(3);
    }

    @Test
    void statusStillLoadsWhenTheBatchServiceIsAsleep() {
        chitAnswers("OPEN");
        batch.failWith = new UpstreamException(502, "Could not reach chitfund-batch-service");

        JsonNode s = service.status();

        assertThat(s.path("batch").path("configured").asBoolean()).isTrue();
        assertThat(s.path("batch").path("reachable").asBoolean()).isFalse();
        assertThat(s.path("batch").path("resetEnabled").asBoolean()).isFalse();
        assertThat(s.path("batch").path("error").asText()).contains("Could not reach");
    }

    @Test
    void statusWithNoBatchServiceSaysItIsNotConfigured() {
        chitAnswers("OPEN");
        batch.enabled = false;
        JsonNode s = service.status();
        assertThat(s.path("batch").path("configured").asBoolean()).isFalse();
        assertThat(batch.calls).isEmpty();
    }

    @Test
    void aBatchServiceSwitchedOffStopsTheClearBeforeAnythingIsDeleted() {
        chitAnswers("OPEN");
        batchIdle(false);

        assertThatThrownBy(() -> service.clearData("admin"))
                .isInstanceOf(FeatureDisabledException.class).hasMessageContaining("BATCH_RESET_ENABLED");
        assertThat(clearCalls()).isZero();
        assertThat(batch.calls).doesNotContain("maintenanceClear admin");
    }

    @Test
    void aBusyBatchServiceStopsTheClearWith409() {
        chitAnswers("OPEN");
        batch.maintenanceStatus = FakeBatch.json("{\"resetEnabled\":true,\"busy\":true,\"busyReason\":\"End of day is running\"}");

        assertThatThrownBy(() -> service.clearData("admin"))
                .isInstanceOfSatisfying(UpstreamException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("End of day is running").contains("Nothing was deleted");
                });
        assertThat(clearCalls()).isZero();
    }

    @Test
    void anUnreachableBatchServiceStopsTheClear() {
        chitAnswers("OPEN");
        batch.failWith = new UpstreamException(502, "Could not reach chitfund-batch-service");

        assertThatThrownBy(() -> service.clearData("admin")).isInstanceOf(UpstreamException.class);
        assertThat(clearCalls()).isZero();
    }

    @Test
    void aCleanRunClearsTheChitDataThenTheBatchHistoryAndMergesTheMessages() {
        chitAnswers("OPEN");
        batchIdle(true);

        JsonNode r = service.clearData("ravi");

        assertThat(clearCalls()).isEqualTo(1L);
        assertThat(chit.calls.stream().filter(c -> c.path().equals("/maintenance/clear-data")).findFirst().orElseThrow().actor()).isEqualTo("ravi");
        assertThat(batch.calls).contains("maintenanceClear ravi");
        assertThat(r.path("totalRows").asInt()).isEqualTo(9);
        assertThat(r.path("batch").path("totalRows").asInt()).isEqualTo(4);
        assertThat(r.path("batch").path("configured").asBoolean()).isTrue();
        assertThat(r.path("message").asText()).contains("Chit data cleared.").contains("Job history cleared.");
    }

    @Test
    void aFailedEndOfDayDoesNotBlockTheClearWhenTheBatchServiceIsIdle() {
        chitAnswers("EOD_RUNNING"); // the date says end of day is running, but the batch service reports nothing running
        batchIdle(true);

        JsonNode r = service.clearData("admin");

        assertThat(r.path("totalRows").asInt()).isEqualTo(9);
        assertThat(clearCalls()).isEqualTo(1L);
    }

    @Test
    void anEndOfDayMarkerWithoutAVerifiedIdleBatchServiceBlocksTheClear() {
        chitAnswers("EOD_RUNNING");
        batch.enabled = false;

        assertThatThrownBy(() -> service.clearData("admin"))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(clearCalls()).isZero();
    }

    @Test
    void withNoBatchServiceOnlyTheChitDataIsClearedAndTheAnswerSaysSo() {
        chitAnswers("OPEN");
        batch.enabled = false;

        JsonNode r = service.clearData("admin");

        assertThat(clearCalls()).isEqualTo(1L);
        assertThat(r.path("batch").path("configured").asBoolean()).isFalse();
        assertThat(r.path("message").asText()).contains("not connected to chitfund-batch-service");
    }

    @Test
    void ifTheBatchClearFailsAfterTheChitClearTheMessageSaysWhatWasDone() {
        chitAnswers("OPEN");
        batchIdle(true);
        batch.clearAnswer = null;
        FakeBatch failing = new FakeBatch() {
            @Override
            public JsonNode maintenanceClear(String actor) {
                throw new UpstreamException(409, "BATCH_BUSY: a job started a minute ago");
            }
        };
        failing.maintenanceStatus = batch.maintenanceStatus;
        MaintenanceService s = new MaintenanceService(chit, failing);

        assertThatThrownBy(() -> s.clearData("admin"))
                .isInstanceOfSatisfying(UpstreamException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("chit data was cleared").contains("BATCH_BUSY").contains("again");
                });
        assertThat(clearCalls()).isEqualTo(1L);
    }

    @Test
    void sampleGroupsAreRequestedFromTheChitServiceWithTheActor() {
        chit.answer(HttpMethod.POST, "/maintenance/sample-groups", "{\"groupCodes\":[\"SAMPLE-001\"],\"message\":\"1 sample group(s) created\"}");
        java.util.Map<String, Object> body = java.util.Map.of("count", 1, "chitValue", 100000, "ticketCount", 20, "startDate", "2026-12-01");

        JsonNode r = service.createSampleGroups(body, "admin");

        assertThat(r.path("groupCodes").get(0).asText()).isEqualTo("SAMPLE-001");
        assertThat(chit.calls).hasSize(1);
        assertThat(chit.calls.get(0).path()).isEqualTo("/maintenance/sample-groups");
        assertThat(chit.calls.get(0).method()).isEqualTo(HttpMethod.POST);
        assertThat(chit.calls.get(0).actor()).isEqualTo("admin");
        assertThat(chit.calls.get(0).body()).isEqualTo(body);
    }
}
