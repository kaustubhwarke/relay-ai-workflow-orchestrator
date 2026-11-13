package com.relay.definition;

import static com.relay.TestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class GraphAnalyzerTest {

    private static WorkflowDefinition def(String nodesJson, String entry) {
        return WorkflowDefinition.parse(json("""
                {"id":"g","name":"g","trigger":{"type":"manual"},"entry":"%s","nodes":%s}""".formatted(entry, nodesJson)), 100);
    }

    @Test
    void bfsFindsReachableAndUnreachableNodes() {
        WorkflowDefinition d = def("""
                [{"id":"a","type":"delay","params":{"seconds":0},"next":"b"},
                 {"id":"b","type":"delay","params":{"seconds":0},"next":null},
                 {"id":"island","type":"delay","params":{"seconds":0},"next":null}]""", "a");
        GraphAnalyzer.Analysis an = GraphAnalyzer.analyze(d, Set.of());
        assertThat(an.reachable()).containsExactlyInAnyOrder("a", "b");
        assertThat(an.unreachable()).containsExactly("island");
    }

    @Test
    void dfsDetectsBackEdgesAsLoops() {
        WorkflowDefinition d = def("""
                [{"id":"a","type":"delay","params":{"seconds":0},"next":"b"},
                 {"id":"b","type":"condition","params":{"left":"1","op":"equals","right":"2"},"on_true":"c","on_false":"a"},
                 {"id":"c","type":"delay","params":{"seconds":0},"next":null}]""", "a");
        GraphAnalyzer.Analysis an = GraphAnalyzer.analyze(d, Set.of());
        assertThat(an.backEdges()).containsExactly(new GraphAnalyzer.Edge("b", "a"));
    }

    @Test
    void forwardOnlyGraphHasNoBackEdges() {
        WorkflowDefinition d = def("""
                [{"id":"a","type":"condition","params":{"left":"1","op":"equals","right":"1"},"on_true":"b","on_false":"c"},
                 {"id":"b","type":"delay","params":{"seconds":0},"next":"c"},
                 {"id":"c","type":"delay","params":{"seconds":0},"next":null}]""", "a");
        assertThat(GraphAnalyzer.analyze(d, Set.of()).backEdges()).isEmpty();
    }

    @Test
    void selfLoopIsABackEdge() {
        WorkflowDefinition d = def("""
                [{"id":"a","type":"delay","params":{"seconds":0},"next":"a"}]""", "a");
        assertThat(GraphAnalyzer.analyze(d, Set.of()).backEdges()).containsExactly(new GraphAnalyzer.Edge("a", "a"));
    }

    @Test
    void sensitiveNodeBehindApprovalOnEveryPathIsGated() {
        WorkflowDefinition d = def("""
                [{"id":"start","type":"condition","params":{"left":"1","op":"equals","right":"1"},"on_true":"gate","on_false":"gate"},
                 {"id":"gate","type":"approval","params":{"message":"ok?"},"next":"refund"},
                 {"id":"refund","type":"order_action","params":{"action":"refund","order_id":"o"},"next":null}]""", "start");
        assertThat(GraphAnalyzer.analyze(d, Set.of("order_action")).ungatedSensitiveNodes()).isEmpty();
    }

    @Test
    void sensitiveNodeReachableAroundTheApprovalIsFlagged() {
        WorkflowDefinition d = def("""
                [{"id":"start","type":"condition","params":{"left":"1","op":"equals","right":"1"},"on_true":"gate","on_false":"refund"},
                 {"id":"gate","type":"approval","params":{"message":"ok?"},"next":"refund"},
                 {"id":"refund","type":"order_action","params":{"action":"refund","order_id":"o"},"next":null}]""", "start");
        assertThat(GraphAnalyzer.analyze(d, Set.of("order_action")).ungatedSensitiveNodes()).containsExactly("refund");
    }
}
