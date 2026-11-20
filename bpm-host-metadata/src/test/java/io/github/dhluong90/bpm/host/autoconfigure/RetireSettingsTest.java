package io.github.dhluong90.bpm.host.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** PD-101 and PD-102: the shared retirement settings, their defaults and their refusals. */
class RetireSettingsTest {

    @Test
    void retire_settings_default_to_single_and_confirm_and_refuse_unknown_values() {
        assertThat(RetireApproval.from(new MockEnvironment())).isEqualTo(RetireApproval.SINGLE);
        assertThat(RetireLastVersion.from(new MockEnvironment())).isEqualTo(RetireLastVersion.CONFIRM);
        assertThat(RetireApproval.from(new MockEnvironment().withProperty(RetireApproval.PROPERTY, "four-eyes")).value())
                .isEqualTo("four-eyes");
        assertThat(RetireLastVersion.from(new MockEnvironment().withProperty(RetireLastVersion.PROPERTY, "refuse")))
                .isEqualTo(RetireLastVersion.REFUSE);

        assertThatThrownBy(() -> RetireApproval.from(new MockEnvironment().withProperty(RetireApproval.PROPERTY, "two")))
                .hasMessageContaining("horus.bpm.lifecycle.retire-approval").hasMessageContaining("four-eyes");
        assertThatThrownBy(() -> RetireLastVersion.from(new MockEnvironment()
                .withProperty(RetireLastVersion.PROPERTY, "maybe")))
                .hasMessageContaining("horus.bpm.lifecycle.retire-last-version").hasMessageContaining("refuse");
    }
}
