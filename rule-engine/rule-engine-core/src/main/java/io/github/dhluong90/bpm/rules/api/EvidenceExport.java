package io.github.dhluong90.bpm.rules.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * An evidence file as the engine wrote it (PD-116). The file states who exported it, when, for which tenant, and
 * {@link #contentSha256()}, the SHA-256 of its content; {@link #content()} is the whole file.
 *
 * @param ruleSet the rule set name
 * @param format the file format
 * @param content the whole file, UTF-8 text
 * @param contentSha256 the SHA-256 of the file's content, 64 lower-case hex digits
 * @param exportedBy who exported it
 * @param exportedAt when
 * @param versions the version numbers exported, ascending
 * @param decisionIds the decision ids exported, in the order listed
 */
public record EvidenceExport(String ruleSet, EvidenceFormat format, String content, String contentSha256,
        String exportedBy, Instant exportedAt, List<Integer> versions, List<String> decisionIds) {

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    public EvidenceExport {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(contentSha256, "contentSha256 must not be null");
        Objects.requireNonNull(exportedBy, "exportedBy must not be null");
        Objects.requireNonNull(exportedAt, "exportedAt must not be null");
        versions = List.copyOf(versions);
        decisionIds = List.copyOf(decisionIds);
    }

    /**
     * Returns the file as bytes.
     *
     * @return the UTF-8 bytes of {@link #content()}
     */
    public byte[] bytes() {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the file name a download should use.
     *
     * @return for example {@code card-fee-evidence-20270120T030000Z.csv}
     */
    public String fileName() {
        return ruleSet + "-evidence-" + FILE_TIME.format(exportedAt) + "." + format.extension();
    }
}
