package io.github.dhluong90.bpm.process.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * AUD-13 for the Process Engine: a list cursor names the key that signed it; a rotation adds a key and makes it
 * active; a cursor from before key ids, of a removed key or of a key whose bytes changed is {@code 400 QUERY_INVALID},
 * never an internal error. Bank-a's instance list is the example.
 */
class CursorKeyRotationTest {

    private static final byte[] KEY_2026 = "cursor-key-2026-of-32-bytes-long!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_2027 = "cursor-key-2027-of-32-bytes-long!".getBytes(StandardCharsets.UTF_8);
    private static final String LAST = "2027-01-04T03:00:00Z|01KF2Q8Z0RQ6V2Y8M1C4N5P7T9";

    @Test
    void PRE_371_a_cursor_names_its_key_and_a_rotation_keeps_the_cursors_of_the_key_still_listed() {
        // Given a cursor signed with the 2026 key
        Cursors before = new Cursors("k2026", Map.of("k2026", KEY_2026));
        String cursor = before.sign("bank-a", "RUNNING", "instances", LAST);
        assertThat(cursor.split("\\.")).as("payload, key id, signature").hasSize(3).contains("k2026");

        // When the host adds the 2027 key and makes it active
        Cursors rotated = new Cursors("k2027", Map.of("k2026", KEY_2026, "k2027", KEY_2027));

        // Then the old cursor still verifies and new ones carry the new key id
        assertThat(rotated.verify("bank-a", "RUNNING", "instances", cursor)).isEqualTo(LAST);
        assertThat(rotated.sign("bank-a", "RUNNING", "instances", LAST).split("\\.")).contains("k2027");

        // And after the 2026 key is removed its cursor is 400 QUERY_INVALID, as is one for another tenant
        Cursors retired = new Cursors("k2027", Map.of("k2027", KEY_2027));
        assertThatThrownBy(() -> retired.verify("bank-a", "RUNNING", "instances", cursor))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> rotated.verify("bank-b", "RUNNING", "instances", cursor))
                .isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void PRE_371_a_cursor_without_a_key_id_or_of_a_changed_key_is_query_invalid() {
        Cursors current = new Cursors("k2027", Map.of("k2027", KEY_2027));
        String cursor = current.sign("bank-a", "RUNNING", "instances", LAST);
        // the format before key ids: payload and signature only
        String old = cursor.substring(0, cursor.indexOf('.')) + cursor.substring(cursor.lastIndexOf('.'));

        assertThatThrownBy(() -> current.verify("bank-a", "RUNNING", "instances", old))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> new Cursors("k2027", Map.of("k2027", KEY_2026))
                .verify("bank-a", "RUNNING", "instances", cursor)).isInstanceOf(QueryInvalidException.class);
        // a key held in the database carries an id derived from its bytes, so replacing it retires its cursors
        Cursors held = new Cursors(KEY_2026);
        String fromDb = held.sign("bank-a", "RUNNING", "instances", LAST);
        assertThat(fromDb.split("\\.")[1]).startsWith("db-");
        assertThatThrownBy(() -> new Cursors(KEY_2027).verify("bank-a", "RUNNING", "instances", fromDb))
                .isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void PRE_371_a_key_that_is_too_short_or_a_missing_active_key_is_refused() {
        assertThatThrownBy(() -> new Cursors("k1", Map.of("k1", new byte[8])))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new Cursors("k2", Map.of("k1", KEY_2026)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("active");
        assertThatThrownBy(() -> new Cursors("bad id", Map.of("bad id", KEY_2026)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("key id");
    }
}
