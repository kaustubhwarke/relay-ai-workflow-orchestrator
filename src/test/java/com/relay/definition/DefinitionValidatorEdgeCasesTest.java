package com.relay.definition;

import static com.relay.TestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.relay.TestSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every remaining way a definition can be wrong, each reported with its own code. */
class DefinitionValidatorEdgeCasesTest {

    private final DefinitionValidator validator = TestSupport.validator();

    private static String wf(String trigger, String limits, String nodes, String entry) {
        return "{\"id\":\"x\",\"name\":\"x\"" + (trigger == null ? "" : ",\"trigger\":" + trigger)
                + (limits == null ? "" : ",\"limits\":" + limits)
                + (nodes == null ? "" : ",\"nodes\":" + nodes)
                + (entry == null ? "" : ",\"entry\":\"" + entry + "\"") + "}";
    }

    private static final String MANUAL = "{\"type\":\"manual\"}";
    private static final String DELAY = "[{\"id\":\"n1\",\"type\":\"delay\",\"params\":{\"seconds\":1},\"next\":null}]";

    @Test
    void eachMalformedDefinitionIsRejectedWithTheExpectedCode() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("invalid_definition", "[1,2]");
        cases.put("invalid_id", "{\"id\":\"bad id!\",\"name\":\"x\",\"trigger\":" + MANUAL + ",\"entry\":\"n1\",\"nodes\":" + DELAY + "}");
        cases.put("missing_name", "{\"id\":\"x\",\"trigger\":" + MANUAL + ",\"entry\":\"n1\",\"nodes\":" + DELAY + "}");
        cases.put("missing_trigger", wf(null, null, DELAY, "n1"));
        cases.put("invalid_trigger_type", wf("{\"type\":\"carrier-pigeon\"}", null, DELAY, "n1"));
        cases.put("missing_trigger_secret", wf("{\"type\":\"webhook\"}", null, DELAY, "n1"));
        cases.put("missing_trigger_cron", wf("{\"type\":\"schedule\"}", null, DELAY, "n1"));
        cases.put("invalid_cron", wf("{\"type\":\"schedule\",\"cron\":\"every minute\"}", null, DELAY, "n1"));
        cases.put("invalid_limits", wf(MANUAL, "[1]", DELAY, "n1"));
        cases.put("missing_nodes", wf(MANUAL, null, "[]", "n1"));
        cases.put("invalid_node_id", wf(MANUAL, null, "[{\"type\":\"delay\",\"params\":{\"seconds\":1}}]", "n1"));
        cases.put("missing_entry", wf(MANUAL, null, DELAY, null));
        cases.put("invalid_params", wf(MANUAL, null, "[{\"id\":\"n1\",\"type\":\"delay\",\"params\":[1],\"next\":null}]", "n1"));
        cases.put("invalid_param_value", wf(MANUAL, null,
                "[{\"id\":\"n1\",\"type\":\"notify\",\"params\":{\"channel\":\"pigeon\",\"to\":\"a\",\"message\":\"m\"},\"next\":null}]", "n1"));
        cases.put("missing_branch", wf(MANUAL, null,
                "[{\"id\":\"n1\",\"type\":\"condition\",\"params\":{\"left\":\"1\",\"op\":\"equals\",\"right\":\"1\"},\"on_true\":null}]", "n1"));
        cases.put("unexpected_next", wf(MANUAL, null,
                "[{\"id\":\"n1\",\"type\":\"condition\",\"params\":{\"left\":\"1\",\"op\":\"equals\",\"right\":\"1\"},\"on_true\":\"n1\",\"on_false\":\"n1\",\"next\":\"n1\"}]", "n1"));
        cases.put("unexpected_branch", wf(MANUAL, null,
                "[{\"id\":\"n1\",\"type\":\"delay\",\"params\":{\"seconds\":1},\"next\":null,\"on_true\":\"n1\"}]", "n1"));
        cases.put("invalid_retry", wf(MANUAL, null,
                "[{\"id\":\"n1\",\"type\":\"delay\",\"params\":{\"seconds\":1},\"next\":null,\"retry\":\"often\"}]", "n1"));
        cases.put("invalid_template", wf(MANUAL, null,
                "[{\"id\":\"n1\",\"type\":\"notify\",\"params\":{\"channel\":\"chat\",\"to\":\"#t\",\"message\":\"{{secrets.key}}\"},\"next\":null}]", "n1"));
        cases.put("unknown_node_reference", wf(MANUAL, null,
                "[{\"id\":\"n1\",\"type\":\"notify\",\"params\":{\"channel\":\"chat\",\"to\":\"#t\",\"message\":\"{{nodes.ghost.output.x}}\"},\"next\":null}]", "n1"));

        for (Map.Entry<String, String> c : cases.entrySet()) {
            DefinitionValidator.Result r = validator.validate(json(c.getValue()), false);
            assertThat(r.valid()).as(c.getKey()).isFalse();
            assertThat(r.errors()).as(c.getKey()).extracting(DefinitionValidator.Issue::code).contains(c.getKey());
        }
    }

    @Test
    void limitsAndRetryBoundsAreEnforced() {
        for (String limits : new String[] {"{\"max_steps\":0}", "{\"max_steps\":\"ten\"}", "{\"max_steps\":99999}",
                "{\"timeout_seconds\":-1}", "{\"max_ai_tokens\":1.5}"}) {
            assertThat(validator.validate(json(wf(MANUAL, limits, DELAY, "n1")), true).errors())
                    .as(limits).extracting(DefinitionValidator.Issue::code).contains("invalid_limits");
        }
        for (String retry : new String[] {"{\"max_attempts\":0}", "{\"max_attempts\":21}", "{\"max_attempts\":\"x\"}"}) {
            String nodes = "[{\"id\":\"n1\",\"type\":\"delay\",\"params\":{\"seconds\":1},\"next\":null,\"retry\":" + retry + "}]";
            assertThat(validator.validate(json(wf(MANUAL, null, nodes, "n1")), true).errors())
                    .as(retry).extracting(DefinitionValidator.Issue::code).contains("invalid_retry");
        }
        String ok = "[{\"id\":\"n1\",\"type\":\"delay\",\"params\":{\"seconds\":1},\"next\":null,\"retry\":{\"max_attempts\":3}}]";
        assertThat(validator.validate(json(wf(MANUAL, "{\"max_steps\":5,\"timeout_seconds\":0}", ok, "n1")), true).valid()).isTrue();
    }

    @Test
    void aWorkflowCanBeNullOrNotAnObjectWithoutThrowing() {
        assertThat(validator.validate(null, true).errors()).extracting(DefinitionValidator.Issue::code).containsExactly("invalid_definition");
        assertThat(validator.validate(json("\"text\""), true).valid()).isFalse();
    }

    @Test
    void warningsDescribeUnreachableNodesLoopsAndUngatedSensitiveActions() {
        String nodes = """
                [{"id":"a","type":"order_action","params":{"action":"refund","order_id":"ord_1"},"next":"b"},
                 {"id":"b","type":"delay","params":{"seconds":0},"next":"a"},
                 {"id":"orphan","type":"delay","params":{"seconds":0},"next":null}]""";
        DefinitionValidator.Result r = validator.validate(json(wf(MANUAL, null, nodes, "a")), true);
        assertThat(r.valid()).isTrue();
        assertThat(r.warnings()).anyMatch(w -> w.contains("unreachable")).anyMatch(w -> w.contains("Loop detected"))
                .anyMatch(w -> w.contains("Sensitive node 'a'"));
        assertThat(r.analysis().ungatedSensitiveNodes()).contains("a");
    }
}
