package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.IdempotencyInFlightException;
import io.github.dhluong90.bpm.rules.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.rules.api.RuleSetExistsException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.UnauthenticatedException;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionConflictException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.Hashes;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class VersionServiceTest {

    static final Instant NOW = Instant.parse("2026-12-10T03:00:00Z");

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final VersionService versions = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(NOW, ZoneOffset.UTC), stores, stores, EngineSettings.defaults().withRequireContentHash(false)),
            stores, stores, stores).versionService();

    @Test
    void RLE_020_import_keeps_source_document_and_canonical_hash() {
        // Given
        byte[] file = CardFee.yaml();

        // When
        RuleSetVersion imported = versions.importRuleSet(ImportRequest.yaml(file));
        RuleSetVersion withSource = versions.find("card-fee", 1, true).orElseThrow();
        RuleSetVersion withoutSource = versions.find("card-fee", 1, false).orElseThrow();

        // Then
        assertThat(imported.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(imported.version()).isEqualTo(1);
        assertThat(withSource.sourceFormat()).isEqualTo(SourceFormat.YAML);
        assertThat(withSource.sourceDocument().getBytes(StandardCharsets.UTF_8)).isEqualTo(file);
        assertThat(withSource.sourceDocument()).contains("# Gold customers pay nothing");
        assertThat(withSource.sourceHash()).isEqualTo(Hashes.sha256(file));
        assertThat(withSource.canonicalDocument()).isEqualTo(CardFee.CANONICAL);
        assertThat(withSource.canonicalHash()).isEqualTo(Hashes.sha256(CardFee.CANONICAL));
        assertThat(withoutSource.sourceDocument()).isNull();
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void RLE_009_maker_checker_blocks_self_approval() {
        // Given
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        versions.requestPublish("card-fee", 1, WriteOptions.none());

        // When alice approves her own request
        // Then
        assertThatThrownBy(() -> versions.approve("card-fee", 1, new WriteOptions(null, 1L, null)))
                .isInstanceOf(SelfApprovalForbiddenException.class)
                .extracting(e -> ((SelfApprovalForbiddenException) e).code()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
        assertThat(versions.find("card-fee", 1, false).orElseThrow().status()).isEqualTo(VersionStatus.PENDING_APPROVAL);

        // When bob approves
        caller.become("bank-a", "bob");
        RuleSetVersion published = versions.approve("card-fee", 1, new WriteOptions(null, 1L, "checked fees"));

        // Then
        assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
        assertThat(published.requestedBy()).isEqualTo("alice");
        assertThat(published.approvedBy()).isEqualTo("bob");
        assertThat(published.effectiveFrom()).isEqualTo(NOW);
        assertThat(stores.outbox).extracting(InMemoryStores.OutboxRow::payload)
                .satisfiesExactly(
                        p -> assertThat(p).contains("\"eventType\":\"rules.version.created.v1\"", "\"actor\":\"alice\""),
                        p -> assertThat(p).contains("\"eventType\":\"rules.version.publish-requested.v1\"", "\"actor\":\"alice\""),
                        p -> assertThat(p).contains("\"eventType\":\"rules.version.published.v1\"", "\"actor\":\"bob\"",
                                "\"tenantId\":\"bank-a\"", "\"version\":1"));
    }

    @Test
    void RLE_009_an_idempotency_key_never_turns_a_self_approval_into_an_approval() {
        // Given alice requested v1 and bob approved it with key ap-1
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        versions.importVersion("card-fee", ImportRequest.yaml(CardFee.yaml()));
        versions.requestPublish("card-fee", 1, WriteOptions.none());
        versions.requestPublish("card-fee", 2, WriteOptions.none());
        caller.become("bank-a", "bob");
        versions.approve("card-fee", 1, new WriteOptions("ap-1", 1L, null));

        // When alice approves her own v2 with a fresh key
        caller.become("bank-a", "alice");

        // Then it is refused and no idempotency record remains for the refused attempt
        assertThatThrownBy(() -> versions.approve("card-fee", 2, new WriteOptions("ap-2", 1L, null)))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThat(stores.idempotency).doesNotContainKey("bank-a|approve|ap-2");
        assertThat(versions.find("card-fee", 2, false).orElseThrow().status()).isEqualTo(VersionStatus.PENDING_APPROVAL);
    }

    @Test
    void RLE_014_idempotency_key_replays_identical_and_rejects_mismatch() {
        // Given
        byte[] b1 = CardFee.yaml();
        byte[] b2 = new String(b1, StandardCharsets.UTF_8).replace("50000 VND", "60000 VND").getBytes(StandardCharsets.UTF_8);
        RuleSetVersion first = versions.importRuleSet(ImportRequest.yaml(b1).withIdempotencyKey("k-101"));

        // When
        RuleSetVersion replay = versions.importRuleSet(ImportRequest.yaml(b1).withIdempotencyKey("k-101"));

        // Then
        assertThat(replay).isEqualTo(first);
        assertThat(stores.versions).hasSize(1);
        assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(b2).withIdempotencyKey("k-101")))
                .isInstanceOf(IdempotencyKeyReuseException.class)
                .extracting(e -> ((IdempotencyKeyReuseException) e).code()).isEqualTo("IDEMPOTENCY_KEY_REUSE_MISMATCH");
        assertThat(stores.versions).hasSize(1);
        assertThat(stores.outbox).hasSize(1);

        // And a key held by a running request is in flight
        stores.heldKeys.add("bank-a|import-rule-set|k-102");
        assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(b1).withIdempotencyKey("k-102")))
                .isInstanceOf(IdempotencyInFlightException.class)
                .extracting(e -> ((IdempotencyInFlightException) e).code()).isEqualTo("IDEMPOTENCY_IN_FLIGHT");
    }

    @Test
    void importing_an_existing_rule_set_is_a_conflict_and_a_new_version_is_numbered_next() {
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));

        assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(CardFee.yaml())))
                .isInstanceOf(RuleSetExistsException.class);
        assertThat(versions.importVersion("card-fee", ImportRequest.yaml(CardFee.yaml())).version()).isEqualTo(2);
    }

    @Test
    void lifecycle_commands_check_status_and_lock_version() {
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));

        assertThatThrownBy(() -> versions.approve("card-fee", 1, new WriteOptions(null, 1L, null)))
                .isInstanceOf(InvalidStateException.class);
        assertThatThrownBy(() -> versions.requestPublish("card-fee", 1, new WriteOptions(null, 7L, null)))
                .isInstanceOf(VersionConflictException.class);
        assertThat(versions.requestPublish("card-fee", 1, new WriteOptions(null, 0L, null)).lockVersion()).isEqualTo(1);
        assertThatThrownBy(() -> versions.requestPublish("card-fee", 1, WriteOptions.none()))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void a_document_with_an_unparsable_cell_is_refused_at_import() {
        byte[] bad = new String(CardFee.yaml(), StandardCharsets.UTF_8).replace("\"== 'GOLD'\"", "\"== 'GOLD\"")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(bad)))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("Rule R1");
        assertThat(stores.versions).isEmpty();
    }

    @Test
    void every_command_needs_a_tenant_and_a_caller() {
        caller.become(null, "alice");

        assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(CardFee.yaml())))
                .isInstanceOf(UnauthenticatedException.class);
    }
}
