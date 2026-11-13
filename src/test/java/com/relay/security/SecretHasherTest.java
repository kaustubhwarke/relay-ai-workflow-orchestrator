package com.relay.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.config.RelayProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretHasherTest {

    @Test
    void hashesAreHexSha256AndDeterministic() {
        String h = SecretHasher.sha256Hex("whsec_expense_774");
        assertThat(h).hasSize(64).matches("[0-9a-f]+").isEqualTo(SecretHasher.sha256Hex("whsec_expense_774"));
        assertThat(h).doesNotContain("whsec");
    }

    @Test
    void matchesOnlyTheCorrectSecret() {
        String stored = SecretHasher.sha256Hex("s3cret");
        assertThat(SecretHasher.matches("s3cret", stored)).isTrue();
        assertThat(SecretHasher.matches("S3cret", stored)).isFalse();
        assertThat(SecretHasher.matches(null, stored)).isFalse();
        assertThat(SecretHasher.matches("s3cret", null)).isFalse();
    }

    @Test
    void principalRegistryMapsTokensToRoles() {
        RelayProperties p = new RelayProperties();
        p.getSecurity().setApiToken("demo-token");
        RelayProperties.TokenPrincipal approver = new RelayProperties.TokenPrincipal();
        approver.setName("alice");
        approver.setToken("alice-token");
        approver.setRoles(List.of("approver"));
        p.getSecurity().setTokens(List.of(approver));
        PrincipalRegistry reg = new PrincipalRegistry(p);

        assertThat(reg.authenticate("demo-token")).hasValueSatisfying(x -> assertThat(x.roles()).containsExactlyInAnyOrder("BUILDER", "OPERATOR", "APPROVER"));
        assertThat(reg.authenticate("alice-token")).hasValueSatisfying(x -> {
            assertThat(x.name()).isEqualTo("alice");
            assertThat(x.roles()).containsExactly("APPROVER");
        });
        assertThat(reg.authenticate("wrong")).isEmpty();
    }
}
