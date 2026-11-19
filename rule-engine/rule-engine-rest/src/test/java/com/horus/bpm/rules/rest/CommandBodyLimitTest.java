package com.horus.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.rules.api.PayloadTooLargeException;
import com.horus.bpm.rules.internal.service.EngineDefaults;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** Command and evaluation bodies are read up to their limit and no further (RLE-044). */
class CommandBodyLimitTest {

    static final int COMMAND = EngineDefaults.COMMAND_BODY_MAX_BYTES;
    static final int EVALUATE = EngineDefaults.EVALUATE_BODY_MAX_BYTES;

    @Test
    void RLE_044_an_endless_command_body_is_refused_after_one_byte_past_the_limit() {
        // Given a :reject body with no Content-Length that never ends
        CountingEndless body = new CountingEndless();

        // Then
        assertThatThrownBy(() -> RestSupport.boundedBody(body, null, COMMAND))
                .isInstanceOfSatisfying(PayloadTooLargeException.class,
                        e -> assertThat(e.code()).isEqualTo("PAYLOAD_TOO_LARGE"));
        assertThat(body.read).isEqualTo(COMMAND + 1L);
    }

    @Test
    void RLE_044_a_declared_length_over_the_limit_is_refused_before_reading() {
        CountingEndless body = new CountingEndless();

        assertThatThrownBy(() -> RestSupport.boundedBody(body, COMMAND + 1L, COMMAND))
                .isInstanceOf(PayloadTooLargeException.class);
        assertThatThrownBy(() -> RestSupport.boundedBody(body, EVALUATE + 1L, EVALUATE))
                .isInstanceOf(PayloadTooLargeException.class);
        assertThat(body.read).isZero();
    }

    @Test
    void RLE_044_a_body_of_exactly_the_limit_is_accepted_and_one_byte_more_is_not() throws Exception {
        assertThat(RestSupport.boundedBody(new ByteArrayInputStream(new byte[COMMAND]), (long) COMMAND, COMMAND))
                .hasSize(COMMAND);
        assertThatThrownBy(() -> RestSupport.boundedBody(new ByteArrayInputStream(new byte[COMMAND + 1]), null, COMMAND))
                .isInstanceOf(PayloadTooLargeException.class);
        assertThat(RestSupport.boundedBody(new ByteArrayInputStream(new byte[0]), 0L, COMMAND)).isEmpty();
    }

    @Test
    void RLE_044_payload_too_large_is_413_and_the_command_limit_fits_the_longest_reason() {
        assertThat(ProblemDetails.STATUS.get("PAYLOAD_TOO_LARGE").value()).isEqualTo(413);
        // A reason of the longest length, every character escaped as backslash-u plus four hex digits
        int longestReasonBody = "{\"reason\":\"\"}".length() + EngineDefaults.REASON_MAX_LENGTH * 6;
        assertThat(longestReasonBody).isLessThanOrEqualTo(COMMAND);
    }

    /** A stream that never ends and counts the bytes taken from it. */
    static final class CountingEndless extends InputStream {
        long read;

        @Override
        public int read() {
            read++;
            return 'a';
        }

        @Override
        public int read(byte[] b, int off, int len) {
            read += len;
            java.util.Arrays.fill(b, off, off + len, (byte) 'a');
            return len;
        }
    }
}
