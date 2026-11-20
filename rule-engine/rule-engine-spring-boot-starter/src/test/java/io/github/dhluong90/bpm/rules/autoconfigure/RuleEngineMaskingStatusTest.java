package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.host.spi.ModuleTenantSettings;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The masking key status the starter publishes in the rules settings of {@code GET /api/bpm/meta} (TR-EXT-08,
 * TR-SEC-10): {@code set} or {@code unset}, the current key id and its check value for the caller's tenant, never the
 * key. The HTTP response is covered by the host metadata tests and the example host.
 */
class RuleEngineMaskingStatusTest {

    static final byte[] HOST_BYTES = filled('h');
    static final byte[] TENANT_BYTES = filled('t');
    static final String HOST_KEY = Base64.getEncoder().encodeToString(HOST_BYTES);
    static final String TENANT_KEY = Base64.getEncoder().encodeToString(TENANT_BYTES);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RuleEngineAutoConfiguration.class))
            .withUserConfiguration(RuleEngineAutoConfigurationTest.HostWithEngineStandIns.class);

    @Test
    void TR_EXT_08_a_host_key_with_id_k1_shows_set_and_k1_and_never_the_key() {
        runner.withPropertyValues("horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.hmac-key-id=k1")
                .run(context -> {
                    ModuleTenantSettings status = context.getBean(ModuleTenantSettings.class);
                    assertThat(status.module()).isEqualTo("rules");
                    assertThat(status.names()).containsExactly("masking.hmac-key", "masking.hmac-key-id",
                            "masking.hmac-key-check");
                    Map<String, Object> bankA = status.settings("bank-a");
                    assertThat(bankA).containsExactly(Map.entry("masking.hmac-key", "set"),
                            Map.entry("masking.hmac-key-id", "k1"),
                            Map.entry("masking.hmac-key-check",
                                    MaskingKey.of(HOST_BYTES).forTenant("bank-a").checkValue()));
                    // Each tenant sees the check value of its own subkey, so tenants cannot tell they share a key.
                    assertThat(status.settings("bank-b").get("masking.hmac-key-check"))
                            .isNotEqualTo(bankA.get("masking.hmac-key-check"));
                    assertThat(bankA.toString()).doesNotContain(HOST_KEY, HexFormat.of().formatHex(HOST_BYTES));
                });
    }

    @Test
    void TR_EXT_08_a_host_without_a_key_shows_unset_and_no_id() {
        runner.run(context -> assertThat(context.getBean(ModuleTenantSettings.class).settings("bank-a"))
                .containsExactly(Map.entry("masking.hmac-key", "unset")));
    }

    @Test
    void TR_EXT_08_a_tenant_with_its_own_key_sees_its_own_id_and_others_see_the_host_id() {
        runner.withPropertyValues("horus.bpm.rules.masking.hmac-key=" + HOST_KEY,
                        "horus.bpm.rules.masking.tenants.bank-b.hmac-key=" + TENANT_KEY,
                        "horus.bpm.rules.masking.tenants.bank-b.hmac-key-id=b1")
                .run(context -> {
                    ModuleTenantSettings status = context.getBean(ModuleTenantSettings.class);
                    assertThat(status.settings("bank-b")).containsEntry("masking.hmac-key-id", "b1")
                            .containsEntry("masking.hmac-key-check", MaskingKey.of(TENANT_BYTES).checkValue());
                    assertThat(status.settings("bank-a")).containsEntry("masking.hmac-key-id", "k1");
                });
    }

    @Test
    void TR_EXT_08_a_host_masking_policy_bean_is_described_too() {
        MaskingKey vault = MaskingKey.of("vault-7", TENANT_BYTES);
        runner.withBean(MaskingPolicy.class, () -> tenant -> "bank-a".equals(tenant) ? Optional.of(vault)
                        : Optional.empty())
                .run(context -> {
                    ModuleTenantSettings status = context.getBean(ModuleTenantSettings.class);
                    assertThat(status.settings("bank-a")).containsEntry("masking.hmac-key", "set")
                            .containsEntry("masking.hmac-key-id", "vault-7");
                    assertThat(status.settings("bank-c")).containsExactly(Map.entry("masking.hmac-key", "unset"));
                });
    }

    static byte[] filled(char c) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) c);
        return bytes;
    }
}
