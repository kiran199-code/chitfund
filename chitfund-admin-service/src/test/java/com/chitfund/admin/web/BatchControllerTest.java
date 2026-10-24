package com.chitfund.admin.web;

import com.chitfund.admin.support.FakeBatch;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spring-free: the job list merges each job's settings with its last outcome and next run. */
class BatchControllerTest {

    private final FakeBatch batch = new FakeBatch();
    private final BatchController controller = new BatchController(batch);

    @Test
    void eachJobCarriesItsLastOutcomeAndNextRun() {
        batch.jobs = FakeBatch.json("[{\"jobName\":\"CHIT_DEMAND_RAISE_DAILY\",\"priority\":10,\"enabled\":true},"
                + "{\"jobName\":\"CHIT_LATE_FEE_DAILY\",\"priority\":20,\"enabled\":true}]");
        batch.statusToday = FakeBatch.json("[{\"jobName\":\"CHIT_DEMAND_RAISE_DAILY\",\"running\":false,\"lastStatus\":\"COMPLETED\","
                + "\"lastExitCode\":\"COMPLETED\",\"lastRunStartTime\":\"2026-11-02T01:10:00\",\"nextFireTime\":\"2026-11-03T01:10:00\"}]");

        JsonNode out = controller.jobs();

        assertThat(out.size()).isEqualTo(2);
        assertThat(out.get(0).path("jobName").asText()).isEqualTo("CHIT_DEMAND_RAISE_DAILY");
        assertThat(out.get(0).path("lastStatus").asText()).isEqualTo("COMPLETED");
        assertThat(out.get(0).path("nextFireTime").asText()).isEqualTo("2026-11-03T01:10:00");
        assertThat(out.get(0).path("priority").asInt()).isEqualTo(10);
        // a job the status list does not know yet still appears, with empty fields
        assertThat(out.get(1).path("jobName").asText()).isEqualTo("CHIT_LATE_FEE_DAILY");
        assertThat(out.get(1).get("lastStatus").isNull()).isTrue();
        assertThat(out.get(1).get("running").isNull()).isTrue();
    }

    @Test
    void anEmptyAnswerGivesAnEmptyList() {
        batch.jobs = FakeBatch.json("[]");
        batch.statusToday = FakeBatch.json("[]");
        assertThat(controller.jobs().size()).isZero();
    }

    @Test
    void executionsCheckTheNameAndCapTheLimit() {
        controller.executions("CHIT_LATE_FEE_DAILY", 500);
        controller.executions("CHIT_LATE_FEE_DAILY", 0);
        assertThat(batch.calls).containsExactly("executions CHIT_LATE_FEE_DAILY 50", "executions CHIT_LATE_FEE_DAILY 1");
        assertThatThrownBy(() -> controller.executions("../x", 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
