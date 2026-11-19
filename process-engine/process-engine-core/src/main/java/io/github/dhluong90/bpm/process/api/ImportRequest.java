package io.github.dhluong90.bpm.process.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * A definition document to import.
 *
 * @param format format of {@code source}
 * @param source the document bytes, stored byte for byte
 * @param idempotencyKey optional key; a replay with the same key and bytes returns the original version
 */
public record ImportRequest(SourceFormat format, byte[] source, String idempotencyKey) {

    public ImportRequest {
        Objects.requireNonNull(format, "format must not be null");
        source = Objects.requireNonNull(source, "source must not be null").clone();
    }

    /**
     * A BPMN 2.0 XML document without an idempotency key.
     *
     * @param source the XML bytes
     * @return the request
     */
    public static ImportRequest bpmn(byte[] source) {
        return new ImportRequest(SourceFormat.BPMN_XML, source, null);
    }

    /**
     * @param key the idempotency key
     * @return a copy with the key
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
