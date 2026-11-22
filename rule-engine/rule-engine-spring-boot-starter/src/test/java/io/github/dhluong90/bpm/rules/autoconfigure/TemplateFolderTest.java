package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.internal.service.RuleSetTemplates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The host's template folder as the starter reads it (PD-112, RLE-197, RLE-198, TR-EMB-05: the core reads no file). */
class TemplateFolderTest {

    @TempDir
    Path folder;

    @Test
    void RLE_197_the_folder_is_read_by_file_name_and_other_files_are_ignored() throws IOException {
        // Given two bank templates written out of order and a notes file
        Files.writeString(folder.resolve("b-second.json"), "{}");
        Files.writeString(folder.resolve("a-first.json"), "{}");
        Files.writeString(folder.resolve("notes.txt"), "not a template");
        Files.createDirectory(folder.resolve("nested.json"));

        // When the folder is read
        // Then only the .json files, by file name
        assertThat(TemplateFolder.read(folder.toString()).keySet()).containsExactly("a-first.json", "b-second.json");
    }

    @Test
    void RLE_197_a_folder_that_does_not_exist_adds_nothing() {
        assertThat(TemplateFolder.read(folder.resolve("missing").toString())).isEmpty();
        assertThat(TemplateFolder.read(null)).isEmpty();
        assertThat(TemplateFolder.read("  ")).isEmpty();
    }

    @Test
    void RLE_198_a_location_that_is_a_file_is_refused() throws IOException {
        Path file = Files.writeString(folder.resolve("not-a-folder"), "x");

        assertThatThrownBy(() -> TemplateFolder.read(file.toString())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a folder");
    }

    @Test
    void RLE_198_the_folder_reader_refuses_a_file_over_the_size_limit() throws IOException {
        Files.writeString(folder.resolve("big.json"), " ".repeat(RuleSetTemplates.MAX_FILE_BYTES + 1));

        assertThatThrownBy(() -> TemplateFolder.read(folder.toString())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Template file big.json").hasMessageContaining("larger than");
    }
}
