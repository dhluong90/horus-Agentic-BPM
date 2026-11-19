package io.github.dhluong90.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.PayloadTooLargeException;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** Import bodies are read up to the limit and no further (security review of the DMN import, finding M1). */
class BoundedBodyTest {

    static final int LIMIT = RuleSetReader.MAX_SOURCE_BYTES;

    @Test
    void TR_STD_25_an_endless_body_is_refused_after_reading_at_most_one_byte_past_the_limit() {
        // Given a body with no end and no Content-Length, as a 2 GB upload in chunked encoding
        CountingEndless body = new CountingEndless();

        // Then
        assertThatThrownBy(() -> RestSupport.boundedBody(body, null))
                .isInstanceOf(PayloadTooLargeException.class)
                .satisfies(e -> assertThat(((PayloadTooLargeException) e).code()).isEqualTo("PAYLOAD_TOO_LARGE"));
        assertThat(body.read).isEqualTo(LIMIT + 1L);
    }

    @Test
    void TR_STD_25_a_declared_length_over_the_limit_is_refused_before_reading() {
        CountingEndless body = new CountingEndless();

        assertThatThrownBy(() -> RestSupport.boundedBody(body, 2_147_483_648L)).isInstanceOf(PayloadTooLargeException.class);
        assertThat(body.read).isZero();
    }

    @Test
    void TR_STD_25_a_body_of_exactly_the_limit_is_accepted() throws Exception {
        assertThat(RestSupport.boundedBody(new ByteArrayInputStream(new byte[LIMIT]), (long) LIMIT)).hasSize(LIMIT);
        assertThatThrownBy(() -> RestSupport.boundedBody(new ByteArrayInputStream(new byte[LIMIT + 1]), null))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void the_problem_status_of_payload_too_large_is_413() {
        assertThat(ProblemDetails.STATUS.get("PAYLOAD_TOO_LARGE").value()).isEqualTo(413);
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
            java.util.Arrays.fill(b, off, off + len, (byte) 'a');
            read += len;
            return len;
        }
    }
}
