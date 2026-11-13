package com.relay.definition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural analysis of a workflow graph.
 *
 * <ul>
 *   <li><b>BFS</b> from the entry node computes reachability (unreachable nodes are reported).</li>
 *   <li><b>DFS</b> classifies edges; an edge to a node on the current DFS stack is a back-edge (a
 *       <em>loop</em>). Loops are legal (polling and retry patterns depend on them) and are bounded at
 *       execution time by {@code limits.max_steps}.</li>
 *   <li>A path check finds sensitive nodes reachable from the entry without passing through an
 *       {@code approval} node. This is reported as a warning only: the engine independently refuses to
 *       execute them without an approval record.</li>
 * </ul>
 */
public final class GraphAnalyzer {

    public record Edge(String from, String to) {}

    public record Analysis(
            Set<String> reachable,
            Set<String> unreachable,
            List<Edge> backEdges,
            List<String> ungatedSensitiveNodes) {}

    private GraphAnalyzer() {}

    public static Analysis analyze(WorkflowDefinition def, Set<String> sensitiveNodeTypes) {
        Set<String> reachable = bfsReachable(def);
        Set<String> unreachable = new LinkedHashSet<>(def.nodes().keySet());
        unreachable.removeAll(reachable);
        List<Edge> back = dfsBackEdges(def);
        List<String> ungated = new ArrayList<>();
        for (WorkflowDefinition.Node n : def.nodes().values()) {
            if (sensitiveNodeTypes.contains(n.type())
                    && reachable.contains(n.id())
                    && reachableWithoutApproval(def, n.id())) {
                ungated.add(n.id());
            }
        }
        return new Analysis(reachable, unreachable, back, ungated);
    }

    /** Breadth-first search from the entry node. */
    static Set<String> bfsReachable(WorkflowDefinition def) {
        Set<String> seen = new LinkedHashSet<>();
        if (def.node(def.entry()) == null) return seen;
        Deque<String> queue = new ArrayDeque<>();
        queue.add(def.entry());
        seen.add(def.entry());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            for (String succ : successors(def.node(id))) {
                if (def.node(succ) != null && seen.add(succ)) queue.add(succ);
            }
        }
        return seen;
    }

    private record Frame(String nodeId, Iterator<String> successors) {}

    /** Iterative depth-first search that records back-edges (loops). */
    static List<Edge> dfsBackEdges(WorkflowDefinition def) {
        List<Edge> back = new ArrayList<>();
        if (def.node(def.entry()) == null) return back;
        Set<String> done = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(def.entry(), successors(def.node(def.entry())).iterator()));
        onStack.add(def.entry());
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (frame.successors().hasNext()) {
                String succ = frame.successors().next();
                if (def.node(succ) == null) continue;
                if (onStack.contains(succ)) {
                    back.add(new Edge(frame.nodeId(), succ));
                } else if (!done.contains(succ)) {
                    onStack.add(succ);
                    stack.push(new Frame(succ, successors(def.node(succ)).iterator()));
                }
            } else {
                stack.pop();
                onStack.remove(frame.nodeId());
                done.add(frame.nodeId());
            }
        }
        return back;
    }

    /** True if {@code target} is reachable from the entry along some path that contains no approval node. */
    static boolean reachableWithoutApproval(WorkflowDefinition def, String target) {
        WorkflowDefinition.Node entry = def.node(def.entry());
        if (entry == null || "approval".equals(entry.type())) return false;
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(entry.id());
        seen.add(entry.id());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (id.equals(target)) return true;
            for (String succ : successors(def.node(id))) {
                WorkflowDefinition.Node sn = def.node(succ);
                if (sn == null || "approval".equals(sn.type())) continue;
                if (seen.add(succ)) queue.add(succ);
            }
        }
        return false;
    }

    static List<String> successors(WorkflowDefinition.Node n) {
        List<String> out = new ArrayList<>(2);
        if (n.isCondition()) {
            if (n.onTrue() != null) out.add(n.onTrue());
            if (n.onFalse() != null) out.add(n.onFalse());
        } else if (n.next() != null) {
            out.add(n.next());
        }
        return out;
    }
}
