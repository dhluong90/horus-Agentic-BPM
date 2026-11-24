package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleSetSummary;
import io.github.dhluong90.bpm.rules.api.VersionService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * AUD-13: a list cursor names the key that signed it, keys come from the host's configuration, and a cursor from
 * before key ids, of a removed key or of a changed key is {@code 400 QUERY_INVALID}, never an internal error. Bank-a's
 * rule-set list is read one item at a time.
 */
class CursorKeyRotationTest {

    private static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    private static final byte[] KEY_2026 = "cursor-key-2026-of-32-bytes-long!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_2027 = "cursor-key-2027-of-32-bytes-long!".getBytes(StandardCharsets.UTF_8);

    private final InMemoryStores stores = new InMemoryStores();
    private final TestCaller caller = new TestCaller("bank-a", "lan");
    private final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(
            Instant.parse("2027-01-04T03:00:00Z"));

    private VersionService replica(String activeId, Map<String, byte[]> keys) {
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores,
                EngineSettings.defaults().withRequireContentHash(false)), stores, stores, stores);
        if (keys != null) {
            engines.cursorKeys().configure(activeId, keys);
        }
        engines.cursorKeys().load();
        return engines.versionService();
    }

    private void seed(VersionService versions) {
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(ImportRequest.yaml(EditorApiTest.cardFee("10000 VND", "card-fee")));
        versions.importRuleSet(ImportRequest.yaml(EditorApiTest.cardFee("10000 VND", "fx-margin")));
    }

    @Test
    void RLE_221_a_cursor_names_its_key_and_a_rotation_keeps_the_cursors_of_the_key_still_listed() {
        // Given two replicas that hold the 2026 key, and a cursor one of them issued
        VersionService before = replica("k2026", Map.of("k2026", KEY_2026));
        seed(before);
        String cursor = before.listRuleSets(new PageRequest(1, null)).nextCursor();
        assertThat(cursor.split("\\.")).as("payload, key id, signature").hasSize(3).contains("k2026");

        // When the host rotates: the 2027 key signs, the 2026 key still verifies
        VersionService rotated = replica("k2027", Map.of("k2026", KEY_2026, "k2027", KEY_2027));

        // Then the old cursor still works, and the next cursor is signed with the new key
        var page = rotated.listRuleSets(new PageRequest(1, cursor));
        assertThat(page.items()).extracting(RuleSetSummary::ruleSet).containsExactly("fx-margin");
        VersionService first = replica("k2027", Map.of("k2026", KEY_2026, "k2027", KEY_2027));
        assertThat(first.listRuleSets(new PageRequest(1, null)).nextCursor().split("\\.")).contains("k2027");

        // And once the 2026 key is removed, its cursor is 400 QUERY_INVALID
        VersionService retired = replica("k2027", Map.of("k2027", KEY_2027));
        assertThatThrownBy(() -> retired.listRuleSets(new PageRequest(1, cursor)))
                .isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void RLE_221_a_cursor_without_a_key_id_or_signed_with_another_key_of_the_same_id_is_query_invalid() {
        // Given a cursor of the format before key ids: payload and signature only
        VersionService versions = replica("k2027", Map.of("k2027", KEY_2027));
        seed(versions);
        String current = versions.listRuleSets(new PageRequest(1, null)).nextCursor();
        String old = current.substring(0, current.indexOf('.')) + current.substring(current.lastIndexOf('.'));

        // Then it is refused as a bad cursor, not as a server error
        assertThatThrownBy(() -> versions.listRuleSets(new PageRequest(1, old)))
                .isInstanceOf(QueryInvalidException.class);

        // And a replica whose key of the same id has other bytes refuses the cursor too
        VersionService other = replica("k2027", Map.of("k2027", KEY_2026));
        assertThatThrownBy(() -> other.listRuleSets(new PageRequest(1, current)))
                .isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void RLE_221_a_key_that_is_too_short_or_not_the_active_one_stops_start_up() {
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores,
                EngineSettings.defaults()), stores, stores, stores);

        assertThatThrownBy(() -> engines.cursorKeys().configure("k1", Map.of("k1", new byte[8])))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> engines.cursorKeys().configure("k2", Map.of("k1", KEY_2026)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("active");
        // and a configured engine never creates a key in the database
        replica("k2027", Map.of("k2027", KEY_2027));
        assertThat(stores.engineKeys).isEmpty();
    }
}
