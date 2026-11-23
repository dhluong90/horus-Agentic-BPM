package io.github.dhluong90.bpm.rules.internal.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.RuleSetSummary;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The display name a list shows: from the document when it names one, else the key made readable. */
class RuleSetDisplayNameTest {

    private static String document(String displayNameLine) {
        return """
                {"schemaVersion":1,"ruleSet":"card-fee",%s"hitPolicy":"first",
                 "inputs":[{"name":"tier","type":"string"}],
                 "outputs":[{"name":"fee","type":"money"}],
                 "rules":[{"id":"R1","when":{"tier":"== 'GOLD'"},"then":{"fee":"0 VND"}}]}
                """.formatted(displayNameLine);
    }

    private static ParsedRuleSet read(String json) {
        return RuleSetReader.read(SourceFormat.JSON, json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void RLE_203_a_document_may_name_the_rule_set_for_people_and_the_name_is_kept_in_the_canonical_form() {
        assertThat(read(document("\"displayName\":\"Card fee\",")).canonicalJson()).contains("\"displayName\":\"Card fee\"");
    }

    @Test
    void RLE_203_a_blank_or_over_long_display_name_is_refused() {
        assertThatThrownBy(() -> read(document("\"displayName\":\" \",")))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("displayName");
        assertThatThrownBy(() -> read(document("\"displayName\":\"" + "x".repeat(101) + "\",")))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("displayName");
    }

    @Test
    void RLE_203_without_a_display_name_the_key_is_made_readable() {
        RuleSetSummary summary = new RuleSetSummary("card-fee", 1, VersionStatus.DRAFT, null, Instant.EPOCH);
        assertThat(summary.displayName()).isEqualTo("Card fee");
        assertThat(new RuleSetSummary("id-check-fee", " ", 2, VersionStatus.PUBLISHED, 2, Instant.EPOCH).displayName())
                .isEqualTo("Id check fee");
        assertThat(new RuleSetSummary("card-fee", "Card fee (Gold)", 1, VersionStatus.DRAFT, null, Instant.EPOCH)
                .displayName()).isEqualTo("Card fee (Gold)");
    }
}
