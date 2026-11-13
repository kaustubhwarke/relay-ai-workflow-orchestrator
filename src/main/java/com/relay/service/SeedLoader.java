package com.relay.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.config.RelayProperties;
import com.relay.domain.RelayException;
import com.relay.repository.WorkflowRepository;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Loads the provided seed workflows as <b>Published</b> at startup (idempotent: existing ids are left alone).
 * Workflow ids such as {@code wf_support_triage} and the definition JSON shape are preserved exactly; the only
 * substitution is the mock-world base URL, which the dataset documents as engine configuration.
 */
@Component
public class SeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);
    static final String DATASET_WORLD_URL = "http://localhost:9210";

    private final WorkflowService workflowService;
    private final WorkflowRepository workflows;
    private final ObjectMapper mapper;
    private final RelayProperties props;

    public SeedLoader(WorkflowService workflowService, WorkflowRepository workflows, ObjectMapper mapper, RelayProperties props) {
        this.workflowService = workflowService;
        this.workflows = workflows;
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!props.getSeed().isEnabled()) return;
        String raw;
        try (InputStream in = new ClassPathResource("data/seed_workflows.json").getInputStream()) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String world = props.getMockWorld().getUrl().replaceAll("/+$", "");
        raw = raw.replace(DATASET_WORLD_URL, world);
        JsonNode root = mapper.readTree(raw);
        int loaded = 0;
        for (JsonNode def : root.path("workflows")) {
            String id = def.path("id").asText();
            if (workflows.exists(id)) {
                finishPartialSeed(id);
                continue;
            }
            try {
                workflowService.create(def);
            } catch (DuplicateKeyException e) {
                log.info("Seed workflow {} was created concurrently by another instance", id);
                continue;
            } catch (RelayException e) {
                if (!"workflow_exists".equals(e.code())) throw e; // only a duplicate id is the benign race
                log.info("Seed workflow {} was created concurrently by another instance", id);
                continue;
            }
            workflowService.publish(id);
            loaded++;
        }
        log.info("Seed workflows: {} loaded as published, {} already present", loaded, root.path("workflows").size() - loaded);
    }

    /** A seed created by an earlier start that stopped before publishing is completed instead of being left as a draft. */
    private void finishPartialSeed(String id) {
        if (workflows.findById(id).map(w -> !w.hasPublishedVersion()).orElse(false)) {
            workflowService.publish(id);
            log.info("Seed workflow {} was found unpublished and has been published", id);
        }
    }
}
