package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** AUD-13 (PRE-371): cursor keys come from configuration as Base64, and a wrong setting stops start-up naming it. */
class CursorKeyPropertiesTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void PRE_371_configured_keys_are_decoded_and_a_wrong_setting_names_the_property() {
        var cursor = new ProcessEngineProperties.Cursor("k2027", Map.of("k2027", KEY));

        assertThat(cursor.decoded()).containsOnlyKeys("k2027");
        assertThat(cursor.toString()).doesNotContain(KEY);
        assertThat(new ProcessEngineProperties.Cursor(null, null).decoded()).isEmpty();
        assertThatThrownBy(() -> new ProcessEngineProperties.Cursor("k1", Map.of("k2027", KEY)).decoded())
                .hasMessageContaining("horus.bpm.processes.cursor.active-key-id");
        assertThatThrownBy(() -> new ProcessEngineProperties.Cursor("k1", Map.of("k1", "not base64!")).decoded())
                .hasMessageContaining("horus.bpm.processes.cursor.keys.k1");
    }
}
