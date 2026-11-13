package com.relay.engine.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.engine.NodeExecutionException;
import com.relay.engine.TemplateResolver;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code order_action}: a sensitive commerce action (refund / replacement). The engine refuses to call this
 * executor unless an approval record exists for the run (see {@code RunExecutor}); that check is data, not
 * prompt text. The refund amount is never AI-controlled: it comes from the definition (or defaults to the
 * order total at the mock world).
 */
@Component
public class OrderActionExecutor implements NodeExecutor {

    /** The order id usually originates in an untrusted payload; restrict it before it goes into a URL path. */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_\\-]{1,64}$");

    private final WorldClient world;

    private final double maxRefundUsd;

    public OrderActionExecutor(WorldClient world, com.relay.config.RelayProperties props) {
        this.world = world;
        this.maxRefundUsd = props.getEngine().getMaxRefundUsd();
    }

    @Override
    public String type() { return "order_action"; }

    @Override
    public NodeOutcome execute(NodeInvocation inv) {
        ObjectNode p = inv.params();
        String action = p.path("action").asText();
        String orderId = TemplateResolver.render(p.get("order_id"));
        if (!SAFE_ID.matcher(orderId).matches()) {
            throw new NodeExecutionException("invalid_param_value", "order_id contains unsupported characters", false);
        }
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        String path;
        switch (action) {
            case "refund" -> {
                path = "/orders/" + orderId + "/refund";
                JsonNode amount = p.get("amount_usd");
                if (amount != null && !amount.isNull()) {
                    if (!amount.isNumber()) {
                        throw new NodeExecutionException("invalid_param_value", "amount_usd must be a number", false);
                    }
                    // the world also enforces the order total and its rejection surfaces as a failed step;
                    // this is the engine-side range check before a financial action is sent at all
                    if (amount.asDouble() <= 0 || amount.asDouble() > maxRefundUsd) {
                        throw new NodeExecutionException("invalid_param_value",
                                "amount_usd must be greater than 0 and at most " + maxRefundUsd, false);
                    }
                    body.set("amount_usd", amount);
                }
            }
            case "replacement" -> path = "/orders/" + orderId + "/replacement";
            default -> throw new NodeExecutionException("invalid_param_value", "Unsupported order action '" + action + "'", false);
        }
        JsonNode resp = world.post(path, body, inv.idempotencyKey());
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("status", resp.path("status").asText(""));
        out.put("reference_id", resp.path("reference_id").asText(""));
        return NodeOutcome.of(out);
    }
}
