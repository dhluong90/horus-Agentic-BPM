package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.internal.service.AgentLabelLimits;
import io.github.dhluong90.bpm.process.spi.InputMasker;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * PRE-012 (#59): the label bounds bind with their defaults and may only be lowered, and the default masker of agent
 * inputs makes the Rule Engine's keyed fingerprint of the tenant, or {@code ***} without a key.
 */
class AgentHistorySettingsTest {

    private static final MaskingKey HOST_KEY = MaskingKey.of("k1", "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8));

    private static ProcessEngineProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("horus.bpm.processes", ProcessEngineProperties.class);
    }

    @Test
    void PRE_012_label_bounds_default_to_16_labels_64_character_keys_and_256_character_values() {
        assertThat(bind(Map.of()).agents().labels().toLimits()).isEqualTo(AgentLabelLimits.DEFAULTS);
        assertThat(bind(Map.of("horus.bpm.processes.agents.labels.max-count", "4")).agents().labels().toLimits())
                .isEqualTo(new AgentLabelLimits(4, 64, 256));
    }

    @Test
    void PRE_012_label_bounds_may_be_lowered_never_raised() {
        assertThatThrownBy(() -> bind(Map.of("horus.bpm.processes.agents.labels.max-count", "17")))
                .isInstanceOf(BindException.class).rootCause().hasMessageContaining("agents.labels.max-count");
        assertThatThrownBy(() -> bind(Map.of("horus.bpm.processes.agents.labels.max-value-length", "257")))
                .isInstanceOf(BindException.class).rootCause().hasMessageContaining("max-value-length");
    }

    @Test
    void PRE_012_the_default_masker_uses_the_tenants_rule_engine_key_and_redacts_without_one() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        MaskingPolicy policy = tenantId -> "bank-a".equals(tenantId) ? Optional.of(HOST_KEY.forTenant(tenantId))
                : Optional.empty();
        beans.addBean("masking", policy);

        InputMasker masker = RuleEngineDecisions.inputMasker(beans);

        assertThat(masker.mask("bank-a", "079088001234"))
                .isEqualTo(HOST_KEY.forTenant("bank-a").fingerprint("079088001234")).startsWith("hmac:k1:");
        assertThat(masker.mask("bank-b", "079088001234")).isEqualTo(InputMasker.REDACTED);
        assertThat(RuleEngineDecisions.inputMasker(new StaticListableBeanFactory()).mask("bank-a", "079088001234"))
                .isEqualTo(InputMasker.REDACTED);
    }
}
