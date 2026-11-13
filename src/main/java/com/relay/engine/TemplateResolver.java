package com.relay.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code {{path}}} expressions such as {@code {{trigger.body.email}}} and
 * {@code {{nodes.classify.output.category}}}.
 *
 * <p>Rules:
 * <ul>
 *   <li>Path lookups only: no expressions, filters or arithmetic.</li>
 *   <li>An unresolvable path fails the step with a clear error; it never silently renders an empty string.</li>
 *   <li>Resolution is <b>single pass</b>: resolved values are never re-scanned, so untrusted payload text
 *       containing {@code {{...}}} cannot inject further template expressions.</li>
 *   <li>A string that is exactly one template keeps the referenced value's JSON type (so a number stays a
 *       number); a template embedded in longer text is rendered as text.</li>
 * </ul>
 */
@Component
public class TemplateResolver {

    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** Resolves every template in {@code value} (recursing through objects and arrays). */
    public JsonNode resolve(JsonNode value, TemplateContext ctx) {
        if (value == null || value.isNull()) return value;
        if (value.isTextual()) return resolveText(value.asText(), ctx);
        if (value.isObject()) {
            ObjectNode out = F.objectNode();
            Iterator<Map.Entry<String, JsonNode>> it = value.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                out.set(e.getKey(), resolve(e.getValue(), ctx));
            }
            return out;
        }
        if (value.isArray()) {
            ArrayNode out = F.arrayNode();
            value.forEach(v -> out.add(resolve(v, ctx)));
            return out;
        }
        return value;
    }

    public String resolveToString(String text, TemplateContext ctx) {
        JsonNode r = resolveText(text, ctx);
        return r.isTextual() ? r.asText() : render(r);
    }

    private JsonNode resolveText(String text, TemplateContext ctx) {
        Matcher m = TEMPLATE.matcher(text);
        if (!m.find()) return F.textNode(text);
        // Whole-string single template: preserve the JSON type of the referenced value.
        if (m.start() == 0 && m.end() == text.length()) {
            return lookup(m.group(1).trim(), ctx);
        }
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(render(lookup(m.group(1).trim(), ctx))));
        }
        m.appendTail(sb);
        return F.textNode(sb.toString());
    }

    /** Renders a value for embedding inside text. */
    public static String render(JsonNode n) {
        if (n.isTextual()) return n.asText();
        if (n.isNull()) return "null";
        if (n.isIntegralNumber()) return n.bigIntegerValue().toString();
        if (n.isNumber()) return new BigDecimal(n.asText()).stripTrailingZeros().toPlainString();
        if (n.isBoolean()) return Boolean.toString(n.asBoolean());
        return n.toString();
    }

    private JsonNode lookup(String path, TemplateContext ctx) {
        String[] seg = path.split("\\.");
        JsonNode current;
        int next;
        switch (seg[0]) {
            case "trigger" -> {
                if (seg.length >= 2 && "type".equals(seg[1]) && seg.length == 2) return F.textNode(ctx.triggerType());
                if (seg.length < 2 || !"body".equals(seg[1])) {
                    throw new TemplateException("Cannot resolve '{{" + path + "}}': use trigger.body.<field> or trigger.type");
                }
                current = ctx.triggerBody();
                next = 2;
            }
            case "nodes" -> {
                if (seg.length < 3 || !"output".equals(seg[2])) {
                    throw new TemplateException("Cannot resolve '{{" + path + "}}': use nodes.<id>.output.<field>");
                }
                current = ctx.nodeOutputs().get(seg[1]);
                if (current == null) {
                    throw new TemplateException("Cannot resolve '{{" + path + "}}': node '" + seg[1] + "' has not produced output yet");
                }
                next = 3;
            }
            case "run" -> {
                if (seg.length == 2 && "id".equals(seg[1])) return F.textNode(ctx.runId());
                throw new TemplateException("Cannot resolve '{{" + path + "}}': only run.id is available");
            }
            default -> throw new TemplateException("Cannot resolve '{{" + path + "}}': unknown root '" + seg[0] + "'");
        }
        for (int i = next; i < seg.length; i++) {
            if (current == null || current.isNull() || current.isMissingNode()) {
                throw new TemplateException("Cannot resolve '{{" + path + "}}': '" + seg[i - 1] + "' is null or missing");
            }
            if (current.isArray() && seg[i].chars().allMatch(Character::isDigit)) {
                current = current.get(Integer.parseInt(seg[i]));
            } else if (current.isObject()) {
                current = current.get(seg[i]);
            } else {
                throw new TemplateException("Cannot resolve '{{" + path + "}}': '" + seg[i - 1] + "' is not an object");
            }
            if (current == null) {
                throw new TemplateException("Cannot resolve '{{" + path + "}}': field '" + seg[i] + "' does not exist");
            }
        }
        if (current == null || current.isMissingNode()) {
            throw new TemplateException("Cannot resolve '{{" + path + "}}': value is missing");
        }
        return current;
    }
}
