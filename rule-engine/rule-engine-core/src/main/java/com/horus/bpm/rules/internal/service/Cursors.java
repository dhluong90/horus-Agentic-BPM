package com.horus.bpm.rules.internal.service;

import com.horus.bpm.rules.api.QueryInvalidException;
import com.horus.bpm.rules.internal.read.RuleSetReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Opaque keyset cursors: {@code base64url("<list>:<last key>")}. A cursor names its list, so one from another
 * list is refused. It is not signed: changing it only moves the position inside the caller's own tenant, which
 * every query filters by.
 */
final class Cursors {

    /** Longest cursor accepted, in characters; a rule set name is at most 100 characters. */
    static final int MAX_LENGTH = 400;

    private Cursors() {
    }

    static String encode(String list, String lastKey) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((list + ":" + lastKey).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the key a cursor carries, or null for no cursor.
     *
     * @throws QueryInvalidException when the cursor was not issued by this list
     */
    static String decode(String list, String cursor) {
        if (cursor == null) {
            return null;
        }
        String text;
        try {
            if (cursor.isEmpty() || cursor.length() > MAX_LENGTH) {
                throw new IllegalArgumentException("length");
            }
            text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        String prefix = list + ":";
        if (!text.startsWith(prefix) || text.length() == prefix.length()) {
            throw invalid();
        }
        return text.substring(prefix.length());
    }

    /**
     * Returns the key when it is a rule set name, the only key the rule set list issues, or null for no cursor.
     * Anything else (a NUL byte, an upper-case or overlong name) is refused here, before it reaches the database.
     */
    static String ruleSetName(String key) {
        if (key == null) {
            return null;
        }
        if (!RuleSetReader.isRuleSetName(key)) {
            throw invalid();
        }
        return key;
    }

    static int positiveInt(String key) {
        try {
            int value = Integer.parseInt(key);
            if (value < 1) {
                throw invalid();
            }
            return value;
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private static QueryInvalidException invalid() {
        return new QueryInvalidException("cursor was not issued by this list; pass nextCursor unchanged");
    }
}
