package com.horus.bpm.rules.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class RuleEngineSchemaTest {

    @Test
    void tr_emb_06_engine_history_table_is_separate_from_the_host_history() {
        assertThat(RuleEngineSchema.HISTORY_TABLE)
                .isEqualTo("rle_schema_history")
                .isNotEqualTo("flyway_schema_history");
    }

    @Test
    void tr_emb_06_engine_owns_only_prefixed_tables() {
        assertThat(RuleEngineSchema.ownsTable("rle_decision_2027_01")).isTrue();
        assertThat(RuleEngineSchema.ownsTable("loan_application")).isFalse();
        assertThat(RuleEngineSchema.ownsTable("flyway_schema_history")).isFalse();
        assertThat(RuleEngineSchema.ownsTable("pre_instance")).isFalse();
    }

    @Test
    void owns_table_rejects_null() {
        assertThatIllegalArgumentException().isThrownBy(() -> RuleEngineSchema.ownsTable(null));
    }
}
