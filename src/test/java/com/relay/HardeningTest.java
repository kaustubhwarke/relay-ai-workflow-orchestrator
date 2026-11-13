package com.relay;

import static com.relay.TestSupport.MAPPER;
import static com.relay.TestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.config.RelayProperties;
import com.relay.definition.DefinitionValidator;
import com.relay.definition.NodeCatalog;
import com.relay.definition.WorkflowDefinition;
import com.relay.domain.AiUsage;
import com.relay.domain.RelayException;
import com.relay.domain.RunRecord;
import com.relay.domain.RunStatus;
import com.relay.domain.WorkflowRecord;
import com.relay.domain.WorkflowVersionRecord;
import com.relay.engine.RetryPolicy;
import com.relay.engine.TemplateException;
import com.relay.engine.TemplateResolver;
import com.relay.security.SecurityConfig;
import com.relay.service.InvalidDefinitionException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

/** Invariants added in response to review: immutability, fail-closed security defaults and saturating arithmetic. */
class HardeningTest {

    // ------------------------------------------------------------------ security defaults

    private static RelayProperties props(String token, boolean apiEnabled) {
        RelayProperties p = new RelayProperties();
        p.getSecurity().setApiToken(token);
        p.getApi().setEnabled(apiEnabled);
        return p;
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        return e;
    }

    @Test
    void theApiRefusesToStartWithoutACredential() {
        SecurityConfig config = new SecurityConfig();
        assertThatThrownBy(() -> config.principalRegistry(props("", true), env()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RELAY_API_TOKEN");
        assertThatThrownBy(() -> config.principalRegistry(props(null, true), env("dev")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theWellKnownDemoTokenIsOnlyAcceptedInDevOrTest() {
        SecurityConfig config = new SecurityConfig();
        assertThatThrownBy(() -> config.principalRegistry(props("relay-demo-token", true), env()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("dev");
        assertThatThrownBy(() -> config.principalRegistry(props("relay-demo-token", true), env("prod")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(config.principalRegistry(props("relay-demo-token", true), env("dev")).authenticate("relay-demo-token")).isPresent();
        assertThat(config.principalRegistry(props("relay-demo-token", true), env("test")).authenticate("relay-demo-token")).isPresent();
        assertThat(config.principalRegistry(props("a-real-secret", true), env()).authenticate("a-real-secret")).isPresent();
    }

    @Test
    void aWorkerOnlyProcessNeedsNoApiCredential() {
        assertThat(new SecurityConfig().principalRegistry(props("", false), env("worker")).authenticate("x")).isEmpty();
    }

    // ------------------------------------------------------------------ arithmetic

    @Test
    void backoffSaturatesInsteadOfOverflowingForHugeBaseDelays() {
        RetryPolicy.Settings huge = new RetryPolicy.Settings(10, Long.MAX_VALUE / 2, 5_000);
        for (int attempt = 1; attempt <= 25; attempt++) {
            assertThat(RetryPolicy.nominalBackoffMillis(huge, attempt)).as("attempt %d", attempt).isEqualTo(5_000);
            assertThat(new RetryPolicy(new RelayProperties()).backoffMillis(huge, attempt)).isBetween(1L, 6_000L);
        }
        RetryPolicy.Settings normal = new RetryPolicy.Settings(5, 100, 10_000);
        assertThat(RetryPolicy.nominalBackoffMillis(normal, 1)).isEqualTo(100);
        assertThat(RetryPolicy.nominalBackoffMillis(normal, 3)).isEqualTo(400);
        assertThat(RetryPolicy.nominalBackoffMillis(normal, 0)).isEqualTo(100);
        assertThat(RetryPolicy.nominalBackoffMillis(new RetryPolicy.Settings(5, -50, 10_000), 2)).isZero();
    }

    @Test
    void anArrayIndexTooLargeForAnIntIsATemplateErrorNotACrash() {
        TemplateResolver resolver = new TemplateResolver();
        com.relay.engine.TemplateContext ctx = new com.relay.engine.TemplateContext("run_1", "manual",
                json("{\"items\":[1,2]}"), java.util.Map.of());
        assertThatThrownBy(() -> resolver.resolve(json("\"{{trigger.body.items.99999999999}}\""), ctx))
                .isInstanceOf(TemplateException.class).hasMessageContaining("out of range");
        assertThatThrownBy(() -> resolver.resolve(json("\"{{trigger.body.items.5}}\""), ctx)).isInstanceOf(TemplateException.class);
        assertThat(resolver.resolve(json("\"{{trigger.body.items.1}}\""), ctx).asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ immutability

    @Test
    void runAndVersionRecordsAreImmuneToChangesMadeThroughTheirInputsAndAccessors() {
        ObjectNode snapshot = (ObjectNode) json("{\"a\":1}");
        RunRecord run = new RunRecord("r", "w", 1, snapshot, RunStatus.QUEUED, "manual", json("{\"in\":1}"), null, 0, 0, null,
                false, null, null, Instant.now(), null, null);
        snapshot.put("a", 2);
        ((ObjectNode) run.definitionSnapshot()).put("a", 3);
        ((ObjectNode) run.input()).put("in", 9);
        assertThat(run.definitionSnapshot().get("a").asInt()).isEqualTo(1);
        assertThat(run.input().get("in").asInt()).isEqualTo(1);
        assertThat(run.error()).isNull();

        ObjectNode def = (ObjectNode) json("{\"id\":\"x\"}");
        WorkflowVersionRecord version = new WorkflowVersionRecord("x", 1, def, "h", Instant.now());
        def.put("id", "changed");
        ((ObjectNode) version.definition()).put("id", "changed too");
        assertThat(version.definition().get("id").asText()).isEqualTo("x");
        assertThat(new WorkflowVersionRecord("x", 1, null, null, null).definition()).isNull();
    }

    @Test
    void workflowRecordDistinguishesPublishedStateFromHavingAPublishedVersion() {
        WorkflowRecord edited = new WorkflowRecord("w", "n", "d", "draft", json("{}"), null, 2, Instant.now(), Instant.now(), Instant.now());
        assertThat(edited.isPublished()).isFalse();
        assertThat(edited.hasPublishedVersion()).isTrue();
        WorkflowRecord fresh = new WorkflowRecord("w", "n", "d", "draft", json("{}"), null, 0, Instant.now(), Instant.now(), null);
        assertThat(fresh.hasPublishedVersion()).isFalse();
        WorkflowRecord published = new WorkflowRecord("w", "n", "d", "published", json("{}"), null, 1, Instant.now(), Instant.now(), Instant.now());
        assertThat(published.isPublished()).isTrue();
    }

    @Test
    void theCatalogAndParsedDefinitionsCannotBeModifiedByCallers() {
        NodeCatalog catalog = TestSupport.catalog();
        ((ObjectNode) catalog.raw()).removeAll();
        assertThat(catalog.raw().path("nodes")).isNotEmpty();
        assertThatThrownBy(() -> catalog.nodeTypes().put("x", null)).isInstanceOf(UnsupportedOperationException.class);
        NodeCatalog.NodeType notify = catalog.nodeType("notify").orElseThrow();
        assertThatThrownBy(() -> notify.params().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> notify.branches().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> notify.params().values().iterator().next().enumValues().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> catalog.triggerType("webhook").orElseThrow().config().clear()).isInstanceOf(UnsupportedOperationException.class);

        WorkflowDefinition def = WorkflowDefinition.parse(TestSupport.seed("wf_support_triage"), 50);
        assertThatThrownBy(() -> def.nodes().clear()).isInstanceOf(UnsupportedOperationException.class);
        WorkflowDefinition.Node n = def.nodes().values().iterator().next();
        n.params().removeAll();
        assertThat(n.params()).isNotEmpty();
    }

    @Test
    void aSharedAiUsageSentinelHasNoTokens() {
        assertThat(AiUsage.NONE.promptTokens()).isNull();
        assertThat(AiUsage.NONE.attempts()).isNull();
    }

    // ------------------------------------------------------------------ exceptions

    @Test
    void relayExceptionRequiresAStatusAndACode() {
        assertThatThrownBy(() -> new RelayException(null, "c", "m")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RelayException(HttpStatus.BAD_REQUEST, null, "m")).isInstanceOf(NullPointerException.class);
        assertThat(new RelayException(HttpStatus.CONFLICT, "c", "m", null).details()).isEmpty();
        assertThat(RelayException.notFound("Thing", "t1").status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(RelayException.badRequest("b", "m").status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anEmptyIssueListStillProducesAValidationError() {
        for (List<DefinitionValidator.Issue> none : java.util.Arrays.<List<DefinitionValidator.Issue>>asList(List.of(), null)) {
            InvalidDefinitionException e = new InvalidDefinitionException(none);
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.code()).isEqualTo("invalid_definition");
        }
        InvalidDefinitionException two = new InvalidDefinitionException(List.of(
                new DefinitionValidator.Issue("a", "first", null), new DefinitionValidator.Issue("b", "second", null)));
        assertThat(two.getMessage()).contains("first").contains("1 more");
        assertThat(two.details()).containsExactly("a: first", "b: second");
    }

    @SuppressWarnings("unused")
    private static JsonNode unused() { return null; }
}
