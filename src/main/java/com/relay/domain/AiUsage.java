package com.relay.domain;

import com.fasterxml.jackson.databind.JsonNode;

/** Token usage and the per-attempt log of one AI step. Shared by the engine and the persistence layer. */
public record AiUsage(Integer promptTokens, Integer completionTokens, JsonNode attempts) {
    public static final AiUsage NONE = new AiUsage(null, null, null);
}
