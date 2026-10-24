package com.chitfund.admin.support;

import com.chitfund.admin.service.ChitClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A ChitClient that records what it is asked and answers from a table, instead of calling the chit service. */
public class FakeChit extends ChitClient {

    public record Call(HttpMethod method, String path, Map<String, Object> query, Object body, String actor, String key) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    public final List<Call> calls = new ArrayList<>();
    /** Answers by "METHOD /path"; an unlisted call answers null (an empty body). */
    public final Map<String, JsonNode> answers = new HashMap<>();
    public RuntimeException failWith;

    public FakeChit() {
        super("", "chit-service", "");
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public JsonNode exchange(HttpMethod method, String path, Map<String, ?> query, Object body, String actor, String idempotencyKey) {
        calls.add(new Call(method, path, query == null ? new LinkedHashMap<>() : new LinkedHashMap<String, Object>(query), body, actor, idempotencyKey));
        if (failWith != null) {
            throw failWith;
        }
        return answers.get(method + " " + path);
    }

    public FakeChit answer(HttpMethod method, String path, String json) {
        try {
            answers.put(method + " " + path, JSON.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    public Call last() {
        return calls.get(calls.size() - 1);
    }
}
