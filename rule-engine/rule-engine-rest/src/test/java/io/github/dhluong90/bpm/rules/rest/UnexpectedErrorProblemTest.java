package io.github.dhluong90.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** An unexpected failure is a problem body with only a correlation id (RLE-036, QA review F1). */
class UnexpectedErrorProblemTest {

    @Test
    void RLE_036_an_unexpected_failure_is_500_internal_error_without_internals() {
        ResponseEntity<byte[]> response = ProblemDetails.unexpected(new IllegalStateException(
                "java.sql.SQLException: relation rle_version does not exist"), "acct-open-7781");

        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(body).contains("\"code\":\"INTERNAL_ERROR\"", "\"correlationId\":\"acct-open-7781\"",
                "Common-Error-Codes#internal_error");
        assertThat(body).doesNotContain("rle_version", "SQLException", "IllegalStateException", "java.");
    }

    @Test
    void RLE_036_a_client_error_spring_already_classified_keeps_its_4xx_status() {
        ResponseEntity<byte[]> response = ProblemDetails.unexpected(
                new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "text/csv"), null);

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("\"code\":\"INPUT_INVALID\"")
                .doesNotContain("text/csv");
    }

    @Test
    void RLE_039_schema_not_ready_is_503() {
        assertThat(ProblemDetails.STATUS.get("SCHEMA_NOT_READY").value()).isEqualTo(503);
    }
}
