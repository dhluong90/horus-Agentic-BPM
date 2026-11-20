package io.github.dhluong90.bpm.host.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** PD-096: one host property for both engines; default makers; any other value stops start-up. */
class ApproverMustDifferFromTest {

    @Test
    void shared_setting_defaults_to_makers_accepts_requester_and_refuses_other_values() {
        assertThat(ApproverMustDifferFrom.from(new MockEnvironment())).isEqualTo(ApproverMustDifferFrom.MAKERS);
        assertThat(ApproverMustDifferFrom.from(new MockEnvironment()
                .withProperty("horus.bpm.lifecycle.approver-must-differ-from", "requester")))
                .isEqualTo(ApproverMustDifferFrom.REQUESTER);
        assertThat(ApproverMustDifferFrom.REQUESTER.value()).isEqualTo("requester");

        assertThatThrownBy(() -> ApproverMustDifferFrom.from(new MockEnvironment()
                .withProperty("horus.bpm.lifecycle.approver-must-differ-from", "nobody")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("horus.bpm.lifecycle.approver-must-differ-from")
                .hasMessageContaining("makers").hasMessageContaining("requester");
    }
}
