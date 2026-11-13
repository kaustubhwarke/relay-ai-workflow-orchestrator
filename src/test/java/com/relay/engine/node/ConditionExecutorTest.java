package com.relay.engine.node;

import static com.relay.TestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.engine.NodeExecutionException;
import org.junit.jupiter.api.Test;

class ConditionExecutorTest {

    private final ConditionExecutor executor = new ConditionExecutor();

    private boolean eval(String left, String op, String right) {
        ObjectNode params = com.relay.TestSupport.MAPPER.createObjectNode();
        params.put("left", left).put("op", op).put("right", right);
        return executor.execute(new NodeExecutor.NodeInvocation("r", "c", params, null, 1)).output().get("result").asBoolean();
    }

    @Test
    void valuesContainingQuotesBackslashesAndNewlinesAreHandled() {
        assertThat(eval("say \"hi\"", "equals", "say \"hi\"")).isTrue();
        assertThat(eval("a\\b", "equals", "a\\b")).isTrue();
        assertThat(eval("line1\nline2", "contains", "line2")).isTrue();
    }

    @Test
    void numericComparisonsParseNumbersFromStrings() {
        assertThat(eval("250", "greater_than", "100")).isTrue();
        assertThat(eval("40", "greater_than", "100")).isFalse();
        assertThat(eval("40", "less_than", "100")).isTrue();
        assertThat(eval("100", "greater_than", "100")).isFalse();
    }

    @Test
    void equalsComparesNumericallyWhenBothAreNumbers() {
        assertThat(eval("100", "equals", "100.0")).isTrue();
        assertThat(eval("100", "not_equals", "100.0")).isFalse();
    }

    @Test
    void equalsComparesStringsOtherwise() {
        assertThat(eval("refund_request", "equals", "refund_request")).isTrue();
        assertThat(eval("complaint", "equals", "refund_request")).isFalse();
        assertThat(eval("complaint", "not_equals", "refund_request")).isTrue();
    }

    @Test
    void containsIsASubstringCheck() {
        assertThat(eval("my earbuds are broken", "contains", "broken")).isTrue();
        assertThat(eval("fine", "contains", "broken")).isFalse();
    }

    @Test
    void ordinalOperatorsFailClearlyOnNonNumbers() {
        assertThatThrownBy(() -> eval("abc", "greater_than", "100"))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("needs two numbers")
                .satisfies(e -> assertThat(((NodeExecutionException) e).isRetryable()).isFalse());
    }

    @Test
    void unknownOperatorIsRejected() {
        assertThatThrownBy(() -> eval("1", "approximately", "1")).isInstanceOf(NodeExecutionException.class);
    }
}
