package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.internal.service.MaskingStartupCheck;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The start-up warning for a host masking policy that declares no keys (TR-SEC-10). */
@ExtendWith(OutputCaptureExtension.class)
class RuleEngineMaskingCheckTest {

    /** A store without live rule sets: only the RLE-035 read is ever called. */
    static final RuleStore EMPTY = (RuleStore) Proxy.newProxyInstance(RuleStore.class.getClassLoader(),
            new Class<?>[] {RuleStore.class}, (proxy, method, args) -> {
                if (method.getName().equals("liveVersionInputs")) {
                    return List.of();
                }
                throw new UnsupportedOperationException(method.getName());
            });

    @Test
    void TR_SEC_10_a_host_policy_that_declares_no_keys_is_named_in_a_start_up_warning(CapturedOutput output) {
        MaskingPolicy vault = tenant -> Optional.of(MaskingKey.of(new byte[32]));
        RuleEngineMaskingCheck.of(new MaskingStartupCheck(EMPTY, vault), vault).verify();
        assertThat(output.getAll()).contains("The masking policy " + vault.getClass().getName()
                + " declares no keys in configuredKeys()");
    }

    @Test
    void TR_SEC_10_a_host_policy_that_declares_its_keys_gets_no_warning(CapturedOutput output) {
        MaskingKey host = MaskingKey.of(new byte[32]);
        MaskingPolicy declared = MaskingPolicy.withKey(host);
        assertThat(declared.configuredKeys()).isEqualTo(Map.of(MaskingPolicy.HOST_SCOPE, List.of(host)));
        RuleEngineMaskingCheck.of(new MaskingStartupCheck(EMPTY, declared), declared).verify();
        assertThat(output.getAll()).doesNotContain("declares no keys");
    }
}
