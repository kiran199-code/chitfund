package com.chitfund.admin.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Tiny helper so controllers can build JSON objects without each owning an ObjectMapper. */
final class JsonNodes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNodes() {
    }

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    static ArrayNode array() {
        return MAPPER.createArrayNode();
    }
}
