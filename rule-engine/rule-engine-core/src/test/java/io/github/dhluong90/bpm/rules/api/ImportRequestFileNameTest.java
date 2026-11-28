package io.github.dhluong90.bpm.rules.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** RLE-232 (#357): the source file name of an import is cleaned to a bare display name, and never used as a path. */
class ImportRequestFileNameTest {

    @Test
    void RLE_232_a_path_is_cut_to_its_last_part_and_control_and_format_characters_are_dropped() {
        assertThat(ImportRequest.cleanFileName("C:\\Users\\an\\Downloads\\card-fee.yaml")).isEqualTo("card-fee.yaml");
        assertThat(ImportRequest.cleanFileName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(ImportRequest.cleanFileName("  Biểu phí thẻ.yaml\t")).isEqualTo("Biểu phí thẻ.yaml");
        assertThat(ImportRequest.cleanFileName("fee\u0000.yaml\u202E")).isEqualTo("fee.yaml");
    }

    @Test
    void RLE_232_no_name_or_a_name_with_nothing_left_is_no_name() {
        assertThat(ImportRequest.cleanFileName(null)).isNull();
        assertThat(ImportRequest.cleanFileName("   ")).isNull();
        assertThat(ImportRequest.cleanFileName("reports/")).isNull();
        assertThat(ImportRequest.yaml(new byte[] {'a'}).sourceFileName()).isNull();
    }

    @Test
    void RLE_232_a_name_of_more_than_255_characters_is_refused_not_cut() {
        assertThat(ImportRequest.cleanFileName("x".repeat(255))).hasSize(255);
        assertThatThrownBy(() -> ImportRequest.cleanFileName("x".repeat(256)))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("256").hasMessageContaining("255");
    }
}
