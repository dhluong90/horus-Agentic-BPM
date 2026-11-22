package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.internal.service.RuleSetTemplates;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reads the host's template folder ({@code horus.bpm.rules.templates.location}, PD-112, RLE-197): every {@code .json}
 * file in file name order. The core does no file I/O (TR-EMB-05), so the starter reads the files once at start-up and
 * hands the bytes to {@link RuleSetTemplates}, which checks them.
 */
final class TemplateFolder {

    private TemplateFolder() {
    }

    /**
     * Reads the template files of a folder.
     *
     * @param location the folder, or null or blank for none; a folder that does not exist adds none
     * @return the files by file name, sorted by name; empty when there is no folder
     * @throws IllegalArgumentException when the location is not a folder, or a file is larger than
     *     {@link RuleSetTemplates#MAX_FILE_BYTES}, naming it
     * @throws UncheckedIOException when a file cannot be read
     */
    static Map<String, byte[]> read(String location) {
        Map<String, byte[]> files = new TreeMap<>();
        if (location == null || location.isBlank()) {
            return files;
        }
        Path folder = Path.of(location);
        if (!Files.exists(folder)) {
            return files;
        }
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("horus.bpm.rules.templates.location is not a folder: " + folder);
        }
        try (Stream<Path> entries = Files.list(folder)) {
            for (Path file : entries.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                String name = file.getFileName().toString();
                if (Files.size(file) > RuleSetTemplates.MAX_FILE_BYTES) {
                    throw new IllegalArgumentException("Template file " + name + " is larger than "
                            + RuleSetTemplates.MAX_FILE_BYTES + " bytes");
                }
                files.put(name, Files.readAllBytes(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The template folder " + folder + " cannot be read", e);
        }
        return files;
    }
}
