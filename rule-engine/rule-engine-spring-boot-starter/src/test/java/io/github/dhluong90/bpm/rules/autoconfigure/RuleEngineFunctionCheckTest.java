package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.internal.service.FunctionStartupCheck;
import io.github.dhluong90.bpm.rules.internal.service.HostFunctions;
import io.github.dhluong90.bpm.rules.internal.store.FunctionVersionStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The function entries of the rules settings in {@code /api/bpm/meta} (RLE-058, PD-119): the policy in force, and the
 * functions whose version changed, shown only to a tenant that owns a published version that calls them.
 */
class RuleEngineFunctionCheckTest {

    static final FunctionStartupCheck.Change SEGMENT_OF = new FunctionStartupCheck.Change("segmentOf", "2027.1", "2027.2",
            List.of(new FunctionVersionStore.Use("bank-a", "card-fee", 1, "segmentOf", "2027.1")));
    static final FunctionStartupCheck.Change REMOVED = new FunctionStartupCheck.Change("riskBand", "3", null,
            List.of(new FunctionVersionStore.Use("bank-b", "loan-pricing", 2, "riskBand", "3")));

    @Test
    void RLE_058_meta_shows_a_changed_function_only_to_the_tenant_that_owns_a_version_calling_it() {
        RuleEngineFunctionCheck check = new RuleEngineFunctionCheck(() -> List.of(SEGMENT_OF, REMOVED));
        check.verify();

        var settings = check.tenantSettings(HostFunctions.VersionChange.WARN);

        assertThat(settings.module()).isEqualTo("rules");
        assertThat(settings.names()).containsExactly("evaluation.function-version-change",
                "evaluation.functions-changed");
        assertThat(settings.settings("bank-a")).containsEntry("evaluation.function-version-change", "warn")
                .containsEntry("evaluation.functions-changed", "segmentOf:2027.1>2027.2");
        assertThat(settings.settings("bank-b")).containsEntry("evaluation.functions-changed", "riskBand:3>-");
        assertThat(settings.settings("bank-c")).containsEntry("evaluation.functions-changed", "");
        assertThat(check.tenantSettings(HostFunctions.VersionChange.REFUSE).settings("bank-c"))
                .containsEntry("evaluation.function-version-change", "refuse");
    }

    @Test
    void RLE_058_nothing_is_listed_before_the_check_ran_and_the_setting_value_is_validated() {
        RuleEngineFunctionCheck check = new RuleEngineFunctionCheck(() -> List.of(SEGMENT_OF));

        assertThat(check.tenantSettings(HostFunctions.VersionChange.WARN).settings("bank-a"))
                .containsEntry("evaluation.functions-changed", "");
        assertThat(HostFunctions.VersionChange.parse(null)).isEqualTo(HostFunctions.VersionChange.WARN);
        assertThat(HostFunctions.VersionChange.parse("refuse")).isEqualTo(HostFunctions.VersionChange.REFUSE);
        assertThatThrownBy(() -> HostFunctions.VersionChange.parse("maybe")).hasMessage(
                "horus.bpm.rules.evaluation.function-version-change must be warn or refuse, was 'maybe'");
    }
}
