package io.github.dhluong90.bpm.rules.api;

import java.util.Locale;

/** The format of an evidence file (PD-116). */
public enum EvidenceFormat {
    /** One CSV file with a {@code record} column, {@code version event} or {@code decision}. */
    CSV("text/csv; charset=utf-8", "csv"),
    /** One JSON document with an {@code evidence} header and a {@code content} member. */
    JSON("application/json", "json");

    private final String contentType;
    private final String extension;

    EvidenceFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    /**
     * Returns the media type of the file.
     *
     * @return for example {@code text/csv; charset=utf-8}
     */
    public String contentType() {
        return contentType;
    }

    /**
     * Returns the file name extension, which is also the format's name in the REST API.
     *
     * @return {@code csv} or {@code json}
     */
    public String extension() {
        return extension;
    }

    /**
     * Reads the REST name of a format.
     *
     * @param name {@code csv} or {@code json}
     * @return the format
     * @throws InputInvalidException for any other value
     */
    public static EvidenceFormat of(String name) {
        if (name != null) {
            for (EvidenceFormat format : values()) {
                if (format.extension.equals(name.toLowerCase(Locale.ROOT))) {
                    return format;
                }
            }
        }
        throw new InputInvalidException("format must be csv or json");
    }
}
