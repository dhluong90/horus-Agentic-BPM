package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * The id a process had in the file it was read from, when the reader had to map it to a definition key (kebab-case,
 * starting with a letter). The export writes it back; it is not part of the canonical form, so it never changes a
 * definition hash.
 *
 * @param id the original id, as written in the file
 */
public record OriginalId(String id) implements HorusExtension {
}
