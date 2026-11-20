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
 */
public record ImportRequest(SourceFormat format, byte[] source, String idempotencyKey, String correlationId) {

    public ImportRequest {
        Objects.requireNonNull(format, "format must not be null");
        source = Objects.requireNonNull(source, "source must not be null").clone();
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
        return new ImportRequest(format, source, key, correlationId);
    }

    /**
     * Returns a copy with a correlation id for the event this import writes.
     *
     * @param id the correlation id, 1 to 128 characters of {@code A-Z a-z 0-9 . _ : -}
     * @return the new request
     */
    public ImportRequest withCorrelationId(String id) {
        return new ImportRequest(format, source, idempotencyKey, id);
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
                && Objects.equals(correlationId, that.correlationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(format, Arrays.hashCode(source), idempotencyKey, correlationId);
    }

    @Override
    public String toString() {
        return "ImportRequest[format=" + format + ", bytes=" + source.length + "]";
    }
}
