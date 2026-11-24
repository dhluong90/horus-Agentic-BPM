package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** AUD-13 (RLE-221): cursor keys come from configuration as Base64, and a wrong setting stops start-up naming it. */
class CursorKeyPropertiesTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void RLE_221_configured_keys_are_decoded_and_a_wrong_setting_names_the_property() {
        var cursor = new RuleEngineProperties.Cursor("k2027", Map.of("k2027", KEY));

        assertThat(cursor.decoded()).containsOnlyKeys("k2027");
        assertThat(cursor.toString()).doesNotContain(KEY);
        assertThat(new RuleEngineProperties.Cursor(null, null).decoded()).isEmpty();
        assertThatThrownBy(() -> new RuleEngineProperties.Cursor("k1", Map.of("k2027", KEY)).decoded())
                .hasMessageContaining("horus.bpm.rules.cursor.active-key-id");
        assertThatThrownBy(() -> new RuleEngineProperties.Cursor("k1", Map.of("k1", "not base64!")).decoded())
                .hasMessageContaining("horus.bpm.rules.cursor.keys.k1");
    }

    @Test
    void RLE_221_a_start_without_cursor_keys_logs_a_warning_that_names_the_properties() {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        org.apache.commons.logging.Log log = (org.apache.commons.logging.Log) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {org.apache.commons.logging.Log.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("warn")) {
                        warnings.add(String.valueOf(args[0]));
                    }
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });

        RuleEngineServicesConfiguration.warnWhenNoCursorKeys(log, Map.of());
        assertThat(warnings).singleElement().asString().contains("cursor.keys").contains("cursor.active-key-id")
                .contains("rle_engine_key");

        warnings.clear();
        RuleEngineServicesConfiguration.warnWhenNoCursorKeys(log, Map.of("k2027", new byte[32]));
        assertThat(warnings).isEmpty();
    }
}
