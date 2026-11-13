package com.relay.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the whole platform ({@code relay.*}). Every tunable lives here so that
 * behaviour is configured, never hard-coded.
 */
@ConfigurationProperties(prefix = "relay")
public class RelayProperties {

    private final Api api = new Api();
    private final MockWorld mockWorld = new MockWorld();
    private final Security security = new Security();
    private final Worker worker = new Worker();
    private final Engine engine = new Engine();
    private final Ai ai = new Ai();
    private final Http http = new Http();
    private final Scheduler scheduler = new Scheduler();
    private final Seed seed = new Seed();

    public Api getApi() { return api; }
    public MockWorld getMockWorld() { return mockWorld; }
    public Security getSecurity() { return security; }
    public Worker getWorker() { return worker; }
    public Engine getEngine() { return engine; }
    public Ai getAi() { return ai; }
    public Http getHttp() { return http; }
    public Scheduler getScheduler() { return scheduler; }
    public Seed getSeed() { return seed; }

    /** Set {@code relay.api.enabled=false} to run a worker-only process that exposes no REST API. */
    public static class Api {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** The external "world" that notify / order_action nodes call. */
    public static class MockWorld {
        private String url = "http://localhost:9210";
        private int timeoutMs = 5000;
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    public static class Security {
        /**
         * Token for the built-in all-roles principal. There is deliberately no default: the API refuses to start
         * without a configured credential (see SecurityConfig). The well-known demo value is only accepted under
         * the {@code dev} or {@code test} profiles.
         */
        private String apiToken = "";
        /** Optional extra principals for role separation (builder / operator / approver). */
        private List<TokenPrincipal> tokens = new ArrayList<>();
        public String getApiToken() { return apiToken; }
        public void setApiToken(String apiToken) { this.apiToken = apiToken; }
        public List<TokenPrincipal> getTokens() { return tokens; }
        public void setTokens(List<TokenPrincipal> tokens) { this.tokens = tokens; }
    }

    public static class TokenPrincipal {
        private String name;
        private String token;
        private List<String> roles = new ArrayList<>();
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public List<String> getRoles() { return roles; }
        public void setRoles(List<String> roles) { this.roles = roles; }
    }

    public static class Worker {
        private boolean enabled = true;
        /**
         * Must be unique per live process: leases held by a previous incarnation of this id are released on
         * start. When blank, a unique id (host name plus a random suffix) is generated for this process.
         */
        private String id = "";
        private int concurrency = 1;
        private long pollIntervalMs = 500;
        private int leaseSeconds = 15;
        private int heartbeatSeconds = 5;
        private long recoverySweepSeconds = 30;
        private int maxClaims = 10;
        public int getMaxClaims() { return maxClaims; }
        public void setMaxClaims(int maxClaims) { this.maxClaims = maxClaims; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public int getConcurrency() { return concurrency; }
        public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
        public int getLeaseSeconds() { return leaseSeconds; }
        public void setLeaseSeconds(int leaseSeconds) { this.leaseSeconds = leaseSeconds; }
        public int getHeartbeatSeconds() { return heartbeatSeconds; }
        public void setHeartbeatSeconds(int heartbeatSeconds) { this.heartbeatSeconds = heartbeatSeconds; }
        public long getRecoverySweepSeconds() { return recoverySweepSeconds; }
        public void setRecoverySweepSeconds(long recoverySweepSeconds) { this.recoverySweepSeconds = recoverySweepSeconds; }
    }

    public static class Engine {
        private int defaultMaxSteps = 100;
        private int hardMaxSteps = 1000;
        private int defaultMaxAttempts = 4;
        private long backoffBaseMs = 500;
        private long backoffMaxMs = 10_000;
        private boolean traceStoreAiPrompts = false;
        private int aiOutputExcerptChars = 2000;
        /** Upper bound for an order_action refund amount; larger values are refused before anything is sent. */
        private double maxRefundUsd = 10_000;
        public double getMaxRefundUsd() { return maxRefundUsd; }
        public void setMaxRefundUsd(double v) { this.maxRefundUsd = v; }
        public int getDefaultMaxSteps() { return defaultMaxSteps; }
        public void setDefaultMaxSteps(int v) { this.defaultMaxSteps = v; }
        public int getHardMaxSteps() { return hardMaxSteps; }
        public void setHardMaxSteps(int v) { this.hardMaxSteps = v; }
        public int getDefaultMaxAttempts() { return defaultMaxAttempts; }
        public void setDefaultMaxAttempts(int v) { this.defaultMaxAttempts = v; }
        public long getBackoffBaseMs() { return backoffBaseMs; }
        public void setBackoffBaseMs(long v) { this.backoffBaseMs = v; }
        public long getBackoffMaxMs() { return backoffMaxMs; }
        public void setBackoffMaxMs(long v) { this.backoffMaxMs = v; }
        public boolean isTraceStoreAiPrompts() { return traceStoreAiPrompts; }
        public void setTraceStoreAiPrompts(boolean v) { this.traceStoreAiPrompts = v; }
        public int getAiOutputExcerptChars() { return aiOutputExcerptChars; }
        public void setAiOutputExcerptChars(int v) { this.aiOutputExcerptChars = v; }
    }

    public static class Ai {
        /** {@code openai} (any OpenAI-compatible endpoint) or {@code heuristic} (offline deterministic model). */
        private String provider = "heuristic";
        private String baseUrl = "https://openrouter.ai/api/v1";
        private String apiKey = "";
        private String model = "openai/gpt-4o-mini";
        private int timeoutMs = 30_000;
        private int maxOutputTokens = 800;
        private boolean jsonMode = true;
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxOutputTokens() { return maxOutputTokens; }
        public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
        public boolean isJsonMode() { return jsonMode; }
        public void setJsonMode(boolean jsonMode) { this.jsonMode = jsonMode; }
    }

    /** Egress policy for {@code http_request} nodes (SSRF protection). */
    public static class Http {
        /** Hosts http_request nodes may call. The mock-world host is always allowed. */
        private List<String> allowedHosts = new ArrayList<>();
        private int timeoutMs = 5000;
        private int maxResponseBytes = 1_048_576;
        public List<String> getAllowedHosts() { return allowedHosts; }
        public void setAllowedHosts(List<String> allowedHosts) { this.allowedHosts = allowedHosts; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    }

    public static class Scheduler {
        private boolean enabled = true;
        private long pollIntervalMs = 5000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    }

    public static class Seed {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
