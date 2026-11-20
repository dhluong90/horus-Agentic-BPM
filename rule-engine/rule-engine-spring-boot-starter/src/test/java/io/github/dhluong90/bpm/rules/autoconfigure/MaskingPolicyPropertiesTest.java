package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.api.MaskingKey;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The default masking policy bound from {@code horus.bpm.rules.masking.*} and {@code decision-log.mask-default}
 * (RLE-012, RLE-035): a host key, a key and default per tenant, the shared key, and invalid keys that are never
 * used and never printed.
 */
class MaskingPolicyPropertiesTest {

    static final String HOST_KEY = key('h');
    static final String TENANT_KEY = key('t');
    static final String SHARED_KEY = key('s');
    static final String OLD_KEY = key('o');
    static final String OLD_TENANT_KEY = key('p');

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PolicyOnly.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RuleEngineProperties.class)
    static class PolicyOnly {
        @Bean
        PropertyMaskingPolicy policy(RuleEngineProperties properties, Environment environment) {
            return PropertyMaskingPolicy.from(properties,
                    environment.getProperty(PropertyMaskingPolicy.SHARED_KEY_PROPERTY));
        }
    }

    @Test
    void RLE_012_safe_defaults_no_key_and_only_declared_inputs_masked() {
        runner.run(context -> {
            PropertyMaskingPolicy policy = context.getBean(PropertyMaskingPolicy.class);
            assertThat(policy.hmacKey("bank-a")).isEmpty();
            assertThat(policy.maskByDefault("bank-a")).isFalse();
            assertThat(policy.invalidSettings()).isEmpty();
        });
    }

    @Test
    void RLE_012_a_tenant_key_and_default_override_the_host_ones() {
        runner.withPropertyValues(
                        "horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.tenants.bank-b.hmac-key=" + TENANT_KEY,
                        "horus.bpm.rules.masking.tenants.bank-b.hmac-key-id=b1",
                        "horus.bpm.rules.masking.tenants.bank-b.mask-default=true",
                        "horus.bpm.rules.decision-log.mask-default=false")
                .run(context -> {
                    PropertyMaskingPolicy policy = context.getBean(PropertyMaskingPolicy.class);
                    // bank-a shares the host key, so it gets its own subkey; bank-b's own key is used as it is.
                    assertThat(fingerprint(policy, "bank-a"))
                            .isEqualTo(MaskingKey.fromBase64(HOST_KEY).forTenant("bank-a").fingerprint("x"));
                    assertThat(fingerprint(policy, "bank-b")).isEqualTo(MaskingKey.fromBase64("b1", TENANT_KEY).fingerprint("x"));
                    assertThat(policy.invalidSettings()).isEmpty();
                    assertThat(policy.maskByDefault("bank-a")).isFalse();
                    assertThat(policy.maskByDefault("bank-b")).isTrue();
                    assertThat(policy.toString()).doesNotContain(HOST_KEY, TENANT_KEY);
                    assertThat(context.getBean(RuleEngineProperties.class).toString())
                            .doesNotContain(HOST_KEY, TENANT_KEY);
                });
    }

    @Test
    void RLE_012_the_shared_key_is_used_when_the_rule_engine_key_is_unset() {
        runner.withPropertyValues("horus.bpm.masking.hmac-key=" + SHARED_KEY)
                .run(context -> assertThat(fingerprint(context.getBean(PropertyMaskingPolicy.class), "bank-a"))
                        .isEqualTo(MaskingKey.fromBase64(SHARED_KEY).forTenant("bank-a").fingerprint("x")));
    }

    @Test
    void RLE_035_an_invalid_tenant_key_is_reported_by_name_and_never_falls_back_to_the_host_key() {
        String short31 = Base64.getEncoder().encodeToString(new byte[31]);
        runner.withPropertyValues(
                        "horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.tenants.bank-b.hmac-key=" + short31)
                .run(context -> {
                    PropertyMaskingPolicy policy = context.getBean(PropertyMaskingPolicy.class);
                    assertThat(policy.hmacKey("bank-b")).isEmpty();
                    assertThat(policy.hmacKey("bank-a")).isPresent();
                    assertThat(policy.invalidSettings()).singleElement().asString()
                            .contains("horus.bpm.rules.masking.tenants.bank-b.hmac-key")
                            .doesNotContain(short31);
                });
    }

    @Test
    void RLE_012_key_ids_come_from_the_settings_and_default_to_k1() {
        runner.withPropertyValues(
                        "horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.tenants.bank-b.hmac-key=" + TENANT_KEY,
                        "horus.bpm.rules.masking.tenants.bank-b.hmac-key-id=bank-b-2027")
                .run(context -> {
                    PropertyMaskingPolicy policy = context.getBean(PropertyMaskingPolicy.class);
                    assertThat(policy.hmacKey("bank-a").orElseThrow().keyId()).isEqualTo("k1");
                    assertThat(fingerprint(policy, "bank-b")).startsWith("hmac:bank-b-2027:");
                    // Its own key first; then its subkey of the host key, for rows from before it had its own.
                    assertThat(policy.searchKeys("bank-b")).extracting(MaskingKey::keyId)
                            .containsExactly("bank-b-2027", "k1");
                });
        runner.withPropertyValues("horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.hmac-key-id=not a label")
                .run(context -> assertThat(context.getBean(PropertyMaskingPolicy.class).invalidSettings())
                        .singleElement().asString().contains("horus.bpm.rules.masking.hmac-key", "key id")
                        .doesNotContain(HOST_KEY));
    }

    @Test
    void RLE_012_search_limit_defaults_to_30_per_minute_per_tenant_override_and_never_below_1() {
        runner.run(context -> assertThat(context.getBean(PropertyMaskingPolicy.class).searchesPerMinute("bank-a"))
                .isEqualTo(30));
        runner.withPropertyValues("horus.bpm.rules.masking.search.max-per-minute=10",
                        "horus.bpm.rules.masking.tenants.bank-b.search.max-per-minute=2")
                .run(context -> {
                    PropertyMaskingPolicy policy = context.getBean(PropertyMaskingPolicy.class);
                    assertThat(policy.searchesPerMinute("bank-a")).isEqualTo(10);
                    assertThat(policy.searchesPerMinute("bank-b")).isEqualTo(2);
                });
        runner.withPropertyValues("horus.bpm.rules.masking.search.max-per-minute=0")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("horus.bpm.rules.masking.search.max-per-minute")
                        .hasMessageContaining("at least 1"));
    }

    @Test
    void RLE_035_a_tenant_key_equal_to_the_host_key_or_to_another_tenants_key_is_refused() {
        runner.withPropertyValues(
                        "horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.tenants.bank-b.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.tenants.bank-b.hmac-key-id=b1",
                        "horus.bpm.rules.masking.tenants.bank-c.hmac-key=" + TENANT_KEY,
                        "horus.bpm.rules.masking.tenants.bank-c.hmac-key-id=c1",
                        "horus.bpm.rules.masking.tenants.bank-d.hmac-key=" + TENANT_KEY,
                        "horus.bpm.rules.masking.tenants.bank-d.hmac-key-id=d1")
                .run(context -> assertThat(context.getBean(PropertyMaskingPolicy.class).invalidSettings())
                        .anySatisfy(problem -> assertThat(problem)
                                .contains("horus.bpm.rules.masking.tenants.bank-b.hmac-key", "equals the host key"))
                        .anySatisfy(problem -> assertThat(problem)
                                .contains("horus.bpm.rules.masking.tenants.bank-c.hmac-key", "tenant bank-d"))
                        .allSatisfy(problem -> assertThat(problem).doesNotContain(HOST_KEY, TENANT_KEY)));
    }

    @Test
    void RLE_035_a_tenant_key_id_equal_to_the_host_key_id_is_refused() {
        runner.withPropertyValues(
                        "horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.tenants.bank-b.hmac-key=" + TENANT_KEY)
                .run(context -> assertThat(context.getBean(PropertyMaskingPolicy.class).invalidSettings())
                        .singleElement().asString()
                        .contains("horus.bpm.rules.masking.tenants.bank-b.hmac-key-id", "host key id k1"));
        runner.withPropertyValues(
                        "horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.hmac-key-id=host-2027",
                        "horus.bpm.rules.masking.tenants.bank-b.hmac-key=" + TENANT_KEY,
                        "horus.bpm.rules.masking.tenants.bank-b.hmac-key-id=host-2027")
                .run(context -> assertThat(context.getBean(PropertyMaskingPolicy.class).invalidSettings())
                        .singleElement().asString().contains("host key id host-2027"));
        // Without a host key there is nothing to confuse, and the default id is fine.
        runner.withPropertyValues("horus.bpm.rules.masking.tenants.bank-b.hmac-key=" + TENANT_KEY)
                .run(context -> assertThat(context.getBean(PropertyMaskingPolicy.class).invalidSettings()).isEmpty());
    }

    @Test
    void RLE_012_previous_keys_feed_the_search_and_never_the_writing() {
        runner.withPropertyValues(
                        "horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.hmac-key-id=k2",
                        "horus.bpm.rules.masking.previous-keys[0].hmac-key=" + OLD_KEY,
                        "horus.bpm.rules.masking.previous-keys[0].hmac-key-id=k1",
                        "horus.bpm.rules.masking.tenants.bank-b.hmac-key=" + TENANT_KEY,
                        "horus.bpm.rules.masking.tenants.bank-b.hmac-key-id=b2",
                        "horus.bpm.rules.masking.tenants.bank-b.previous-keys[0].hmac-key=" + OLD_TENANT_KEY,
                        "horus.bpm.rules.masking.tenants.bank-b.previous-keys[0].hmac-key-id=b1")
                .run(context -> {
                    PropertyMaskingPolicy policy = context.getBean(PropertyMaskingPolicy.class);
                    assertThat(policy.invalidSettings()).isEmpty();
                    // Writing: the current key only.
                    assertThat(fingerprint(policy, "bank-a")).startsWith("hmac:k2:");
                    assertThat(fingerprint(policy, "bank-b")).startsWith("hmac:b2:");
                    // Search: current first, then each earlier key; the host's through the tenant's subkey.
                    assertThat(policy.searchKeys("bank-a")).extracting(MaskingKey::keyId).containsExactly("k2", "k1");
                    assertThat(policy.searchKeys("bank-a").get(1).fingerprint("x"))
                            .isEqualTo(MaskingKey.fromBase64("k1", OLD_KEY).forTenant("bank-a").fingerprint("x"));
                    // A tenant with its own key searches its own keys, used as they are, then its host subkeys.
                    assertThat(policy.searchKeys("bank-b")).extracting(MaskingKey::keyId)
                            .containsExactly("b2", "b1", "k2", "k1");
                    assertThat(policy.searchKeys("bank-b").get(1).fingerprint("x"))
                            .isEqualTo(MaskingKey.fromBase64("b1", OLD_TENANT_KEY).fingerprint("x"));
                    // Check values are recorded per scope: host keys as configured under '*', tenant keys by tenant.
                    assertThat(policy.configuredKeys()).containsOnlyKeys("*", "bank-b");
                    assertThat(policy.configuredKeys().get("*")).extracting(MaskingKey::checkValue).containsExactly(
                            MaskingKey.fromBase64(HOST_KEY).checkValue(), MaskingKey.fromBase64(OLD_KEY).checkValue());
                    assertThat(policy.toString()).doesNotContain(HOST_KEY, OLD_KEY, TENANT_KEY, OLD_TENANT_KEY);
                    assertThat(context.getBean(RuleEngineProperties.class).toString())
                            .doesNotContain(HOST_KEY, OLD_KEY, TENANT_KEY, OLD_TENANT_KEY);
                });
        // Retired: a key no longer listed is no longer searched.
        runner.withPropertyValues("horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.hmac-key-id=k2")
                .run(context -> assertThat(context.getBean(PropertyMaskingPolicy.class).searchKeys("bank-a"))
                        .extracting(MaskingKey::keyId).containsExactly("k2"));
    }

    @Test
    void RLE_012_a_tenant_that_moves_off_the_host_key_still_finds_the_rows_it_wrote_under_it() {
        // Before: bank-a writes national id 079090001234 under its subkey of host key k1.
        String tokenOnHostKey = MaskingKey.fromBase64(HOST_KEY).forTenant("bank-a").fingerprint("079090001234");
        // After: bank-a has a key of its own, a1; the host key is unchanged.
        runner.withPropertyValues(
                        "horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.tenants.bank-a.hmac-key=" + TENANT_KEY,
                        "horus.bpm.rules.masking.tenants.bank-a.hmac-key-id=a1")
                .run(context -> {
                    PropertyMaskingPolicy policy = context.getBean(PropertyMaskingPolicy.class);
                    assertThat(policy.invalidSettings()).isEmpty();
                    assertThat(fingerprint(policy, "bank-a")).startsWith("hmac:a1:");
                    assertThat(policy.searchKeys("bank-a")).extracting(MaskingKey::keyId).containsExactly("a1", "k1");
                    assertThat(policy.searchKeys("bank-a")).extracting(key -> key.fingerprint("079090001234"))
                            .contains(tokenOnHostKey);
                    // The host key itself is never searched, only bank-a's subkey of it.
                    assertThat(policy.searchKeys("bank-a")).noneMatch(key -> key.sameKeyAs(
                            MaskingKey.fromBase64(HOST_KEY)));
                });
        // Later the host rotates to k2 and keeps k1 as a previous key: bank-a still finds its k1 rows.
        runner.withPropertyValues(
                        "horus.bpm.rules.masking.hmac-key=" + SHARED_KEY,
                        "horus.bpm.rules.masking.hmac-key-id=k2",
                        "horus.bpm.rules.masking.previous-keys[0].hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.previous-keys[0].hmac-key-id=k1",
                        "horus.bpm.rules.masking.tenants.bank-a.hmac-key=" + TENANT_KEY,
                        "horus.bpm.rules.masking.tenants.bank-a.hmac-key-id=a1")
                .run(context -> assertThat(context.getBean(PropertyMaskingPolicy.class).searchKeys("bank-a"))
                        .extracting(key -> key.fingerprint("079090001234")).contains(tokenOnHostKey));
    }

    @Test
    void TR_SEC_10_a_tenant_named_star_is_refused_because_star_is_the_host_scope() {
        runner.withPropertyValues(
                        "horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.tenants.[*].hmac-key=" + TENANT_KEY,
                        "horus.bpm.rules.masking.tenants.[*].hmac-key-id=t1")
                .run(context -> {
                    PropertyMaskingPolicy policy = context.getBean(PropertyMaskingPolicy.class);
                    assertThat(policy.invalidSettings()).singleElement().asString()
                            .contains("horus.bpm.rules.masking.tenants.[*]", "reserved for the host keys")
                            .doesNotContain(TENANT_KEY);
                    assertThat(policy.configuredKeys()).containsOnlyKeys("*");
                    assertThat(policy.configuredKeys().get("*")).singleElement()
                            .satisfies(key -> assertThat(key.sameKeyAs(MaskingKey.fromBase64(HOST_KEY))).isTrue());
                });
    }

    @Test
    void TR_SEC_10_two_keys_with_the_same_id_for_one_tenant_stop_start_up_naming_the_setting_never_the_key() {
        runner.withPropertyValues(
                        "horus.bpm.rules.masking.tenants.bank-a.hmac-key=" + TENANT_KEY,
                        "horus.bpm.rules.masking.tenants.bank-a.hmac-key-id=a1",
                        "horus.bpm.rules.masking.tenants.bank-a.previous-keys[0].hmac-key=" + OLD_TENANT_KEY,
                        "horus.bpm.rules.masking.tenants.bank-a.previous-keys[0].hmac-key-id=a1")
                .run(context -> assertThat(context.getBean(PropertyMaskingPolicy.class).invalidSettings())
                        .singleElement().asString()
                        .contains("horus.bpm.rules.masking.tenants.bank-a.previous-keys[0].hmac-key-id", "a1")
                        .doesNotContain(TENANT_KEY, OLD_TENANT_KEY));
        runner.withPropertyValues(
                        "horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.previous-keys[0].hmac-key=" + OLD_KEY,
                        "horus.bpm.rules.masking.previous-keys[0].hmac-key-id=k1")
                .run(context -> assertThat(context.getBean(PropertyMaskingPolicy.class).invalidSettings())
                        .singleElement().asString()
                        .contains("horus.bpm.rules.masking.previous-keys[0].hmac-key-id", "k1")
                        .doesNotContain(HOST_KEY, OLD_KEY));
        // A tenant's earlier key may not reuse a host key id either.
        runner.withPropertyValues(
                        "horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.tenants.bank-a.previous-keys[0].hmac-key=" + OLD_TENANT_KEY,
                        "horus.bpm.rules.masking.tenants.bank-a.previous-keys[0].hmac-key-id=k1")
                .run(context -> assertThat(context.getBean(PropertyMaskingPolicy.class).invalidSettings())
                        .singleElement().asString()
                        .contains("horus.bpm.rules.masking.tenants.bank-a.previous-keys[0].hmac-key-id",
                                "host key id k1"));
    }

    @Test
    void TR_SEC_10_a_previous_key_equal_to_a_current_key_or_without_an_id_is_refused() {
        runner.withPropertyValues(
                        "horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.previous-keys[0].hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.previous-keys[0].hmac-key-id=k0",
                        "horus.bpm.rules.masking.tenants.bank-b.hmac-key=" + TENANT_KEY,
                        "horus.bpm.rules.masking.tenants.bank-b.hmac-key-id=b2",
                        "horus.bpm.rules.masking.tenants.bank-b.previous-keys[0].hmac-key=" + TENANT_KEY,
                        "horus.bpm.rules.masking.tenants.bank-b.previous-keys[0].hmac-key-id=b1")
                .run(context -> assertThat(context.getBean(PropertyMaskingPolicy.class).invalidSettings())
                        .anySatisfy(problem -> assertThat(problem)
                                .contains("horus.bpm.rules.masking.previous-keys[0].hmac-key", "equals the host key"))
                        .anySatisfy(problem -> assertThat(problem).contains(
                                "horus.bpm.rules.masking.tenants.bank-b.hmac-key", "previous key of tenant bank-b b1"))
                        .allSatisfy(problem -> assertThat(problem).doesNotContain(HOST_KEY, TENANT_KEY)));
        runner.withPropertyValues(
                        "horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.previous-keys[0].hmac-key=" + OLD_KEY)
                .run(context -> {
                    PropertyMaskingPolicy policy = context.getBean(PropertyMaskingPolicy.class);
                    assertThat(policy.invalidSettings()).singleElement().asString()
                            .contains("horus.bpm.rules.masking.previous-keys[0].hmac-key-id", "id")
                            .doesNotContain(OLD_KEY);
                    assertThat(policy.searchKeys("bank-a")).hasSize(1);
                });
    }

    static String fingerprint(PropertyMaskingPolicy policy, String tenant) {
        return policy.hmacKey(tenant).orElseThrow().fingerprint("x");
    }

    static String key(char fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
