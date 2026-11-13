package com.relay.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * The data a node can reference: the trigger payload, the latest output of every node that has run, and
 * a few run attributes.
 *
 * @param runId the run id ({@code {{run.id}}})
 * @param triggerType manual / webhook / schedule ({@code {{trigger.type}}})
 * @param triggerBody the trigger payload ({@code {{trigger.body.*}}})
 * @param nodeOutputs node id to its most recent output ({@code {{nodes.<id>.output.*}}})
 */
public record TemplateContext(String runId, String triggerType, JsonNode triggerBody, Map<String, JsonNode> nodeOutputs) {
}
