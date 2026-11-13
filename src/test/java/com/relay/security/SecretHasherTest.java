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
    void producesTheKnownSha256TestVectors() {
        assertThat(SecretHasher.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(SecretHasher.sha256Hex("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void aMalformedStoredHashNeverMatchesAndNeverThrows() {
        assertThat(SecretHasher.matches("s3cret", "")).isFalse();
        assertThat(SecretHasher.matches("s3cret", "not-hex")).isFalse();
        assertThat(SecretHasher.matches("s3cret", SecretHasher.sha256Hex("s3cret").toUpperCase())).isFalse();
        assertThat(SecretHasher.matches("s3cret", SecretHasher.sha256Hex("s3cret").substring(1))).isFalse();
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
        assertThat(reg.authenticate(null)).as("no bearer token presented").isEmpty();
    }

    @Test
    void theSameTokenForTwoPrincipalsIsAStartupError() {
        RelayProperties p = new RelayProperties();
        p.getSecurity().setApiToken("shared-token");
        RelayProperties.TokenPrincipal other = new RelayProperties.TokenPrincipal();
        other.setName("other");
        other.setToken("shared-token");
        other.setRoles(List.of("approver"));
        p.getSecurity().setTokens(List.of(other));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PrincipalRegistry(p))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("more than one principal");
    }

    @Test
    void blankConfiguredTokensAreIgnoredAndRolesAreCopiedDefensively() {
        RelayProperties p = new RelayProperties();
        p.getSecurity().setApiToken("  ");
        RelayProperties.TokenPrincipal blank = new RelayProperties.TokenPrincipal();
        blank.setToken("");
        p.getSecurity().setTokens(List.of(blank));
        assertThat(new PrincipalRegistry(p).authenticate("anything")).isEmpty();

        java.util.Set<String> roles = new java.util.HashSet<>(java.util.Set.of("BUILDER"));
        RelayPrincipal principal = new RelayPrincipal("x", roles);
        roles.add("APPROVER");
        assertThat(principal.roles()).containsExactly("BUILDER");
        assertThat(new RelayPrincipal("y", null).roles()).isEmpty();
    }
}
