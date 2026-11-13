package com.relay.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Selects the {@link AiProvider} from configuration (fails fast on misconfiguration). */
@Configuration
public class AiProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(AiProviderConfig.class);

    @Bean
    public AiProvider aiProvider(RelayProperties props, ObjectMapper mapper) {
        String provider = props.getAi().getProvider() == null ? "" : props.getAi().getProvider().toLowerCase();
        return switch (provider) {
            case "openai" -> {
                log.info("AI provider: OpenAI-compatible endpoint {} model {}", props.getAi().getBaseUrl(), props.getAi().getModel());
                yield new OpenAiCompatibleProvider(props.getAi(), mapper);
            }
            case "heuristic" -> {
                log.info("AI provider: offline heuristic model (no network, deterministic)");
                yield new HeuristicAiProvider(mapper);
            }
            default -> throw new IllegalStateException("Unknown relay.ai.provider '" + provider + "' (use 'openai' or 'heuristic')");
        };
    }
}
