package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Puts untrusted text from an imported file into a refusal detail safely: cut to {@link #MAX} characters with an
 * ellipsis, control and line-separator characters written as {@code \}{@code uXXXX}, so a detail cannot forge log
 * lines or grow with the input. Every message of this package that quotes input goes through here.
 */
final class Quote {

    /** Longest quoted value, in characters, before the ellipsis. */
    static final int MAX = 80;

    private Quote() {
    }

    /**
     * Quotes a value: {@code 'value'}.
     *
     * @param value the untrusted text, possibly null
     * @return the quoted, bounded, escaped text
     */
    static String of(String value) {
        return "'" + text(value) + "'";
    }

    /**
     * Bounds and escapes a value without quotes.
     *
     * @param value the untrusted text, possibly null
     * @return the bounded, escaped text
     */
    static String text(String value) {
        if (value == null) {
            return "";
        }
        boolean cut = value.codePointCount(0, value.length()) > MAX;
        String head = cut ? value.substring(0, value.offsetByCodePoints(0, MAX)) : value;
        StringBuilder out = new StringBuilder(head.length() + 8);
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (c < 0x20 || c == 0x7F || c == ' ' || c == ' ' || (c >= 0x80 && c < 0xA0)) {
                out.append(String.format("\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return cut ? out.append('…').toString() : out.toString();
    }

    /**
     * Decodes bytes as UTF-8, refusing malformed input instead of replacing it.
     *
     * @param source the bytes
     * @return the text
     * @throws ValidationFailedException when the bytes are not UTF-8
     */
    static String utf8(byte[] source) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ValidationFailedException("Document is not valid UTF-8; DMN files must be UTF-8");
        }
    }
}
