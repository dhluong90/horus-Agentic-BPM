package io.github.dhluong90.bpm.rules.internal.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** ADR-0026 decision 4: the printed canonical form must parse back to the model it was printed from. */
class CanonicalIntegrityTest {

    @Test
    void RLE_045_a_printer_that_does_not_reproduce_the_model_is_refused_at_import() throws IOException {
        byte[] cardFee;
        try (InputStream in = getClass().getResourceAsStream("/rules/card-fee.yaml")) {
            cardFee = in.readAllBytes();
        }

        // A deliberately broken printer: it prints txCount >= 3 as > 3
        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.YAML, cardFee, printed -> printed.replace("\">= 3\"", "\"> 3\"")))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("does not reproduce it");
        // The real printer passes
        assertThat(RuleSetReader.read(SourceFormat.YAML, cardFee, printed -> printed).canonicalJson()).contains("\">= 3\"");
    }
}
