package com.chitfund.admin.support;

import com.chitfund.admin.service.BatchConfigClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** A BatchConfigClient that records the calls and answers with canned JSON, instead of calling the batch service. */
public class FakeBatch extends BatchConfigClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    public final List<String> calls = new ArrayList<>();
    public boolean enabled = true;
    public JsonNode eodStatus;
    public JsonNode eodRunAnswer;
    public JsonNode jobs;
    public JsonNode statusToday;
    public JsonNode maintenanceStatus;
    public JsonNode clearAnswer;
    public RuntimeException failWith;

    public FakeBatch() {
        super("", "batch-admin", "");
    }

    public static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void check(String call) {
        calls.add(call);
        if (failWith != null) {
            throw failWith;
        }
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public JsonNode eodStatus() {
        check("eodStatus");
        return eodStatus;
    }

    @Override
    public JsonNode eodRun(String expectedBusinessDate) {
        check("eodRun " + expectedBusinessDate);
        return eodRunAnswer;
    }

    @Override
    public JsonNode jobs() {
        check("jobs");
        return jobs;
    }

    @Override
    public JsonNode statusToday() {
        check("statusToday");
        return statusToday;
    }

    @Override
    public JsonNode executions(String jobName, int limit) {
        check("executions " + jobName + " " + limit);
        return json("[]");
    }

    @Override
    public JsonNode maintenanceStatus() {
        check("maintenanceStatus");
        return maintenanceStatus;
    }

    @Override
    public JsonNode maintenanceClear(String actor) {
        check("maintenanceClear " + actor);
        return clearAnswer;
    }
}
