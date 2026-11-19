package com.horus.bpm.rules.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * A source document to import as a new draft version.
 *
 * @param format the source format; only {@link SourceFormat#YAML} is accepted in this release
 * @param source the document bytes, stored byte for byte and never executed (TR-STD-01)
 * @param idempotencyKey optional key; a replay returns the original version
 */
public record ImportRequest(SourceFormat format, byte[] source, String idempotencyKey) {

    public ImportRequest {
        Objects.requireNonNull(format, "format must not be null");
        source = Objects.requireNonNull(source, "source must not be null").clone();
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
        return new ImportRequest(format, source, key);
    }

    @Override
    public byte[] source() {
        return source.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImportRequest that && format == that.format
                && Arrays.equals(source, that.source) && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(format, Arrays.hashCode(source), idempotencyKey);
    }

    @Override
    public String toString() {
        return "ImportRequest[format=" + format + ", bytes=" + source.length + "]";
    }
}
