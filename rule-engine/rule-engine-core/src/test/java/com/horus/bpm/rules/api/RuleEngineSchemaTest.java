package com.horus.bpm.rules.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class RuleEngineSchemaTest {

    @Test
    void owns_only_tables_with_the_engine_prefix() {
        assertThat(RuleEngineSchema.ownsTable("rle_decision_2027_01")).isTrue();
        assertThat(RuleEngineSchema.ownsTable(RuleEngineSchema.HISTORY_TABLE)).isTrue();
        assertThat(RuleEngineSchema.ownsTable("loan_application")).isFalse();
        assertThat(RuleEngineSchema.ownsTable("flyway_schema_history")).isFalse();
        assertThat(RuleEngineSchema.ownsTable("pre_instance")).isFalse();
    }

    @Test
    void owns_table_rejects_null() {
        assertThatIllegalArgumentException().isThrownBy(() -> RuleEngineSchema.ownsTable(null));
    }
}
