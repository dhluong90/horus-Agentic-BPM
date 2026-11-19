package com.horus.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.horus.bpm.rules.api.MaskingKey;
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
                        "horus.bpm.rules.masking.tenants.bank-b.mask-default=true",
                        "horus.bpm.rules.decision-log.mask-default=false")
                .run(context -> {
                    PropertyMaskingPolicy policy = context.getBean(PropertyMaskingPolicy.class);
                    assertThat(fingerprint(policy, "bank-a")).isEqualTo(MaskingKey.fromBase64(HOST_KEY).fingerprint("x"));
                    assertThat(fingerprint(policy, "bank-b")).isEqualTo(MaskingKey.fromBase64(TENANT_KEY).fingerprint("x"));
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
                        .isEqualTo(MaskingKey.fromBase64(SHARED_KEY).fingerprint("x")));
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

    static String fingerprint(PropertyMaskingPolicy policy, String tenant) {
        return policy.hmacKey(tenant).orElseThrow().fingerprint("x");
    }

    static String key(char fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
