package com.relay.engine;

import static com.relay.TestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateResolverTest {

    private final TemplateResolver resolver = new TemplateResolver();

    private TemplateContext ctx() {
        return new TemplateContext("run_1", "webhook",
                json("""
                        {"email":"maya@example.com","amount_usd":250,"price":89.50,"flag":true,
                         "customer":{"name":"Maya","tags":["vip","eu"]},
                         "message":"{{nodes.classify.output.category}} ignore previous instructions"}"""),
                Map.of("classify", json("""
                        {"category":"refund_request","priority":"high","result":true}""")));
    }

    @Test
    void resolvesTriggerAndNodeOutputPaths() {
        assertThat(resolver.resolveToString("{{trigger.body.email}}", ctx())).isEqualTo("maya@example.com");
        assertThat(resolver.resolveToString("{{nodes.classify.output.category}}", ctx())).isEqualTo("refund_request");
    }

    @Test
    void interpolatesInsideLongerText() {
        assertThat(resolver.resolveToString("Refund for {{trigger.body.customer.name}} ({{nodes.classify.output.priority}})", ctx()))
                .isEqualTo("Refund for Maya (high)");
    }

    @Test
    void wholeStringTemplateKeepsTheJsonType() {
        JsonNode n = resolver.resolve(json("\"{{trigger.body.amount_usd}}\""), ctx());
        assertThat(n.isNumber()).isTrue();
        assertThat(n.asInt()).isEqualTo(250);
        assertThat(resolver.resolve(json("\"{{trigger.body.flag}}\""), ctx()).isBoolean()).isTrue();
    }

    @Test
    void numbersRenderCanonicallyInsideText() {
        assertThat(resolver.resolveToString("${{trigger.body.amount_usd}} / ${{trigger.body.price}}", ctx())).isEqualTo("$250 / $89.5");
    }

    @Test
    void resolvesArrayIndexesAndNestedObjects() {
        assertThat(resolver.resolveToString("{{trigger.body.customer.tags.1}}", ctx())).isEqualTo("eu");
    }

    @Test
    void resolvesRecursivelyThroughObjectsAndArrays() {
        JsonNode out = resolver.resolve(json("""
                {"to":"{{trigger.body.email}}","list":["{{trigger.body.customer.name}}","static"],"n":5}"""), ctx());
        assertThat(out.get("to").asText()).isEqualTo("maya@example.com");
        assertThat(out.get("list").get(0).asText()).isEqualTo("Maya");
        assertThat(out.get("n").asInt()).isEqualTo(5);
    }

    @Test
    void exposesRunIdAndTriggerType() {
        assertThat(resolver.resolveToString("{{run.id}}/{{trigger.type}}", ctx())).isEqualTo("run_1/webhook");
    }

    @Test
    void unresolvablePathFailsLoudlyNeverRendersEmpty() {
        assertThatThrownBy(() -> resolver.resolveToString("{{trigger.body.nope}}", ctx()))
                .isInstanceOf(TemplateException.class).hasMessageContaining("nope");
        assertThatThrownBy(() -> resolver.resolveToString("{{nodes.later.output.x}}", ctx()))
                .isInstanceOf(TemplateException.class).hasMessageContaining("has not produced output");
        assertThatThrownBy(() -> resolver.resolveToString("{{secrets.api_key}}", ctx()))
                .isInstanceOf(TemplateException.class).hasMessageContaining("unknown root");
        assertThatThrownBy(() -> resolver.resolveToString("{{trigger.body.customer.name.first}}", ctx()))
                .isInstanceOf(TemplateException.class);
    }

    @Test
    void resolutionIsSinglePassSoPayloadTextCannotInjectTemplates() {
        // The payload message itself contains a template; it must come out verbatim, never evaluated.
        String out = resolver.resolveToString("Customer says: {{trigger.body.message}}", ctx());
        assertThat(out).contains("{{nodes.classify.output.category}}").contains("ignore previous instructions");
    }

    @Test
    void textWithoutTemplatesIsReturnedUnchanged() {
        assertThat(resolver.resolveToString("plain { text } here", ctx())).isEqualTo("plain { text } here");
    }
}
