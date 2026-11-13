package com.relay.definition;

import static com.relay.TestSupport.json;
import static com.relay.TestSupport.seed;
import static com.relay.TestSupport.validator;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DefinitionValidatorTest {

    private final DefinitionValidator validator = validator();

    private List<String> codes(JsonNode def) {
        return validator.validate(def, true).errors().stream().map(DefinitionValidator.Issue::code).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"wf_support_triage", "wf_expense_approval", "wf_slow_fulfillment", "wf_runaway"})
    void providedSeedWorkflowsAreValid(String id) {
        DefinitionValidator.Result r = validator.validate(seed(id), true);
        assertThat(r.errors()).isEmpty();
    }

    @Test
    void rejectsUnknownNodeType() {
        ObjectNode def = seed("wf_expense_approval").deepCopy();
        ((ObjectNode) def.withArray("nodes").get(0)).put("type", "teleport");
        assertThat(codes(def)).contains("invalid_node_type");
        assertThat(validator.validate(def, true).errors().get(0).message()).contains("teleport");
    }

    @Test
    void rejectsMissingRequiredParam() {
        JsonNode def = json("""
                {"id":"x","name":"x","trigger":{"type":"manual"},"entry":"n1",
                 "nodes":[{"id":"n1","type":"notify","params":{"channel":"chat"},"next":null}]}""");
        List<DefinitionValidator.Issue> errors = validator.validate(def, false).errors();
        assertThat(errors).extracting(DefinitionValidator.Issue::code).contains("missing_required_param");
        assertThat(errors.get(0).message()).contains("'to'").contains("n1");
    }

    @Test
    void rejectsReferenceToNonexistentNode() {
        JsonNode def = json("""
                {"id":"x","name":"x","trigger":{"type":"manual"},"entry":"n1",
                 "nodes":[{"id":"n1","type":"delay","params":{"seconds":1},"next":"ghost"}]}""");
        assertThat(codes(def)).containsExactly("dangling_reference");
    }

    @Test
    void rejectsEntryPointingToNonexistentNode() {
        JsonNode def = json("""
                {"id":"x","name":"x","trigger":{"type":"manual"},"entry":"nope",
                 "nodes":[{"id":"n1","type":"delay","params":{"seconds":1},"next":null}]}""");
        assertThat(codes(def)).contains("entry_not_found");
    }

    @Test
    void everyRejectionCaseHasADistinctMessage() {
        JsonNode unknownType = json("""
                {"id":"x","name":"x","trigger":{"type":"manual"},"entry":"n1","nodes":[{"id":"n1","type":"teleport","params":{},"next":null}]}""");
        JsonNode missingParam = json("""
                {"id":"x","name":"x","trigger":{"type":"manual"},"entry":"n1","nodes":[{"id":"n1","type":"notify","params":{"channel":"chat"},"next":null}]}""");
        JsonNode badRef = json("""
                {"id":"x","name":"x","trigger":{"type":"manual"},"entry":"n1","nodes":[{"id":"n1","type":"delay","params":{"seconds":1},"next":"ghost"}]}""");
        String a = validator.validate(unknownType, true).errors().get(0).message();
        String b = validator.validate(missingParam, true).errors().get(0).message();
        String c = validator.validate(badRef, true).errors().get(0).message();
        assertThat(List.of(a, b, c)).doesNotHaveDuplicates();
    }

    @Test
    void loopsAreLegalAndOnlyWarned() {
        DefinitionValidator.Result r = validator.validate(seed("wf_runaway"), true);
        assertThat(r.errors()).isEmpty();
        assertThat(r.warnings()).anyMatch(w -> w.contains("Loop detected") && w.contains("max_steps=12"));
        assertThat(r.analysis().backEdges()).isNotEmpty();
    }

    @Test
    void warnsAboutUnreachableNodes() {
        JsonNode def = json("""
                {"id":"x","name":"x","trigger":{"type":"manual"},"entry":"a",
                 "nodes":[{"id":"a","type":"delay","params":{"seconds":1},"next":null},
                          {"id":"orphan","type":"delay","params":{"seconds":1},"next":null}]}""");
        DefinitionValidator.Result r = validator.validate(def, true);
        assertThat(r.errors()).isEmpty();
        assertThat(r.warnings()).anyMatch(w -> w.contains("orphan") && w.contains("unreachable"));
    }

    @Test
    void warnsWhenSensitiveNodeIsReachableWithoutApproval() {
        JsonNode def = json("""
                {"id":"x","name":"x","trigger":{"type":"manual"},"entry":"refund",
                 "nodes":[{"id":"refund","type":"order_action","params":{"action":"refund","order_id":"ord_1"},"next":null}]}""");
        DefinitionValidator.Result r = validator.validate(def, true);
        assertThat(r.errors()).isEmpty(); // publish may warn; the engine refuses at runtime
        assertThat(r.warnings()).anyMatch(w -> w.contains("refund") && w.contains("without passing an approval"));
    }

    @Test
    void seedTriageHasNoUngatedSensitiveNodes() {
        assertThat(validator.validate(seed("wf_support_triage"), true).analysis().ungatedSensitiveNodes()).isEmpty();
    }

    @Test
    void conditionNeedsBothBranches() {
        JsonNode def = json("""
                {"id":"x","name":"x","trigger":{"type":"manual"},"entry":"c",
                 "nodes":[{"id":"c","type":"condition","params":{"left":"a","op":"equals","right":"b"},"on_true":"c"}]}""");
        assertThat(codes(def)).contains("missing_branch");
    }

    @Test
    void rejectsInvalidEnumValue() {
        JsonNode def = json("""
                {"id":"x","name":"x","trigger":{"type":"manual"},"entry":"n",
                 "nodes":[{"id":"n","type":"notify","params":{"channel":"pigeon","to":"a","message":"m"},"next":null}]}""");
        assertThat(codes(def)).contains("invalid_param_value");
    }

    @Test
    void rejectsUnknownParam() {
        JsonNode def = json("""
                {"id":"x","name":"x","trigger":{"type":"manual"},"entry":"n",
                 "nodes":[{"id":"n","type":"delay","params":{"seconds":1,"bogus":true},"next":null}]}""");
        assertThat(codes(def)).contains("unknown_param");
    }

    @Test
    void rejectsInvalidOutputSchema() {
        JsonNode def = json("""
                {"id":"x","name":"x","trigger":{"type":"manual"},"entry":"n",
                 "nodes":[{"id":"n","type":"ai","params":{"prompt":"p","output_schema":{"type":"bogus-type"}},"next":null}]}""");
        assertThat(codes(def)).contains("invalid_output_schema");
    }

    @Test
    void rejectsTemplateReferencingMissingNode() {
        JsonNode def = json("""
                {"id":"x","name":"x","trigger":{"type":"manual"},"entry":"n",
                 "nodes":[{"id":"n","type":"notify","params":{"channel":"chat","to":"#a","message":"{{nodes.ghost.output.x}}"},"next":null}]}""");
        assertThat(codes(def)).contains("unknown_node_reference");
    }

    @Test
    void rejectsTemplateWithUnknownRoot() {
        JsonNode def = json("""
                {"id":"x","name":"x","trigger":{"type":"manual"},"entry":"n",
                 "nodes":[{"id":"n","type":"notify","params":{"channel":"chat","to":"#a","message":"{{secrets.key}}"},"next":null}]}""");
        assertThat(codes(def)).contains("invalid_template");
    }

    @Test
    void webhookTriggerRequiresASecret() {
        JsonNode def = json("""
                {"id":"x","name":"x","trigger":{"type":"webhook"},"entry":"n",
                 "nodes":[{"id":"n","type":"delay","params":{"seconds":1},"next":null}]}""");
        assertThat(validator.validate(def, false).errors()).extracting(DefinitionValidator.Issue::code).contains("missing_trigger_secret");
        assertThat(validator.validate(def, true).errors()).isEmpty();
    }

    @Test
    void scheduleTriggerNeedsAValidFiveFieldCron() {
        String tmpl = """
                {"id":"x","name":"x","trigger":{"type":"schedule","cron":"%s"},"entry":"n",
                 "nodes":[{"id":"n","type":"delay","params":{"seconds":1},"next":null}]}""";
        assertThat(validator.validate(json(tmpl.formatted("*/5 * * * *")), false).errors()).isEmpty();
        assertThat(validator.validate(json(tmpl.formatted("not a cron")), false).errors())
                .extracting(DefinitionValidator.Issue::code).contains("invalid_cron");
    }

    @Test
    void rejectsDuplicateNodeIdsAndBadLimits() {
        JsonNode def = json("""
                {"id":"x","name":"x","trigger":{"type":"manual"},"entry":"n","limits":{"max_steps":0},
                 "nodes":[{"id":"n","type":"delay","params":{"seconds":1},"next":null},
                          {"id":"n","type":"delay","params":{"seconds":1},"next":null}]}""");
        assertThat(codes(def)).contains("duplicate_node_id", "invalid_limits");
    }

    @Test
    void nonObjectDefinitionIsRejected() {
        assertThat(validator.validate(json("[]"), true).errors().get(0).code()).isEqualTo("invalid_definition");
    }
}
