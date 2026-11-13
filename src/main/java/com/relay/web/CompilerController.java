package com.relay.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.compiler.NlCompilerService;
import com.relay.compiler.NlEvalService;
import com.relay.web.dto.Views;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Natural-language workflow compiler (Good To Have). Builder role required. */
@ConditionalOnProperty(name = "relay.api.enabled", havingValue = "true", matchIfMissing = true)
@RestController
@RequestMapping("/workflows/compile")
public class CompilerController {

    public record CompileRequest(String description, Boolean persist) {}

    private final NlCompilerService compiler;
    private final NlEvalService eval;

    public CompilerController(NlCompilerService compiler, NlEvalService eval) {
        this.compiler = compiler;
        this.eval = eval;
    }

    /**
     * Success: {@code {"workflow": <definition>, "draft": <stored draft>, "explanation", "warnings", "webhook_secret"?}}
     * (the generated webhook secret is shown exactly once). Refusal: {@code {"refusal": {"reason", "missing_capabilities"}}}.
     */
    @PostMapping
    public Map<String, Object> compile(@RequestBody CompileRequest req) {
        NlCompilerService.Result r = compiler.compile(req.description(), req.persist() == null || req.persist());
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (r.refused()) {
            ObjectNode ref = out.putObject("refusal");
            ref.put("reason", r.refusal().reason());
            var arr = ref.putArray("missing_capabilities");
            r.refusal().missingCapabilities().forEach(arr::add);
        } else {
            out.set("workflow", r.workflow());
            if (r.explanation() != null && !r.explanation().isBlank()) out.put("explanation", r.explanation());
            if (r.webhookSecret() != null) out.put("webhook_secret", r.webhookSecret());
            var warn = out.putArray("warnings");
            r.warnings().forEach(warn::add);
        }
        out.put("attempts", r.attempts());
        out.put("model", compiler.modelName());
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        out.fields().forEachRemaining(e -> body.put(e.getKey(), e.getValue()));
        if (r.persisted() != null) body.put("draft", Views.WorkflowDetail.of(r.persisted(), null));
        return body;
    }

    /** Runs data/nl_eval.jsonl through the compiler and reports per-case results and accuracy. */
    @PostMapping("/eval")
    public JsonNode runEval() {
        return eval.run();
    }
}
