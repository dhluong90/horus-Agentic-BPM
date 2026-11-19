package io.github.dhluong90.bpm.host.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** RLE-026 and PD-101, PD-102: the shared retirement settings of both engines, read once from the host. */
class RetirementSettingsTest {

    @Test
    void RLE_026_retire_approval_defaults_to_single_and_four_eyes_stops_start_up() {
        assertThat(RetireApproval.from(new MockEnvironment())).isEqualTo(RetireApproval.SINGLE);
        assertThat(RetireApproval.from(new MockEnvironment().withProperty(RetireApproval.PROPERTY, " Single ")).value())
                .isEqualTo("single");
        assertThatThrownBy(() -> RetireApproval.from(new MockEnvironment().withProperty(RetireApproval.PROPERTY,
                "four-eyes"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("horus.bpm.lifecycle.retire-approval").hasMessageContaining("not available yet");
        assertThatThrownBy(() -> RetireApproval.from(new MockEnvironment().withProperty(RetireApproval.PROPERTY, "two")))
                .hasMessageContaining("must be single");
    }

    @Test
    void RLE_026_retire_last_version_defaults_to_confirm_refuse_is_accepted_and_four_eyes_stops_start_up() {
        assertThat(RetireLastVersion.from(new MockEnvironment())).isEqualTo(RetireLastVersion.CONFIRM);
        assertThat(RetireLastVersion.from(new MockEnvironment().withProperty(RetireLastVersion.PROPERTY, "refuse"))
                .value()).isEqualTo("refuse");
        assertThatThrownBy(() -> RetireLastVersion.from(new MockEnvironment().withProperty(RetireLastVersion.PROPERTY,
                "four-eyes"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("horus.bpm.lifecycle.retire-last-version").hasMessageContaining("not available yet");
        assertThatThrownBy(() -> RetireLastVersion.from(new MockEnvironment().withProperty(RetireLastVersion.PROPERTY,
                "ask"))).hasMessageContaining("confirm (default) or refuse");
    }
}
