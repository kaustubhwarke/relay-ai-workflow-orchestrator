package com.relay.engine.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.engine.NodeExecutionException;
import com.relay.engine.TemplateResolver;
import org.springframework.stereotype.Component;

/**
 * {@code notify}: email -> {@code POST /email/send}, chat -> {@code POST /chat/message}. The recipient comes
 * only from the workflow definition (never from AI output), and every call is idempotency-keyed.
 */
@Component
public class NotifyExecutor implements NodeExecutor {

    private final WorldClient world;

    public NotifyExecutor(WorldClient world) {
        this.world = world;
    }

    @Override
    public String type() { return "notify"; }

    @Override
    public NodeOutcome execute(NodeInvocation inv) {
        ObjectNode p = inv.params();
        String channel = p.path("channel").asText();
        String to = TemplateResolver.render(p.get("to"));
        String message = TemplateResolver.render(p.get("message"));
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        String path;
        switch (channel) {
            case "email" -> {
                path = "/email/send";
                body.put("to", to);
                JsonNode subject = p.get("subject");
                if (subject != null && !subject.isNull()) body.put("subject", TemplateResolver.render(subject));
                body.put("message", message);
            }
            case "chat" -> {
                path = "/chat/message";
                body.put("channel", to);
                body.put("message", message);
            }
            default -> throw new NodeExecutionException("invalid_param_value", "Unsupported notify channel '" + channel + "'", false);
        }
        JsonNode resp = world.post(path, body, inv.idempotencyKey());
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("delivered", resp.path("delivered").asBoolean(false));
        out.put("notification_id", resp.path("notification_id").asText(""));
        return NodeOutcome.of(out);
    }
}
