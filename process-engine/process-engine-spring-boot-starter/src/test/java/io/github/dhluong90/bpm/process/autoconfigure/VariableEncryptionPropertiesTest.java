package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.internal.store.VariableVault;
import io.github.dhluong90.bpm.process.spi.VariableKeyring;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** PRE-521 and PRE-523: variable encryption is configured, and a half-configured host stops at start-up. */
class VariableEncryptionPropertiesTest {

    private static final String P = "horus.bpm.processes.variable-encryption.";
    private static final String K1 = Base64.getEncoder().encodeToString(new byte[32]);

    private static ProcessEngineProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source)).bindOrCreate("horus.bpm.processes",
                ProcessEngineProperties.class);
    }

    @Test
    void PRE_521_nothing_is_encrypted_until_a_variable_is_named() {
        ProcessEngineProperties properties = bind(Map.of());

        assertThat(properties.variableEncryption().rules().isEmpty()).isTrue();
        assertThat(properties.variableEncryption().keyring().current()).isEmpty();
    }

    @Test
    void PRE_521_variables_definitions_and_keys_bind_from_configuration_and_the_active_key_writes() {
        Map<String, String> source = new HashMap<>();
        source.put(P + "variables[0]", "idNumber");
        source.put(P + "definitions.payroll[0]", "*");
        source.put(P + "active-key-id", "k2");
        source.put(P + "keys.k1", K1);
        source.put(P + "keys.k2", Base64.getEncoder().encodeToString(new byte[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}));

        ProcessEngineProperties.VariableEncryption encryption = bind(source).variableEncryption();

        assertThat(encryption.rules().encrypts("loan", "idNumber")).isTrue();
        assertThat(encryption.rules().encrypts("payroll", "salary")).isTrue();
        assertThat(encryption.rules().encrypts("loan", "salary")).isFalse();
        assertThat(encryption.keyring().current()).get().extracting(VariableKeyring.Key::id).isEqualTo("k2");
        assertThat(encryption.keyring().previous()).extracting(VariableKeyring.Key::id).containsExactly("k1");
        assertThat(encryption.toString()).doesNotContain(K1);
    }

    @Test
    void PRE_523_a_key_that_is_not_32_bytes_or_an_active_id_that_is_missing_names_the_property() {
        assertThatThrownBy(() -> bind(Map.of(P + "active-key-id", "k1", P + "keys.k1", "AAAA")).variableEncryption()
                .keyring()).hasMessageContaining("variable-encryption.keys.k1");
        assertThatThrownBy(() -> bind(Map.of(P + "active-key-id", "k9", P + "keys.k1", K1)).variableEncryption()
                .keyring()).hasMessageContaining("variable-encryption.active-key-id");
    }

    @Test
    void PRE_523_naming_a_variable_without_a_key_stops_start_up_and_never_falls_back_to_the_clear() {
        ProcessEngineProperties properties = bind(Map.of(P + "variables[0]", "idNumber"));

        assertThatThrownBy(() -> new ProcessEngineAutoConfiguration().processEngineVariableVault(
                properties.variableEncryption().keyring(), properties))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("variable-encryption");
    }

    @Test
    void PRE_523_keys_without_rules_start_a_vault_that_only_opens_what_it_finds() {
        ProcessEngineProperties properties = bind(Map.of(P + "active-key-id", "k1", P + "keys.k1", K1));

        VariableVault vault = new ProcessEngineAutoConfiguration()
                .processEngineVariableVault(properties.variableEncryption().keyring(), properties);

        assertThat(vault.encrypts("loan", "idNumber")).isFalse();
        assertThatThrownBy(() -> VariableVault.NONE.open("t", "i", "{\"a\":{\"$enc\":\"aes-256-gcm\"}}"))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.VariableIntegrityException.class);
    }
}
