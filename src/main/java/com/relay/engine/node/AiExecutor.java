package com.relay.engine.node;

import com.relay.ai.AiNodeRunner;
import com.relay.engine.TemplateResolver;
import org.springframework.stereotype.Component;

/** {@code ai}: prompt template + JSON Schema; the validated model output becomes the node output. */
@Component
public class AiExecutor implements NodeExecutor {

    private final AiNodeRunner runner;

    public AiExecutor(AiNodeRunner runner) {
        this.runner = runner;
    }

    @Override
    public String type() { return "ai"; }

    @Override
    public NodeOutcome execute(NodeInvocation inv) {
        String prompt = TemplateResolver.render(inv.params().get("prompt"));
        AiNodeRunner.Result r = runner.run(prompt, inv.params().get("output_schema"));
        return new NodeOutcome(r.output(), r.usage());
    }
}
