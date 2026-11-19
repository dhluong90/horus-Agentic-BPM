package io.github.dhluong90.bpm.process.internal.json;

/** Quotes a value from a request for an error message or a log line: at most 80 characters, control characters escaped. */
public final class Quote {

    /** Longest quoted value. */
    public static final int MAX = 80;

    private Quote() {
    }

    /**
     * @param value the value, possibly null
     * @return the quoted form
     */
    public static String of(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("'");
        int limit = Math.min(value.length(), MAX);
        for (int i = 0; i < limit; i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F || c == ' ' || c == ' ') {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        if (value.length() > MAX) {
            out.append("...");
        }
        return out.append('\'').toString();
    }
}
