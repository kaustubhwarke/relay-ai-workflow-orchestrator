package com.relay.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.config.RelayProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class EgressPolicyTest {

    private EgressPolicy policy(String... extraHosts) {
        RelayProperties p = new RelayProperties();
        p.getMockWorld().setUrl("http://localhost:9210");
        p.getHttp().setAllowedHosts(List.of(extraHosts));
        return new EgressPolicy(p);
    }

    @Test
    void allowsTheMockWorldHostAndConfiguredHosts() {
        EgressPolicy policy = policy("api.partner.example");
        assertThat(policy.check("http://localhost:9210/orders/ord_2001").getHost()).isEqualTo("localhost");
        assertThat(policy.check("https://api.partner.example/v1/x").getHost()).isEqualTo("api.partner.example");
    }

    @Test
    void blocksCloudMetadataAndInternalHosts() {
        EgressPolicy policy = policy();
        assertThatThrownBy(() -> policy.check("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(NodeExecutionException.class).hasMessageContaining("allow-list");
        assertThatThrownBy(() -> policy.check("http://internal.corp.local/admin")).isInstanceOf(NodeExecutionException.class);
    }

    @Test
    void blocksNonHttpSchemesAndEmbeddedCredentials() {
        EgressPolicy policy = policy();
        assertThatThrownBy(() -> policy.check("file:///etc/passwd")).isInstanceOf(NodeExecutionException.class);
        assertThatThrownBy(() -> policy.check("ftp://localhost/x")).isInstanceOf(NodeExecutionException.class);
        assertThatThrownBy(() -> policy.check("http://user:pass@localhost:9210/")).isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    void violationsAreNeverRetried() {
        assertThatThrownBy(() -> policy().check("http://evil.example/"))
                .isInstanceOfSatisfying(NodeExecutionException.class, e -> assertThat(e.isRetryable()).isFalse());
    }
}
