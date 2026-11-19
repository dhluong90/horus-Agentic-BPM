package io.github.dhluong90.bpm.process.internal.json;

import io.github.dhluong90.bpm.process.api.InputInvalidException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Strict UTF-8: invalid bytes are refused, never replaced, so the stored source is exactly what was parsed. */
public final class Utf8 {

    private Utf8() {
    }

    /**
     * @param bytes the bytes
     * @return the text, without a leading byte order mark
     * @throws InputInvalidException when the bytes are not valid UTF-8
     */
    public static String strict(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            throw new InputInvalidException("The document is not valid UTF-8");
        }
    }
}
