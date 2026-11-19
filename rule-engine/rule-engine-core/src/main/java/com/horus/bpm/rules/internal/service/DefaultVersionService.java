package com.horus.bpm.rules.internal.service;

import com.horus.bpm.rules.api.ImportRequest;
import com.horus.bpm.rules.api.InputInvalidException;
import com.horus.bpm.rules.api.InvalidStateException;
import com.horus.bpm.rules.api.PayloadTooLargeException;
import com.horus.bpm.rules.api.LifecycleAction;
import com.horus.bpm.rules.api.Page;
import com.horus.bpm.rules.api.PageRequest;
import com.horus.bpm.rules.api.QueryInvalidException;
import com.horus.bpm.rules.api.RuleSetSummary;
import com.horus.bpm.rules.api.VersionHistoryEntry;
import com.horus.bpm.rules.api.VersionSummary;
import com.horus.bpm.rules.api.RuleSetExistsException;
import com.horus.bpm.rules.api.RuleSetNotFoundException;
import com.horus.bpm.rules.api.RuleSetVersion;
import com.horus.bpm.rules.api.SelfApprovalForbiddenException;
import com.horus.bpm.rules.api.SourceFormat;
import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.api.VersionConflictException;
import com.horus.bpm.rules.api.VersionNotFoundException;
import com.horus.bpm.rules.api.VersionService;
import com.horus.bpm.rules.api.VersionStatus;
import com.horus.bpm.rules.api.WriteOptions;
import com.horus.bpm.rules.internal.eval.CompiledRuleSet;
import com.horus.bpm.rules.internal.json.EngineJson;
import com.horus.bpm.rules.internal.json.EngineValues;
import com.horus.bpm.rules.internal.json.Hashes;
import com.horus.bpm.rules.internal.json.Ulid;
import com.horus.bpm.rules.internal.read.ParsedRuleSet;
import com.horus.bpm.rules.internal.read.RuleSetReader;
import com.horus.bpm.rules.internal.store.EventOutbox;
import com.horus.bpm.rules.internal.store.HistoryRow;
import com.horus.bpm.rules.internal.store.RuleStore;
import com.horus.bpm.rules.internal.store.StoredVersion;
import io.github.dhluong90.bpm.rules.internal.dmn.ruleset.DmnWriter;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Imports versions and runs the publication lifecycle with maker-checker. Thread-safe and stateless.
 */
public final class DefaultVersionService implements VersionService {

    static final String PERMISSION_AUTHOR = "rules:author";
    static final String PERMISSION_APPROVE = "rules:approve";
    static final String PERMISSION_READ = "rules:read";

    static final String EVENT_CREATED = "rules.version.created.v1";
    static final String EVENT_PUBLISH_REQUESTED = "rules.version.publish-requested.v1";
    static final String EVENT_PUBLISHED = "rules.version.published.v1";
    static final String EVENT_REJECTED = "rules.version.rejected.v1";

    private static final String CURSOR_RULE_SETS = "rule-sets";
    private static final String CURSOR_VERSIONS = "versions";

    private final EngineContext context;
    private final RuleStore store;
    private final EventOutbox outbox;
    /** The cursor key in memory, loaded at start-up (RLE-039). */
    private final CursorKeys cursorKeys;

    /**
     * Creates the service.
     *
     * @param context shared engine context
     * @param store rule set storage
     * @param outbox event outbox
     * @param cursorKeys the cursor key, loaded at start-up; the only constructor, so a hand-wired engine always
     *     shares the holder that {@link RuleEngines#cursorKeys()} exposes for loading
     */
    DefaultVersionService(EngineContext context, RuleStore store, EventOutbox outbox, CursorKeys cursorKeys) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.cursorKeys = Objects.requireNonNull(cursorKeys, "cursorKeys must not be null");
    }

    @Override
    public RuleSetVersion importRuleSet(ImportRequest request) {
        return importDocument(null, request);
    }

    @Override
    public RuleSetVersion importVersion(String ruleSet, ImportRequest request) {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        return importDocument(ruleSet, request);
    }

    private RuleSetVersion importDocument(String targetRuleSet, ImportRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.size() > RuleSetReader.MAX_SOURCE_BYTES) {
            throw new PayloadTooLargeException(RuleSetReader.MAX_SOURCE_BYTES);
        }
        String tenant = context.tenant();
        String actor = context.callerWith(PERMISSION_AUTHOR);
        byte[] source = request.source();
        String operation = targetRuleSet == null ? "import-rule-set" : "import-version";
        String requestHash = Hashes.sha256Parts(operation, actor, targetRuleSet, request.format().name(), Hashes.sha256(source));
        return context.idempotent(tenant, operation, request.idempotencyKey(), requestHash, 201,
                () -> doImport(tenant, actor, targetRuleSet, request.format(), source),
                version -> EngineValues.write(EngineValues.version(version)),
                EngineValues::versionFromJson);
    }

    private RuleSetVersion doImport(String tenant, String actor, String targetRuleSet, SourceFormat format, byte[] source) {
        if (format != SourceFormat.YAML && format != SourceFormat.DMN_XML) {
            throw new InputInvalidException("Only YAML and DMN XML import are available in this release");
        }
        String sourceText = utf8(source);
        ParsedRuleSet parsed = RuleSetReader.read(format, source);
        // The import model equals the parsed canonical form (checked by the reader, ADR-0026 decision 4); it is compiled
        // here so violations follow the order cells are written in (RLE-041). Evaluation compiles only from the stored form.
        int rows = parsed.model().decision().table().rules().size();
        if (rows > context.settings().maxRows()) {
            throw new ValidationFailedException("The rule set has " + rows + " rules; evaluation.max-rows allows "
                    + context.settings().maxRows());
        }
        CompiledRuleSet.compile(parsed.model(), context.settings().maxOutputBytes(), context.settings().maxStringLength());
        String name = parsed.model().name();
        Instant now = context.clock().instant();
        String ruleSetId;
        if (targetRuleSet == null) {
            if (store.findRuleSetId(tenant, name).isPresent()) {
                throw new RuleSetExistsException(name);
            }
            ruleSetId = Ulid.next(now);
            store.createRuleSet(tenant, ruleSetId, name, now);
        } else {
            if (!targetRuleSet.equals(name)) {
                throw new InputInvalidException("Document names rule set '" + name + "', not '" + targetRuleSet + "'");
            }
            ruleSetId = store.findRuleSetId(tenant, name).orElseThrow(() -> new RuleSetNotFoundException(name));
        }
        int number = store.lockAndNextVersionNumber(tenant, ruleSetId);
        StoredVersion version = new StoredVersion(tenant, Ulid.next(now), ruleSetId, name, number, VersionStatus.DRAFT,
                parsed.model().decision().table().hitPolicy().name(), parsed.model().effectiveFrom(),
                parsed.canonicalJson(), parsed.canonicalHash(), format, sourceText, Hashes.sha256(source), 0,
                null, null, null, null, null, actor, now);
        store.insertVersion(version);
        history(version, LifecycleAction.CREATED, null, actor, null, now);
        event(tenant, EVENT_CREATED, version, actor, now);
        return version.toView(false);
    }

    @Override
    public RuleSetVersion requestPublish(String ruleSet, int version, WriteOptions options) {
        WriteOptions opts = options == null ? WriteOptions.none() : options;
        String tenant = context.tenant();
        String actor = context.callerWith(PERMISSION_AUTHOR);
        String requestHash = Hashes.sha256Parts("request-publish", actor, ruleSet, Integer.toString(version),
                String.valueOf(opts.expectedLockVersion()));
        return context.idempotent(tenant, "request-publish", opts.idempotencyKey(), requestHash, 200, () -> {
            StoredVersion current = load(tenant, ruleSet, version);
            if (current.status() != VersionStatus.DRAFT) {
                throw new InvalidStateException(ruleSet, version, current.status(), "request-publish");
            }
            checkLock(current, opts);
            Instant now = context.clock().instant();
            StoredVersion updated = current.requested(actor, now);
            store.updateVersion(updated, current.lockVersion());
            history(updated, LifecycleAction.REQUESTED, current.status(), actor, null, now);
            event(tenant, EVENT_PUBLISH_REQUESTED, updated, actor, now);
            return updated.toView(false);
        }, v -> EngineValues.write(EngineValues.version(v)), EngineValues::versionFromJson);
    }

    @Override
    public RuleSetVersion approve(String ruleSet, int version, WriteOptions options) {
        WriteOptions opts = options == null ? WriteOptions.none() : options;
        String tenant = context.tenant();
        String actor = context.callerWith(PERMISSION_APPROVE);
        requireCommentLength(opts.comment());
        String requestHash = Hashes.sha256Parts("approve", actor, ruleSet, Integer.toString(version),
                String.valueOf(opts.expectedLockVersion()), opts.comment());
        return context.idempotent(tenant, "approve", opts.idempotencyKey(), requestHash, 200, () -> {
            StoredVersion current = load(tenant, ruleSet, version);
            if (current.status() != VersionStatus.PENDING_APPROVAL) {
                throw new InvalidStateException(ruleSet, version, current.status(), "approve");
            }
            // Four eyes: neither the person who requested publication nor the author of the content may approve it.
            if (actor.equals(current.requestedBy()) || actor.equals(current.createdBy())) {
                throw new SelfApprovalForbiddenException(ruleSet, version);
            }
            checkLock(current, opts);
            Instant now = context.clock().instant();
            StoredVersion updated = current.approved(actor, now, opts.comment());
            store.updateVersion(updated, current.lockVersion());
            history(updated, LifecycleAction.APPROVED, current.status(), actor, opts.comment(), now);
            event(tenant, EVENT_PUBLISHED, updated, actor, now);
            return updated.toView(false);
        }, v -> EngineValues.write(EngineValues.version(v)), EngineValues::versionFromJson);
    }

    @Override
    public RuleSetVersion reject(String ruleSet, int version, WriteOptions options) {
        WriteOptions opts = options == null ? WriteOptions.none() : options;
        String tenant = context.tenant();
        String actor = context.callerWith(PERMISSION_APPROVE);
        String reason = requireReason(opts.comment());
        String requestHash = Hashes.sha256Parts("reject", actor, ruleSet, Integer.toString(version),
                String.valueOf(opts.expectedLockVersion()), reason);
        return context.idempotent(tenant, "reject", opts.idempotencyKey(), requestHash, 200, () -> {
            StoredVersion current = load(tenant, ruleSet, version);
            if (current.status() != VersionStatus.PENDING_APPROVAL) {
                throw new InvalidStateException(ruleSet, version, current.status(), "reject");
            }
            // PD-089: the requester takes a request back with :withdraw, so the audit trail keeps "the author took
            // it back" apart from "an approver refused it" (DESIGN section 3.3).
            if (actor.equals(current.requestedBy())) {
                throw SelfApprovalForbiddenException.selfReject(ruleSet, version);
            }
            checkLock(current, opts);
            Instant now = context.clock().instant();
            StoredVersion updated = current.rejected();
            store.updateVersion(updated, current.lockVersion());
            history(updated, LifecycleAction.REJECTED, current.status(), actor, reason, now);
            event(tenant, EVENT_REJECTED, updated, actor, now, payload -> payload
                    .put("rejectedBy", actor)
                    .put("reason", reason));
            return updated.toView(false);
        }, v -> EngineValues.write(EngineValues.version(v)), EngineValues::versionFromJson);
    }

    @Override
    public Page<RuleSetSummary> listRuleSets(PageRequest page) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_READ);
        PageRequest request = page == null ? PageRequest.first() : page;
        int limit = limit(request);
        // Verified in memory before any transaction: a forged cursor never reaches the database (RLE-039).
        String after = Cursors.ruleSetName(cursorKeys.verify(tenant, "", CURSOR_RULE_SETS, request.cursor()));
        return context.withoutDooming(() -> {
            List<RuleSetSummary> rows = store.listRuleSets(tenant, after, limit + 1, context.clock().instant());
            return page(rows, limit, last -> cursorKeys.sign(tenant, "", CURSOR_RULE_SETS, last.ruleSet()));
        });
    }

    @Override
    public Optional<RuleSetSummary> findRuleSet(String ruleSet) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_READ);
        if (!RuleSetReader.isRuleSetName(ruleSet)) {
            return Optional.empty();
        }
        return context.withoutDooming(() -> store.findRuleSetSummary(tenant, ruleSet, context.clock().instant()));
    }

    @Override
    public Page<VersionSummary> listVersions(String ruleSet, PageRequest page) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_READ);
        PageRequest request = page == null ? PageRequest.first() : page;
        int limit = limit(request);
        requireRuleSetName(ruleSet);
        String before = cursorKeys.verify(tenant, ruleSet, CURSOR_VERSIONS, request.cursor());
        Integer beforeVersion = before == null ? null : Cursors.positiveInt(before);
        return context.withoutDooming(() -> {
            String ruleSetId = store.findRuleSetId(tenant, ruleSet).orElseThrow(() -> new RuleSetNotFoundException(ruleSet));
            List<VersionSummary> rows = store.listVersions(tenant, ruleSetId, beforeVersion, limit + 1);
            return page(rows, limit, last -> cursorKeys.sign(tenant, ruleSet, CURSOR_VERSIONS,
                    Integer.toString(last.version())));
        });
    }

    @Override
    public List<VersionHistoryEntry> history(String ruleSet, int version) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_READ);
        requireRuleSetName(ruleSet);
        return context.withoutDooming(() -> {
            StoredVersion stored = store.findVersion(tenant, ruleSet, version, false).orElseThrow(() ->
                    store.findRuleSetId(tenant, ruleSet).isPresent()
                            ? new VersionNotFoundException(ruleSet, version)
                            : new RuleSetNotFoundException(ruleSet));
            return store.history(tenant, stored.id()).stream().map(HistoryRow::toEntry).toList();
        });
    }

    private int limit(PageRequest request) {
        int max = context.settings().queryMaxPageSize();
        if (request.limit() == null) {
            return Math.min(EngineDefaults.QUERY_DEFAULT_PAGE_SIZE, max);
        }
        if (request.limit() < 1 || request.limit() > max) {
            throw new QueryInvalidException("limit must be 1 to " + max + ", was " + request.limit());
        }
        return request.limit();
    }

    private static <T> Page<T> page(List<T> rows, int limit, java.util.function.Function<T, String> cursorOf) {
        if (rows.size() <= limit) {
            return new Page<>(rows, null);
        }
        List<T> items = rows.subList(0, limit);
        return new Page<>(items, cursorOf.apply(items.getLast()));
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InputInvalidException("reason is required and must not be blank");
        }
        int max = context.settings().reasonMaxLength();
        if (reason.length() > max) {
            throw new InputInvalidException("reason must be at most " + max + " characters, was " + reason.length());
        }
        return reason;
    }

    /** The approval comment is optional, but lands in the immutable history, so it has the reason's bound. */
    private void requireCommentLength(String comment) {
        int max = context.settings().reasonMaxLength();
        if (comment != null && comment.length() > max) {
            throw new InputInvalidException("comment must be at most " + max + " characters, was " + comment.length());
        }
    }

    private void history(StoredVersion version, LifecycleAction action, VersionStatus from, String actor,
            String reason, Instant at) {
        store.appendHistory(new HistoryRow(version.tenantId(), Ulid.next(at), version.id(), action, from,
                version.status(), actor, reason, version.lockVersion(), at));
    }

    @Override
    public Optional<RuleSetVersion> find(String ruleSet, int version, boolean includeSource) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_READ);
        if (!RuleSetReader.isRuleSetName(ruleSet)) {
            return Optional.empty();
        }
        return context.withoutDooming(() ->
                store.findVersion(tenant, ruleSet, version, false).map(v -> v.toView(includeSource)));
    }

    @Override
    public String export(String ruleSet, int version, SourceFormat format) {
        Objects.requireNonNull(format, "format must not be null");
        String tenant = context.tenant();
        context.callerWith(PERMISSION_READ);
        requireRuleSetName(ruleSet);
        StoredVersion stored = context.withoutDooming(() -> store.findVersion(tenant, ruleSet, version, false)
                .orElseThrow(() -> store.findRuleSetId(tenant, ruleSet).isPresent()
                        ? new VersionNotFoundException(ruleSet, version)
                        : new RuleSetNotFoundException(ruleSet)));
        return switch (format) {
            case JSON -> stored.canonicalJson();
            case DMN_XML -> DmnWriter.write(stored.canonicalJson());
            case YAML -> throw new InputInvalidException("YAML export is not available in this release; use json or dmn");
        };
    }

    /**
     * A name that cannot be a rule set name (a NUL byte, upper case, over 100 characters) is not found, before it
     * reaches the database, which would refuse a NUL byte with a driver error (QA review F1).
     */
    private static void requireRuleSetName(String ruleSet) {
        if (!RuleSetReader.isRuleSetName(ruleSet)) {
            throw new RuleSetNotFoundException("(invalid name)");
        }
    }

    private StoredVersion load(String tenant, String ruleSet, int version) {
        requireRuleSetName(ruleSet);
        return store.findVersion(tenant, ruleSet, version, true).orElseThrow(() ->
                store.findRuleSetId(tenant, ruleSet).isPresent()
                        ? new VersionNotFoundException(ruleSet, version)
                        : new RuleSetNotFoundException(ruleSet));
    }

    private static void checkLock(StoredVersion current, WriteOptions options) {
        if (options.expectedLockVersion() != null && options.expectedLockVersion() != current.lockVersion()) {
            throw new VersionConflictException("Expected lock version " + options.expectedLockVersion()
                    + " but " + current.ruleSet() + " v" + current.version() + " is at " + current.lockVersion());
        }
    }

    private void event(String tenant, String type, StoredVersion version, String actor, Instant at) {
        event(tenant, type, version, actor, at, payload -> { });
    }

    private void event(String tenant, String type, StoredVersion version, String actor, Instant at,
            java.util.function.Consumer<ObjectNode> extra) {
        ObjectNode payload = EngineJson.NODES.objectNode()
                .put("eventType", type)
                .put("ruleSet", version.ruleSet())
                .put("version", version.version())
                .put("tenantId", tenant)
                .put("actor", actor)
                .put("occurredAt", at.toString());
        extra.accept(payload);
        outbox.append(tenant, Ulid.next(at), EngineValues.write(payload), at);
    }

    private static String utf8(byte[] source) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(source))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ValidationFailedException("Document is not valid UTF-8");
        }
    }
}
