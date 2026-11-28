package io.github.dhluong90.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import org.junit.jupiter.api.Test;

/** RLE-228 (#357): {@code X-Source-File-Name} is ASCII on the wire, so a Vietnamese file name is percent-encoded. */
class SourceFileNameHeaderTest {

    @Test
    void RLE_228_an_ascii_name_passes_and_a_percent_encoded_utf8_name_is_decoded() {
        assertThat(RestSupport.sourceFileName("card-fee.yaml")).isEqualTo("card-fee.yaml");
        assertThat(RestSupport.sourceFileName("Bi%E1%BB%83u%20ph%C3%AD%20th%E1%BA%BB.yaml"))
                .isEqualTo("Biểu phí thẻ.yaml");
        assertThat(RestSupport.sourceFileName("100%25-fee+v2.yaml")).isEqualTo("100%-fee+v2.yaml");
    }

    @Test
    void RLE_228_no_header_or_a_blank_one_is_no_name() {
        assertThat(RestSupport.sourceFileName(null)).isNull();
        assertThat(RestSupport.sourceFileName("  ")).isNull();
    }

    @Test
    void RLE_228_a_malformed_encoding_is_input_invalid() {
        assertThatThrownBy(() -> RestSupport.sourceFileName("fee%2")).isInstanceOf(InputInvalidException.class);
        assertThatThrownBy(() -> RestSupport.sourceFileName("fee%ZZ.yaml")).isInstanceOf(InputInvalidException.class);
        assertThatThrownBy(() -> RestSupport.sourceFileName("fee%C3.yaml")).isInstanceOf(InputInvalidException.class);
        assertThatThrownBy(() -> RestSupport.sourceFileName("phí.yaml")).isInstanceOf(InputInvalidException.class);
    }
}
