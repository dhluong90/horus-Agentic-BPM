package io.github.dhluong90.bpm.rules.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * A source document to import as a new draft version.
 *
 * @param format the source format; only {@link SourceFormat#YAML} is accepted in this release
 * @param source the document bytes, stored byte for byte and never executed (TR-STD-01)
 * @param idempotencyKey optional key; a replay returns the original version
 * @param correlationId optional correlation id carried by the {@code rules.version.created.v1} event; not part of
 *     the idempotent request, so a replay with another correlation id still returns the original version
 * @param sourceFileName optional name of the file the document came from, kept with the version and shown in its
 *     details and history (RLE-228). Display metadata: it is cleaned to a bare name (any directory part, control and
 *     format characters are dropped), at most {@link #MAX_FILE_NAME} characters, outside every hash and not part of the
 *     idempotent request. Never used as a path
 */
public record ImportRequest(SourceFormat format, byte[] source, String idempotencyKey, String correlationId,
        String sourceFileName) {

    /** The longest source file name kept, in characters; a longer name is {@code 400 INPUT_INVALID}. */
    public static final int MAX_FILE_NAME = 255;

    public ImportRequest {
        Objects.requireNonNull(format, "format must not be null");
        source = Objects.requireNonNull(source, "source must not be null").clone();
        sourceFileName = cleanFileName(sourceFileName);
    }

    /**
     * Creates a request that names no source file.
     *
     * @param format the source format
     * @param source the document bytes
     * @param idempotencyKey optional key
     * @param correlationId optional correlation id
     */
    public ImportRequest(SourceFormat format, byte[] source, String idempotencyKey, String correlationId) {
        this(format, source, idempotencyKey, correlationId, null);
    }

    /**
     * Cleans a file name for display: keeps what follows the last {@code /} or {@code \}, drops control and format
     * characters, and trims. A name that is empty afterwards is no name.
     *
     * @param raw the name as received, or null
     * @return the cleaned name, or null when there is none
     * @throws InputInvalidException when the cleaned name is longer than {@link #MAX_FILE_NAME} characters
     */
    public static String cleanFileName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.substring(Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1);
        StringBuilder kept = new StringBuilder(name.length());
        name.codePoints().filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT)
                .forEach(kept::appendCodePoint);
        String cleaned = kept.toString().strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() > MAX_FILE_NAME) {
            throw new InputInvalidException("the source file name is " + cleaned.length() + " characters; at most "
                    + MAX_FILE_NAME + " are kept");
        }
        return cleaned;
    }

    /**
     * Creates a request without correlation id.
     *
     * @param format the source format
     * @param source the document bytes
     * @param idempotencyKey optional key
     */
    public ImportRequest(SourceFormat format, byte[] source, String idempotencyKey) {
        this(format, source, idempotencyKey, null);
    }

    /**
     * Creates a YAML import without idempotency key.
     *
     * @param source the YAML bytes
     * @return the request
     */
    public static ImportRequest yaml(byte[] source) {
        return new ImportRequest(SourceFormat.YAML, source, null);
    }

    /**
     * Returns a copy with an idempotency key.
     *
     * @param key the key
     * @return the new request
     */
    public ImportRequest withIdempotencyKey(String key) {
        return new ImportRequest(format, source, key, correlationId, sourceFileName);
    }

    /**
     * Returns a copy with a correlation id for the event this import writes.
     *
     * @param id the correlation id, 1 to 128 characters of {@code A-Z a-z 0-9 . _ : -}
     * @return the new request
     */
    public ImportRequest withCorrelationId(String id) {
        return new ImportRequest(format, source, idempotencyKey, id, sourceFileName);
    }

    /**
     * Returns a copy that names the file the document came from.
     *
     * @param name the file name as received, cleaned to a bare name; null or blank for none
     * @return the new request
     * @throws InputInvalidException when the cleaned name is longer than {@link #MAX_FILE_NAME} characters
     */
    public ImportRequest withSourceFileName(String name) {
        return new ImportRequest(format, source, idempotencyKey, correlationId, name);
    }

    /**
     * Returns the size of the document without copying it.
     *
     * @return the number of bytes
     */
    public int size() {
        return source.length;
    }

    @Override
    public byte[] source() {
        return source.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImportRequest that && format == that.format
                && Arrays.equals(source, that.source) && Objects.equals(idempotencyKey, that.idempotencyKey)
                && Objects.equals(correlationId, that.correlationId)
                && Objects.equals(sourceFileName, that.sourceFileName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(format, Arrays.hashCode(source), idempotencyKey, correlationId, sourceFileName);
    }

    @Override
    public String toString() {
        return "ImportRequest[format=" + format + ", bytes=" + source.length + "]";
    }
}
