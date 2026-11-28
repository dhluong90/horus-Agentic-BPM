package io.github.dhluong90.bpm.process.internal.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.ProcessException;
import io.github.dhluong90.bpm.process.api.VariableIntegrityException;
import io.github.dhluong90.bpm.process.api.VariableKeyUnavailableException;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.spi.VariableEncryptionRules;
import io.github.dhluong90.bpm.process.spi.VariableKeyring;
import io.github.dhluong90.bpm.process.spi.VariableKeyring.Key;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * PRE-521 to PRE-524 on the vault alone: a national id number and a salary, two keys, two tenants.
 */
class VariableVaultTest {

    private static final String ID_NUMBER = "079012345678";
    private static final Key K1 = new Key("k1", bytes(1));
    private static final Key K2 = new Key("k2", bytes(2));
    private static final VariableEncryptionRules RULES = new VariableEncryptionRules(Set.of("idNumber"),
            Map.of("payroll", Set.of(VariableEncryptionRules.ALL)));
    private static final String VARIABLES = "{\"idNumber\":\"" + ID_NUMBER + "\",\"channel\":\"BRANCH\","
            + "\"amount\":{\"currency\":\"VND\",\"minor\":25000000}}";

    private static byte[] bytes(int seed) {
        byte[] b = new byte[32];
        java.util.Arrays.fill(b, (byte) seed);
        return b;
    }

    private static VariableKeyring ring(Key current, Key... previous) {
        return new VariableKeyring() {
            @Override
            public Optional<Key> current() {
                return Optional.ofNullable(current);
            }

            @Override
            public List<Key> previous() {
                return List.of(previous);
            }
        };
    }

    @Test
    void PRE_521_a_protected_variable_is_stored_as_an_envelope_and_comes_back_whole() {
        VariableVault vault = new VariableVault(ring(K1), RULES);

        String stored = vault.seal("bank-a", "I1", "loan", VARIABLES);

        assertThat(stored).doesNotContain(ID_NUMBER).contains("\"$enc\"", "\"kid\":\"k1\"")
                .contains("\"channel\":\"BRANCH\"");
        assertThat(Values.readMap(vault.open("bank-a", "I1", stored))).isEqualTo(Values.readMap(VARIABLES));
    }

    @Test
    void PRE_521_a_definition_entry_with_a_star_protects_every_variable_of_that_definition_only() {
        VariableVault vault = new VariableVault(ring(K1), RULES);

        String payroll = vault.seal("bank-a", "I1", "payroll", VARIABLES);
        String loan = vault.seal("bank-a", "I1", "loan", VARIABLES);

        assertThat(payroll).doesNotContain("BRANCH", "25000000", ID_NUMBER);
        assertThat(loan).contains("BRANCH", "25000000").doesNotContain(ID_NUMBER);
        assertThat(vault.encrypts("payroll", "channel")).isTrue();
        assertThat(vault.encrypts("loan", "channel")).isFalse();
    }

    @Test
    void PRE_521_two_writes_of_one_value_differ_but_both_open_to_it() {
        VariableVault vault = new VariableVault(ring(K1), RULES);

        String first = vault.seal("bank-a", "I1", "loan", VARIABLES);
        String second = vault.seal("bank-a", "I1", "loan", VARIABLES);

        assertThat(first).isNotEqualTo(second);
        assertThat(vault.open("bank-a", "I1", first)).isEqualTo(vault.open("bank-a", "I1", second));
    }

    @Test
    void PRE_521_a_value_copied_to_another_tenant_instance_or_variable_does_not_authenticate() {
        VariableVault vault = new VariableVault(ring(K1), new VariableEncryptionRules(Set.of("idNumber", "taxId"),
                Map.of()));
        String stored = vault.seal("bank-a", "I1", "loan", "{\"idNumber\":\"" + ID_NUMBER + "\"}");

        assertThatThrownBy(() -> vault.open("bank-b", "I1", stored)).isInstanceOf(VariableIntegrityException.class);
        assertThatThrownBy(() -> vault.open("bank-a", "I2", stored)).isInstanceOf(VariableIntegrityException.class);
        String renamed = stored.replace("\"idNumber\"", "\"taxId\"");
        assertThatThrownBy(() -> vault.open("bank-a", "I1", renamed)).isInstanceOf(VariableIntegrityException.class)
                .extracting(e -> ((ProcessException) e).code()).isEqualTo("VARIABLE_INTEGRITY_FAILED");
    }

    @Test
    void PRE_521_a_flipped_byte_of_the_ciphertext_is_refused() {
        VariableVault vault = new VariableVault(ring(K1), RULES);
        String stored = vault.seal("bank-a", "I1", "loan", VARIABLES);
        String ct = (String) ((Map<?, ?>) Values.readMap(stored).get("idNumber")).get("ct");
        String flipped = stored.replace(ct, (ct.charAt(0) == 'A' ? "B" : "A") + ct.substring(1));

        assertThatThrownBy(() -> vault.open("bank-a", "I1", flipped)).isInstanceOf(VariableIntegrityException.class);
    }

    @Test
    void PRE_521_a_plain_variable_shaped_like_an_envelope_is_refused() {
        VariableVault vault = new VariableVault(ring(K1), RULES);

        assertThatThrownBy(() -> vault.seal("bank-a", "I1", "loan",
                "{\"note\":{\"$enc\":\"aes-256-gcm\",\"kid\":\"k1\",\"iv\":\"AA\",\"ct\":\"AA\"}}"))
                .isInstanceOf(InputInvalidException.class);
    }

    @Test
    void PRE_522_a_value_written_under_the_old_key_opens_while_the_new_key_writes() {
        String underK1 = new VariableVault(ring(K1), RULES).seal("bank-a", "I1", "loan", VARIABLES);
        VariableVault rotated = new VariableVault(ring(K2, K1), RULES);

        assertThat(Values.readMap(rotated.open("bank-a", "I1", underK1))).isEqualTo(Values.readMap(VARIABLES));
        assertThat(rotated.seal("bank-a", "I1", "loan", VARIABLES)).contains("\"kid\":\"k2\"");
    }

    @Test
    void PRE_522_reencrypt_moves_every_envelope_to_the_current_key_and_leaves_a_current_row_alone() {
        String underK1 = new VariableVault(ring(K1), RULES).seal("bank-a", "I1", "loan", VARIABLES);
        VariableVault rotated = new VariableVault(ring(K2, K1), RULES);

        String moved = rotated.reencrypt("bank-a", "I1", underK1);

        assertThat(moved).contains("\"kid\":\"k2\"").doesNotContain("\"kid\":\"k1\"", ID_NUMBER);
        assertThat(rotated.reencrypt("bank-a", "I1", moved)).isNull();
        // the old key can now be dropped without a lockout
        VariableVault withoutOld = new VariableVault(ring(K2), RULES);
        assertThat(Values.readMap(withoutOld.open("bank-a", "I1", moved))).isEqualTo(Values.readMap(VARIABLES));
    }

    @Test
    void PRE_522_a_rotation_keeps_the_values_the_engine_reads_identical() {
        String underK1 = new VariableVault(ring(K1), RULES).seal("bank-a", "I1", "loan", VARIABLES);
        VariableVault rotated = new VariableVault(ring(K2, K1), RULES);

        String before = rotated.open("bank-a", "I1", underK1);
        String after = rotated.open("bank-a", "I1", rotated.reencrypt("bank-a", "I1", underK1));

        assertThat(after).isEqualTo(before);
    }

    @Test
    void PRE_523_a_key_id_the_keyring_does_not_hold_is_refused_with_its_own_code_and_no_value() {
        String underK1 = new VariableVault(ring(K1), RULES).seal("bank-a", "I1", "loan", VARIABLES);
        VariableVault lost = new VariableVault(ring(K2), RULES);

        assertThatThrownBy(() -> lost.open("bank-a", "I1", underK1)).isInstanceOf(VariableKeyUnavailableException.class)
                .hasMessageContaining("'k1'").hasMessageNotContaining(ID_NUMBER)
                .extracting(e -> ((ProcessException) e).code()).isEqualTo("VARIABLE_KEY_UNAVAILABLE");
    }

    @Test
    void PRE_523_a_protected_variable_with_no_current_key_is_refused_and_never_stored_in_the_clear() {
        VariableVault none = new VariableVault(VariableKeyring.none(), RULES);

        assertThatThrownBy(() -> none.seal("bank-a", "I1", "loan", VARIABLES))
                .isInstanceOf(VariableKeyUnavailableException.class).hasMessageNotContaining(ID_NUMBER);
    }

    @Test
    void PRE_523_a_vault_without_rules_still_refuses_a_stored_envelope_instead_of_passing_it_on() {
        String underK1 = new VariableVault(ring(K1), RULES).seal("bank-a", "I1", "loan", VARIABLES);

        assertThatThrownBy(() -> VariableVault.NONE.open("bank-a", "I1", underK1))
                .isInstanceOf(VariableKeyUnavailableException.class);
    }

    @Test
    void PRE_523_a_row_that_holds_no_envelope_and_no_protected_variable_passes_untouched() {
        assertThat(VariableVault.NONE.seal("bank-a", "I1", "loan", VARIABLES)).isSameAs(VARIABLES);
        assertThat(VariableVault.NONE.open("bank-a", "I1", VARIABLES)).isSameAs(VARIABLES);
    }

    @Test
    void PRE_524_no_key_and_no_value_appear_in_a_key_text_or_a_refusal() {
        assertThat(K1.toString()).doesNotContain("1, 1").contains("redacted");
        VariableVault vault = new VariableVault(ring(K1), RULES);
        String stored = vault.seal("bank-a", "I1", "loan", VARIABLES);
        VariableVault wrongKey = new VariableVault(ring(new Key("k1", bytes(9))), RULES);

        assertThatThrownBy(() -> wrongKey.open("bank-a", "I1", stored)).isInstanceOf(VariableIntegrityException.class)
                .satisfies(e -> assertThat(e.getMessage() + e).doesNotContain(ID_NUMBER, "25000000"));
    }

    @Test
    void PRE_521_a_key_must_be_exactly_32_bytes_with_a_plain_id() {
        assertThatThrownBy(() -> new Key("k1", new byte[16])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Key("bad id", bytes(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
