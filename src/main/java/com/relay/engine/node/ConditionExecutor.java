package com.relay.engine.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.engine.NodeExecutionException;
import com.relay.engine.TemplateResolver;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/** Evaluates {@code left <op> right} and outputs {@code {"result": boolean}}; the engine routes on it. */
@Component
public class ConditionExecutor implements NodeExecutor {

    @Override
    public String type() { return "condition"; }

    @Override
    public NodeOutcome execute(NodeInvocation inv) {
        String left = text(inv.params().get("left"));
        String right = text(inv.params().get("right"));
        String op = inv.params().path("op").asText();
        boolean result = evaluate(left, op, right);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("result", result);
        return NodeOutcome.of(out);
    }

    static boolean evaluate(String left, String op, String right) {
        BigDecimal l = number(left);
        BigDecimal r = number(right);
        return switch (op) {
            case "equals" -> l != null && r != null ? l.compareTo(r) == 0 : left.equals(right);
            case "not_equals" -> l != null && r != null ? l.compareTo(r) != 0 : !left.equals(right);
            case "contains" -> left.contains(right);
            case "greater_than" -> compareNumeric(l, r, left, right, op) > 0;
            case "less_than" -> compareNumeric(l, r, left, right, op) < 0;
            default -> throw new NodeExecutionException("condition_error", "Unsupported operator '" + op + "'", false);
        };
    }

    private static int compareNumeric(BigDecimal l, BigDecimal r, String left, String right, String op) {
        if (l == null || r == null) {
            throw new NodeExecutionException("condition_error",
                    "Operator '" + op + "' needs two numbers but got left='" + clip(left) + "' and right='" + clip(right) + "'", false);
        }
        return l.compareTo(r);
    }

    private static BigDecimal number(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull()) return "";
        return TemplateResolver.render(n);
    }

    private static String clip(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }
}
