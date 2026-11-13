package com.relay.domain;

import com.fasterxml.jackson.databind.JsonNode;

/** Records hold JSON trees, which are mutable; they copy at their boundary so a record stays immutable. */
final class Json {

    private Json() {}

    @SuppressWarnings("unchecked")
    static <T extends JsonNode> T copy(T node) {
        return node == null ? null : (T) node.deepCopy();
    }
}
