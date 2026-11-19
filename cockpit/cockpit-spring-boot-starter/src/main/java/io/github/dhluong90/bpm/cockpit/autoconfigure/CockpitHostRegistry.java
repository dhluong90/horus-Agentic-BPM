package io.github.dhluong90.bpm.cockpit.autoconfigure;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The {@code cockpit-hosts.json} an embedded cockpit reads: one host, this one, on the same origin
 * ({@code baseUrl} empty). The format is owned by {@code cockpit/api/schema/cockpit-hosts.v1.schema.json}.
 * Built once at start-up; it contains configuration only, never user or tenant data.
 */
final class CockpitHostRegistry {

    static final String FILE_NAME = "cockpit-hosts.json";
    private static final Pattern HOST_ID = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");

    private final byte[] json;

    CockpitHostRegistry(String hostId, CockpitProperties properties) {
        if (hostId.length() > 40 || !HOST_ID.matcher(hostId).matches()) {
            throw new IllegalArgumentException("horus.bpm.host-id must be kebab-case with at most 40 characters, was '"
                    + hostId + "'");
        }
        String label = properties.hostLabel() != null ? properties.hostLabel() : hostId;
        StringBuilder host = new StringBuilder()
                .append("{\"id\":").append(quote(hostId))
                .append(",\"label\":").append(quote(label))
                .append(",\"baseUrl\":\"\"");
        if (properties.environment() != null && !properties.environment().isBlank()) {
            host.append(",\"environment\":").append(quote(properties.environment()));
        }
        host.append('}');
        this.json = ("{\"schemaVersion\":1"
                + ",\"auth\":{\"mode\":" + quote(properties.authMode().registryValue()) + "}"
                + ",\"hosts\":[" + host + "]"
                + ",\"ui\":{\"defaultLocale\":" + quote(properties.defaultLocale()) + "}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    byte[] json() {
        return json.clone();
    }

    /** JSON string literal, RFC 8259 escaping. */
    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
