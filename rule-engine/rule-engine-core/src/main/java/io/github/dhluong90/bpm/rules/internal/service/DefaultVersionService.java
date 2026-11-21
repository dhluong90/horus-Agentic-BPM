package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.AuthorUnknownException;
import io.github.dhluong90.bpm.rules.api.DraftAuthoringDisabledException;
import io.github.dhluong90.bpm.rules.api.DraftValidation;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.PendingApproval;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.PreconditionRequiredException;
import io.github.dhluong90.bpm.rules.api.VersionDiff;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.rules.api.LastVersionInEffectException;
import io.github.dhluong90.bpm.rules.api.PayloadTooLargeException;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleSetSummary;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionSummary;
import io.github.dhluong90.bpm.rules.api.RuleSetExistsException;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RetirementRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.ContentChangedException;
import io.github.dhluong90.bpm.rules.api.ContentHashRequiredException;
import io.github.dhluong90.bpm.rules.api.VersionConflictException;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionActions;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.api.AcknowledgedWarning;
import io.github.dhluong90.bpm.rules.api.VersionWarning;
import io.github.dhluong90.bpm.rules.api.WarningsNotAcknowledgedException;
import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.json.Hashes;
import io.github.dhluong90.bpm.rules.internal.json.Ulid;
import io.github.dhluong90.bpm.rules.internal.read.ParsedRuleSet;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import io.github.dhluong90.bpm.rules.internal.store.EventOutbox;
import io.github.dhluong90.bpm.rules.internal.store.HistoryRow;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;
import io.github.dhluong90.bpm.rules.internal.store.StoredVersion;
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
    static final String EVENT_RETIRED = Supersession.EVENT_RETIRED;
    static final String EVENT_DRAFT_UPDATED = "rules.version.draft-updated.v1";
    static final String EVENT_WITHDRAWN = "rules.version.withdrawn.v1";
    static final String EVENT_DISCARDED = "rules.version.discarded.v1";
    static final String EVENT_RETIRE_REQUESTED = "rules.version.retire-requested.v1";

    private static final String CURSOR_RULE_SETS = "rule-sets";
    private static final String CURSOR_VERSIONS = "versions";
    private static final String CURSOR_PENDING = "pending-approvals";

    private final EngineContext context;
    private final RuleStore store;
    private final EventOutbox outbox;
    /** The cursor key in memory, loaded at start-up (RLE-039). */
    private final CursorKeys cursorKeys;
    /** This replica's compiled versions; an approval evicts its rule set after commit (ADR-0009). */
    private final CompiledCache cache;
    private final EngineMeters meters;
    private final Supersession supersession;
    /** Words problems and readings; the same catalogue as the explanations (PD-077). */
    private final CatalogueExplainer catalogue;

    /**
     * Creates the service.
     *
     * @param context shared engine context
     * @param store rule set storage
     * @param outbox event outbox
     * @param cursorKeys the cursor key, loaded at start-up; the only constructor, so a hand-wired engine always
     *     shares the holder that {@link RuleEngines#cursorKeys()} exposes for loading
     * @param cache the compiled cache the evaluation of the same replica uses
     * @param supersession retires superseded versions, shared with the lifecycle sweep
     * @param catalogue the message catalogue that words problems and readings
     */
    DefaultVersionService(EngineContext context, RuleStore store, EventOutbox outbox, CursorKeys cursorKeys,
            Supersession supersession, CompiledCache cache, EngineMeters meters, CatalogueExplainer catalogue) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.cursorKeys = Objects.requireNonNull(cursorKeys, "cursorKeys must not be null");
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.supersession = Objects.requireNonNull(supersession, "supersession must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue must not be null");
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
        String correlationId = EventEnvelope.requireValidCorrelationId(request.correlationId());
        byte[] source = request.source();
        String operation = targetRuleSet == null ? "import-rule-set" : "import-version";
        String requestHash = Hashes.sha256Parts(operation, actor, targetRuleSet, request.format().name(), Hashes.sha256(source));
        return context.idempotent(tenant, operation, request.idempotencyKey(), requestHash, 201,
                () -> doImport(tenant, actor, targetRuleSet, request.format(), source, correlationId),
                version -> EngineValues.write(EngineValues.version(version)),
                EngineValues::versionFromJson);
    }

    /**
     * Reads, checks and compiles a source document exactly as import does; used by import and by draft replacement,
     * so both give the same violations (RLE-033, RLE-041, RLE-047).
     */
    private ParsedRuleSet checkedDocument(SourceFormat format, byte[] source) {
        if (format != SourceFormat.YAML && format != SourceFormat.DMN_XML) {
            throw new InputInvalidException("Only YAML and DMN XML import are available in this release");
        }
        ParsedRuleSet parsed = RuleSetReader.read(format, source);
        // The import model equals the parsed canonical form (checked by the reader, ADR-0026 decision 4); it is compiled
        // here so violations follow the order cells are written in (RLE-041). Evaluation compiles only from the stored form.
        checkedModel(parsed.model());
        return parsed;
    }

    /** The row limit and the publication checks of a model, shared by import, draft replacement and restore. */
    private void checkedModel(io.github.dhluong90.bpm.rules.internal.model.Definitions model) {
        int rows = model.decision().table().rules().size();
        if (rows > context.settings().maxRows()) {
            throw new ValidationFailedException("The rule set has " + rows + " rules; evaluation.max-rows allows "
                    + context.settings().maxRows());
        }
        compileForPublication(model);
    }

    @Override
    public RuleSetVersion copyVersion(String ruleSet, int version, WriteOptions options) {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        WriteOptions opts = options == null ? WriteOptions.none() : options;
        String tenant = context.tenant();
        String actor = context.callerWith(PERMISSION_AUTHOR);
        String correlationId = EventEnvelope.requireValidCorrelationId(opts.correlationId());
        if (version < 1) {
            throw new InputInvalidException("copyOf must be a version number, 1 or more, was " + version);
        }
        requireRuleSetName(ruleSet);
        String requestHash = Hashes.sha256Parts("copy-version", actor, ruleSet, Integer.toString(version));
        return context.idempotent(tenant, "copy-version", opts.idempotencyKey(), requestHash, 201,
                () -> doCopy(tenant, actor, ruleSet, version, correlationId),
                v -> EngineValues.write(EngineValues.version(v)), EngineValues::versionFromJson);
    }

    private RuleSetVersion doCopy(String tenant, String actor, String ruleSet, int from, String correlationId) {
        // The source is read in the caller's tenant only: another tenant's rule set or version is not found (TR-TEN-01).
        StoredVersion source = existing(tenant, ruleSet, from);
        // The copy is checked as an import of the same document would be today (RLE-063), not as it was when approved.
        checkedModel(RuleSetReader.readCanonical(source.canonicalJson()));
        requireMaskDeclarations(tenant, source.canonicalJson());
        Instant now = context.clock().instant();
        int number = store.lockAndNextVersionNumber(tenant, source.ruleSetId());
        // Nothing of the approval is copied: no approver, no effective date, no acknowledgement. The content, its
        // canonical hash and the source it was imported from are the earlier version's, byte for byte.
        StoredVersion copy = new StoredVersion(tenant, Ulid.next(now), source.ruleSetId(), ruleSet, number,
                VersionStatus.DRAFT, source.hitPolicy(), null, source.canonicalJson(), source.canonicalHash(),
                source.sourceFormat(), source.sourceDocument(), source.sourceHash(), 0, null, null, null, null, null,
                actor, now, null, from);
        store.insertVersion(copy);
        store.appendHistory(new HistoryRow(tenant, Ulid.next(now), copy.id(), LifecycleAction.CREATED, null,
                VersionStatus.DRAFT, actor, null, copy.lockVersion(), now, null, null, null, null, null, List.of(),
                List.of(), from));
        event(tenant, EVENT_CREATED, copy, actor, now, correlationId, payload -> payload.put("copiedFrom", from));
        return view(copy, false);
    }

    private RuleSetVersion doImport(String tenant, String actor, String targetRuleSet, SourceFormat format, byte[] source,
            String correlationId) {
        if (format != SourceFormat.YAML && format != SourceFormat.DMN_XML) {
            throw new InputInvalidException("Only YAML and DMN XML import are available in this release");
        }
        String sourceText = utf8(source);
        ParsedRuleSet parsed = checkedDocument(format, source);
        requireMaskDeclarations(tenant, parsed.canonicalJson());
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
        event(tenant, EVENT_CREATED, version, actor, now, correlationId, payload -> { });
        return view(version, false);
    }

    @Override
    public RuleSetVersion replaceDraft(String ruleSet, int version, ImportRequest request, WriteOptions options) {
        Objects.requireNonNull(request, "request must not be null");
        WriteOptions opts = options == null ? WriteOptions.none() : options;
        if (request.size() > RuleSetReader.MAX_SOURCE_BYTES) {
            throw new PayloadTooLargeException(RuleSetReader.MAX_SOURCE_BYTES);
        }
        String correlationId = EventEnvelope.requireValidCorrelationId(
                opts.correlationId() != null ? opts.correlationId() : request.correlationId());
        String tenant = context.tenant();
        String actor = context.callerWith(PERMISSION_AUTHOR);
        if (!context.settings().draftAuthoring()) {
            throw new DraftAuthoringDisabledException(ruleSet, version);
        }
        byte[] source = request.source();
        String key = opts.idempotencyKey() != null ? opts.idempotencyKey() : request.idempotencyKey();
        String requestHash = Hashes.sha256Parts("replace-draft", actor, ruleSet, Integer.toString(version),
                String.valueOf(opts.expectedLockVersion()), request.format().name(), Hashes.sha256(source));
        return context.idempotent(tenant, "replace-draft", key, requestHash, 200, () -> {
            StoredVersion current = load(tenant, ruleSet, version);
            if (current.status() != VersionStatus.DRAFT) {
                throw new InvalidStateException(ruleSet, version, current.status(), "replace");
            }
            // Four eyes compares the approver with the author (created_by), so only the author replaces the content;
            // another author imports a new version, which records them as its author.
            if (!actor.equals(current.createdBy())) {
                throw PermissionDeniedException.notAuthor(ruleSet, version);
            }
            checkLock(current, opts);
            String sourceText = utf8(source);
            ParsedRuleSet parsed = checkedDraftDocument(tenant, ruleSet, request.format(), source);
            StoredVersion updated = current.replaced(parsed.model().decision().table().hitPolicy().name(),
                    parsed.model().effectiveFrom(), parsed.canonicalJson(), parsed.canonicalHash(), request.format(),
                    sourceText, Hashes.sha256(source));
            store.replaceDraft(updated, current.lockVersion());
            // Every content change leaves an immutable audit row with both hashes (architecture decision on #234).
            Instant now = context.clock().instant();
            store.appendHistory(new HistoryRow(tenant, Ulid.next(now), updated.id(), LifecycleAction.DRAFT_UPDATED,
                    VersionStatus.DRAFT, VersionStatus.DRAFT, actor, null, updated.lockVersion(), now,
                    updated.canonicalHash(), current.canonicalHash()));
            event(tenant, EVENT_DRAFT_UPDATED, updated, actor, now, correlationId, payload -> payload
                    .put("updatedBy", actor)
                    .put("lockVersion", updated.lockVersion())
                    .put("canonicalHash", updated.canonicalHash())
                    .put("previousCanonicalHash", current.canonicalHash()));
            return view(updated, false);
        }, v -> EngineValues.write(EngineValues.version(v)), EngineValues::versionFromJson);
    }

    /**
     * The checks a draft's new document passes, shared by {@link #replaceDraft} and {@link #validateDraft} so a
     * validation can never be kinder or stricter than the save it stands in for (RLE-090).
     */
    private ParsedRuleSet checkedDraftDocument(String tenant, String ruleSet, SourceFormat format, byte[] source) {
        ParsedRuleSet parsed = checkedDocument(format, source);
        requireMaskDeclarations(tenant, parsed.canonicalJson());
        if (!ruleSet.equals(parsed.model().name())) {
            throw new InputInvalidException("Document names rule set '" + parsed.model().name() + "', not '"
                    + ruleSet + "'");
        }
        return parsed;
    }

    @Override
    public DraftValidation validateDraft(String ruleSet, int version, ImportRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.size() > RuleSetReader.MAX_SOURCE_BYTES) {
            throw new PayloadTooLargeException(RuleSetReader.MAX_SOURCE_BYTES);
        }
        String tenant = context.tenant();
        context.callerWith(PERMISSION_AUTHOR);
        if (!context.settings().draftAuthoring()) {
            throw new DraftAuthoringDisabledException(ruleSet, version);
        }
        byte[] source = request.source();
        return context.withoutDooming(() -> {
            StoredVersion current = load(tenant, ruleSet, version);
            if (current.status() != VersionStatus.DRAFT) {
                throw new InvalidStateException(ruleSet, version, current.status(), "validate");
            }
            try {
                ParsedRuleSet parsed = checkedDraftDocument(tenant, ruleSet, request.format(), source);
                boolean maskByDefault = context.masking().maskByDefault(tenant);
                return new DraftValidation(ruleSet, version, true, List.of(), false,
                        catalogue.readings(parsed.model(), maskByDefault));
            } catch (ValidationFailedException refused) {
                return new DraftValidation(ruleSet, version, false,
                        refused.violations().stream().map(catalogue::problem).toList(), refused.truncated(),
                        List.of());
            }
        });
    }

    @Override
    public VersionDiff diff(String ruleSet, int version, int base) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_READ);
        requireRuleSetName(ruleSet);
        if (base == version) {
            throw new InputInvalidException("base must name another version than " + version);
        }
        return context.withoutDooming(() -> {
            StoredVersion compared = existing(tenant, ruleSet, version);
            StoredVersion against = existing(tenant, ruleSet, base);
            return VersionDiffs.diff(compared, against, context.masking().maskByDefault(tenant),
                    context.masking().personalDataNames(tenant));
        });
    }

    private StoredVersion existing(String tenant, String ruleSet, int version) {
        return store.findVersion(tenant, ruleSet, version, false).orElseThrow(() ->
                store.findRuleSetId(tenant, ruleSet).isPresent()
                        ? new VersionNotFoundException(ruleSet, version)
                        : new RuleSetNotFoundException(ruleSet));
    }

    @Override
    public RuleSetVersion requestPublish(String ruleSet, int version, WriteOptions options) {
        WriteOptions opts = options == null ? WriteOptions.none() : options;
        EventEnvelope.requireValidCorrelationId(opts.correlationId());
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
            // RLE-004: the draft is checked again with today's checker, so a draft stored before a check existed
            // cannot reach an approver; a refusal leaves it DRAFT.
            recheck(current);
            // A draft imported before strict mode was turned on is checked again before anyone can approve it.
            requireMaskDeclarations(tenant, current.canonicalJson());
            Instant now = context.clock().instant();
            StoredVersion updated = current.requested(actor, now);
            store.updateVersion(updated, current.lockVersion());
            history(updated, LifecycleAction.REQUESTED, current.status(), actor, null, now);
            // DESIGN 3.3: the request names the behaviour hash and the date it asks for (null means at approval).
            event(tenant, EVENT_PUBLISH_REQUESTED, updated, actor, now, opts.correlationId(), payload -> {
                payload.put("canonicalHash", updated.canonicalHash());
                payload.put("requestedBy", actor);
                payload.set("effectiveFrom", EngineValues.value(updated.effectiveFrom()));
            });
            return view(updated, false);
        }, v -> EngineValues.write(EngineValues.version(v)), EngineValues::versionFromJson);
    }

    @Override
    public RuleSetVersion approve(String ruleSet, int version, WriteOptions options) {
        WriteOptions opts = options == null ? WriteOptions.none() : options;
        EventEnvelope.requireValidCorrelationId(opts.correlationId());
        String tenant = context.tenant();
        String actor = context.callerWith(PERMISSION_APPROVE);
        requireLockVersion(opts, ":approve");
        requireCommentLength(opts.comment());
        requireContentHash(opts);
        requireHashShape(opts.expectedCanonicalHash());
        java.util.Set<AcknowledgedWarning> acknowledged = acknowledgedPairs(opts.acknowledgedWarnings());
        // The hash joins the request hash only when named, so a request stored before this field existed still replays.
        String requestHash = opts.expectedCanonicalHash() == null
                ? Hashes.sha256Parts("approve", actor, ruleSet, Integer.toString(version),
                        String.valueOf(opts.expectedLockVersion()), opts.comment(), sortedPairs(acknowledged))
                : Hashes.sha256Parts("approve", actor, ruleSet, Integer.toString(version),
                        String.valueOf(opts.expectedLockVersion()), opts.comment(), sortedPairs(acknowledged),
                        opts.expectedCanonicalHash());
        return context.idempotent(tenant, "approve", opts.idempotencyKey(), requestHash, 200, () -> {
            StoredVersion current = load(tenant, ruleSet, version);
            if (current.status() != VersionStatus.PENDING_APPROVAL) {
                throw new InvalidStateException(ruleSet, version, current.status(), "approve");
            }
            // Four eyes: neither the person who requested publication nor the author of the content may approve it.
            if (actor.equals(current.requestedBy()) || actor.equals(current.createdBy())) {
                throw new SelfApprovalForbiddenException(ruleSet, version);
            }
            // PD-105: a version written before the author was recorded cannot pass four eyes; no author is invented.
            if (current.createdBy() == null) {
                throw new AuthorUnknownException(ruleSet, version);
            }
            // DESIGN 3.3.5 order: permission, If-Match present, status, four eyes, lock, reviewed content, strict
            // mode, acknowledgement.
            checkLock(current, opts);
            checkContent(current, opts);
            // A version requested before strict mode was turned on is checked again, so it never reaches the
            // append-only decision log with an undeclared input (security review of #308).
            requireMaskDeclarations(tenant, current.canonicalJson());
            // RLE-056: a call to a function the host removed since the import is refused here too, as at request.
            recheck(current);
            Acknowledgement acknowledgement = requireAcknowledgement(current, acknowledged);
            Instant now = context.clock().instant();
            StoredVersion updated = current.approved(actor, now, opts.comment());
            // RLE-058: the function versions the version is approved with are the baseline of a later change.
            context.functions().approvals().record(tenant, ruleSet, version, compiledFunctions(current), now);
            store.updateVersion(updated, current.lockVersion());
            store.appendHistory(new HistoryRow(tenant, Ulid.next(now), updated.id(), LifecycleAction.APPROVED,
                    current.status(), updated.status(), actor, opts.comment(), updated.lockVersion(), now, null, null,
                    null, null, null, acknowledgement.accepted(), acknowledgement.maskedByDefault()));
            // ADR-0009: the epoch moves and every replica is notified in this transaction; this replica evicts
            // once the approval is committed, so it never reloads the version before others can see it.
            long epoch = store.advanceCacheEpoch(tenant, updated.ruleSetId(), updated.ruleSet());
            context.transactions().afterCommit(() -> cache.evict(tenant, updated.ruleSet(), epoch));
            // DESIGN 3.3: the publication names what was approved, from when, both people of the four eyes, and the
            // warnings the approver accepted on the record (PD-104).
            event(tenant, EVENT_PUBLISHED, updated, actor, now, opts.correlationId(), payload -> {
                payload.put("canonicalHash", updated.canonicalHash());
                payload.set("effectiveFrom", EngineValues.value(updated.effectiveFrom()));
                payload.put("requestedBy", updated.requestedBy());
                payload.put("approvedBy", actor);
                var accepted = payload.putArray("acknowledgedWarnings");
                acknowledgement.accepted().forEach(w -> accepted.addObject().put("code", w.code()).put("input", w.input()));
                acknowledgement.maskedByDefault().forEach(payload.putArray("maskedByDefaultInputs")::add);
            });
            if (!updated.effectiveFrom().isAfter(now)) {
                // In effect at once: what it supersedes retires in this transaction (DESIGN 3.3.2, RLE-027).
                supersession.retire(tenant, updated.ruleSetId(), now, opts.correlationId());
            }
            return view(updated, false);
        }, v -> EngineValues.write(EngineValues.version(v)), EngineValues::versionFromJson);
    }

    @Override
    public RuleSetVersion reject(String ruleSet, int version, WriteOptions options) {
        WriteOptions opts = options == null ? WriteOptions.none() : options;
        EventEnvelope.requireValidCorrelationId(opts.correlationId());
        String tenant = context.tenant();
        String actor = context.callerWith(PERMISSION_APPROVE);
        requireLockVersion(opts, ":reject");
        String reason = requireReason(opts.comment());
        requireContentHash(opts);
        requireHashShape(opts.expectedCanonicalHash());
        String requestHash = opts.expectedCanonicalHash() == null
                ? Hashes.sha256Parts("reject", actor, ruleSet, Integer.toString(version),
                        String.valueOf(opts.expectedLockVersion()), reason)
                : Hashes.sha256Parts("reject", actor, ruleSet, Integer.toString(version),
                        String.valueOf(opts.expectedLockVersion()), reason, opts.expectedCanonicalHash());
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
            checkContent(current, opts);
            Instant now = context.clock().instant();
            StoredVersion updated = current.rejected();
            store.updateVersion(updated, current.lockVersion());
            history(updated, LifecycleAction.REJECTED, current.status(), actor, reason, now);
            event(tenant, EVENT_REJECTED, updated, actor, now, opts.correlationId(), payload -> payload
                    .put("rejectedBy", actor)
                    .put("reason", reason));
            return view(updated, false);
        }, v -> EngineValues.write(EngineValues.version(v)), EngineValues::versionFromJson);
    }

    @Override
    public RuleSetVersion retire(String ruleSet, int version, WriteOptions options) {
        return retire(ruleSet, version, options, false);
    }

    @Override
    public RuleSetVersion retire(String ruleSet, int version, WriteOptions options, boolean confirmNoVersionInEffect) {
        WriteOptions opts = options == null ? WriteOptions.none() : options;
        String tenant = context.tenant();
        String actor = context.callerWith(PERMISSION_APPROVE);
        String reason = requireReason(opts.comment());
        EventEnvelope.requireValidCorrelationId(opts.correlationId());
        // The confirmation is part of the request only when given, so a key used without it still replays.
        String requestHash = confirmNoVersionInEffect
                ? Hashes.sha256Parts("retire", actor, ruleSet, Integer.toString(version),
                        String.valueOf(opts.expectedLockVersion()), reason, "confirm-no-version-in-effect")
                : Hashes.sha256Parts("retire", actor, ruleSet, Integer.toString(version),
                        String.valueOf(opts.expectedLockVersion()), reason);
        return context.idempotent(tenant, "retire", opts.idempotencyKey(), requestHash, 200, () -> {
            requireRuleSetName(ruleSet);
            String ruleSetId = store.findRuleSetId(tenant, ruleSet).orElseThrow(() -> new RuleSetNotFoundException(ruleSet));
            // The rule set row first, then the versions in effect: two concurrent retirements of its last two versions
            // run one after the other, and the second sees that it is now the last (PD-101).
            store.lockRuleSet(tenant, ruleSetId);
            StoredVersion current = load(tenant, ruleSet, version);
            if (current.status() != VersionStatus.PUBLISHED) {
                throw new InvalidStateException(ruleSet, version, current.status(), "retire");
            }
            if (current.retirementRequest() != null) {
                throw InvalidStateException.retirePending(ruleSet, version, current.retirementRequest().requestedBy());
            }
            checkLock(current, opts);
            Instant now = context.clock().instant();
            boolean last = lastInEffect(tenant, current, now);
            String lastSetting = context.settings().retireLastVersion();
            // RLE-038: in four-eyes mode, and for the last version in effect with retire-last-version four-eyes, this
            // call only asks; the version keeps serving until a second approver confirms.
            boolean fourEyes = EngineDefaults.RETIRE_APPROVAL_FOUR_EYES.equals(context.settings().retireApproval())
                    || (last && EngineDefaults.RETIRE_LAST_FOUR_EYES.equals(lastSetting));
            if (last) {
                requireLastVersionAllowed(tenant, current, now, confirmNoVersionInEffect);
            }
            if (fourEyes) {
                StoredVersion updated = current.retireRequested(
                        new RetirementRequest(actor, now, reason, confirmNoVersionInEffect));
                store.updateVersion(updated, current.lockVersion());
                history(updated, LifecycleAction.RETIRE_REQUESTED, current.status(), actor, reason, now);
                event(tenant, EVENT_RETIRE_REQUESTED, updated, actor, now, opts.correlationId(), payload -> payload
                        .put("requestedBy", actor)
                        .put("reason", reason));
                return view(updated, false);
            }
            return retireNow(tenant, current, actor, reason, context.settings().retireApproval(), last, null,
                    opts.correlationId(), now);
        }, v -> EngineValues.write(EngineValues.version(v)), EngineValues::versionFromJson);
    }

    @Override
    public RuleSetVersion confirmRetire(String ruleSet, int version, WriteOptions options,
            boolean confirmNoVersionInEffect) {
        WriteOptions opts = options == null ? WriteOptions.none() : options;
        String tenant = context.tenant();
        String actor = context.callerWith(PERMISSION_APPROVE);
        requireLockVersion(opts, ":confirm-retire");
        EventEnvelope.requireValidCorrelationId(opts.correlationId());
        String requestHash = Hashes.sha256Parts("confirm-retire", actor, ruleSet, Integer.toString(version),
                String.valueOf(opts.expectedLockVersion()), Boolean.toString(confirmNoVersionInEffect));
        return context.idempotent(tenant, "confirm-retire", opts.idempotencyKey(), requestHash, 200, () -> {
            requireRuleSetName(ruleSet);
            String ruleSetId = store.findRuleSetId(tenant, ruleSet).orElseThrow(() -> new RuleSetNotFoundException(ruleSet));
            store.lockRuleSet(tenant, ruleSetId);
            StoredVersion current = load(tenant, ruleSet, version);
            RetirementRequest request = current.retirementRequest();
            if (request == null) {
                throw InvalidStateException.noRetirePending(ruleSet, version, current.status(), "confirm-retire");
            }
            // Four eyes on retirement (PD-080): the requester never confirms their own request.
            if (actor.equals(request.requestedBy())) {
                throw SelfApprovalForbiddenException.selfConfirmRetire(ruleSet, version);
            }
            checkLock(current, opts);
            Instant now = context.clock().instant();
            // Checked again now: time has passed since the request, and a version scheduled then may be in effect.
            boolean last = lastInEffect(tenant, current, now);
            if (last) {
                requireLastVersionAllowed(tenant, current, now,
                        request.confirmNoVersionInEffect() || confirmNoVersionInEffect, true);
            }
            return retireNow(tenant, current, actor, request.reason(), EngineDefaults.RETIRE_APPROVAL_FOUR_EYES, last,
                    request.requestedBy(), opts.correlationId(), now);
        }, v -> EngineValues.write(EngineValues.version(v)), EngineValues::versionFromJson);
    }

    @Override
    public RuleSetVersion cancelRetire(String ruleSet, int version, WriteOptions options) {
        WriteOptions opts = options == null ? WriteOptions.none() : options;
        String tenant = context.tenant();
        String actor = context.callerWith(PERMISSION_APPROVE);
        requireLockVersion(opts, ":cancel-retire");
        String reason = requireReason(opts.comment());
        EventEnvelope.requireValidCorrelationId(opts.correlationId());
        String requestHash = Hashes.sha256Parts("cancel-retire", actor, ruleSet, Integer.toString(version),
                String.valueOf(opts.expectedLockVersion()), reason);
        return context.idempotent(tenant, "cancel-retire", opts.idempotencyKey(), requestHash, 200, () -> {
            StoredVersion current = load(tenant, ruleSet, version);
            if (current.retirementRequest() == null) {
                throw InvalidStateException.noRetirePending(ruleSet, version, current.status(), "cancel-retire");
            }
            checkLock(current, opts);
            Instant now = context.clock().instant();
            StoredVersion updated = current.retireCancelled();
            store.updateVersion(updated, current.lockVersion());
            history(updated, LifecycleAction.RETIRE_CANCELLED, current.status(), actor, reason, now);
            return view(updated, false);
        }, v -> EngineValues.write(EngineValues.version(v)), EngineValues::versionFromJson);
    }

    /**
     * Whether retiring this version now would leave no version in effect (PD-101): it is in force (older versions retire
     * with it and none comes back), or nothing is in force at all. A version that only starts later does not count.
     *
     * @throws InvalidStateException when a newer version in effect already supersedes it: it no longer serves and the
     *     sweep retires it; refused before anything is written, so no actor or reason lands on the wrong transition
     */
    private boolean lastInEffect(String tenant, StoredVersion current, Instant now) {
        Optional<StoredVersion> inForce = store.findEffective(tenant, current.ruleSet(), now);
        if (inForce.isPresent() && inForce.get().version() > current.version()) {
            throw InvalidStateException.superseded(current.ruleSet(), current.version(), inForce.get().version());
        }
        return inForce.isEmpty() || inForce.get().version() == current.version();
    }

    /** A request for the last version in effect: refused, or needs the confirmation, as the host set it (PD-101). */
    private void requireLastVersionAllowed(String tenant, StoredVersion current, Instant now, boolean confirmed) {
        requireLastVersionAllowed(tenant, current, now, confirmed, false);
    }

    /**
     * @param secondApprover true at a four-eyes confirmation: with {@code retire-last-version: four-eyes} the second
     *     approver is the control, so no confirmation is needed; at a request that setting turns the call into a request
     */
    private void requireLastVersionAllowed(String tenant, StoredVersion current, Instant now, boolean confirmed,
            boolean secondApprover) {
        String setting = context.settings().retireLastVersion();
        boolean allowed = switch (setting) {
            case EngineDefaults.RETIRE_LAST_REFUSE -> false;
            case EngineDefaults.RETIRE_LAST_FOUR_EYES -> true;
            default -> confirmed;
        };
        if (!allowed) {
            Optional<StoredVersion> next = store.nextScheduled(tenant, current.ruleSetId(), now, current.id());
            throw new LastVersionInEffectException(current.ruleSet(), current.version(), setting,
                    next.map(StoredVersion::version).orElse(null), next.map(StoredVersion::effectiveFrom).orElse(null));
        }
    }

    /**
     * The effects of a retirement in either mode (DESIGN 3.3): what it superseded retires first, then the version, the
     * cache epoch, one {@code RETIRED} history row and one {@code rules.version.retired.v1} event.
     *
     * @param requestedBy the four-eyes requester, or null in {@code single} mode
     */
    private RuleSetVersion retireNow(String tenant, StoredVersion current, String actor, String reason, String mode,
            boolean last, String requestedBy, String correlationId, Instant now) {
        // Retire what it superseded first, while it is still published, so a retire never brings back an older
        // version whose status lags its supersession (ADR-0025 decision 1).
        supersession.retire(tenant, current.ruleSetId(), now, correlationId);
        StoredVersion updated = current.retired(now);
        store.updateVersion(updated, current.lockVersion());
        // ADR-0009: as on approve, the epoch moves and every replica is notified in this transaction; this replica
        // evicts once the retirement is committed.
        long epoch = store.advanceCacheEpoch(tenant, updated.ruleSetId(), updated.ruleSet());
        context.transactions().afterCommit(() -> cache.evict(tenant, updated.ruleSet(), epoch));
        // One manual retirement in either mode, counted once committed; a four-eyes request counts nothing until confirmed.
        context.transactions().afterCommit(() -> meters.retired(EngineMeters.RetireReason.MANUAL, 1));
        store.appendHistory(new HistoryRow(tenant, Ulid.next(now), updated.id(), LifecycleAction.RETIRED,
                current.status(), updated.status(), actor, reason, updated.lockVersion(), now, null, mode, last, null,
                null, List.of(), List.of(), requestedBy));
        String eventId = Ulid.next(now);
        tools.jackson.databind.node.ObjectNode payload = Supersession.retiredEvent(eventId, correlationId, tenant,
                current.ruleSet(), current.version(), actor, reason, null, now, mode, last);
        if (requestedBy != null) {
            payload.put("requestedBy", requestedBy);
        }
        outbox.append(tenant, eventId, EngineValues.write(payload), now);
        return view(updated, false);
    }

    @Override
    public RuleSetVersion withdraw(String ruleSet, int version, WriteOptions options) {
        WriteOptions opts = options == null ? WriteOptions.none() : options;
        EventEnvelope.requireValidCorrelationId(opts.correlationId());
        String tenant = context.tenant();
        String actor = context.callerWith(PERMISSION_AUTHOR);
        String reason = optionalReason(opts.comment());
        String requestHash = Hashes.sha256Parts("withdraw", actor, ruleSet, Integer.toString(version),
                String.valueOf(opts.expectedLockVersion()), reason);
        return context.idempotent(tenant, "withdraw", opts.idempotencyKey(), requestHash, 200, () -> {
            StoredVersion current = load(tenant, ruleSet, version);
            if (current.status() != VersionStatus.PENDING_APPROVAL) {
                throw new InvalidStateException(ruleSet, version, current.status(), "withdraw");
            }
            // PD-089: only the identity that requested publication takes it back; an approver rejects instead, so
            // the history never records an approver's refusal as the author's own withdrawal.
            if (!actor.equals(current.requestedBy())) {
                throw PermissionDeniedException.notRequester(ruleSet, version);
            }
            checkLock(current, opts);
            Instant now = context.clock().instant();
            StoredVersion updated = current.withdrawn();
            store.updateVersion(updated, current.lockVersion());
            history(updated, LifecycleAction.WITHDRAWN, current.status(), actor, reason, now);
            event(tenant, EVENT_WITHDRAWN, updated, actor, now, opts.correlationId(), payload -> payload
                    .put("withdrawnBy", actor)
                    .put("reason", reason));
            return view(updated, false);
        }, v -> EngineValues.write(EngineValues.version(v)), EngineValues::versionFromJson);
    }

    @Override
    public RuleSetVersion discard(String ruleSet, int version, WriteOptions options) {
        WriteOptions opts = options == null ? WriteOptions.none() : options;
        EventEnvelope.requireValidCorrelationId(opts.correlationId());
        String tenant = context.tenant();
        String actor = context.callerWith(PERMISSION_AUTHOR);
        String reason = optionalReason(opts.comment());
        String requestHash = Hashes.sha256Parts("discard", actor, ruleSet, Integer.toString(version),
                String.valueOf(opts.expectedLockVersion()), reason);
        return context.idempotent(tenant, "discard", opts.idempotencyKey(), requestHash, 200, () -> {
            StoredVersion current = load(tenant, ruleSet, version);
            if (current.status() != VersionStatus.DRAFT) {
                throw new InvalidStateException(ruleSet, version, current.status(), "discard");
            }
            // As for replacing a draft: only its author may throw it away, so nobody removes another person's work.
            if (!actor.equals(current.createdBy())) {
                throw PermissionDeniedException.notAuthorToDiscard(ruleSet, version);
            }
            checkLock(current, opts);
            Instant now = context.clock().instant();
            StoredVersion updated = current.discarded();
            store.updateVersion(updated, current.lockVersion());
            history(updated, LifecycleAction.DISCARDED, current.status(), actor, reason, now);
            event(tenant, EVENT_DISCARDED, updated, actor, now, opts.correlationId(), payload -> payload
                    .put("discardedBy", actor)
                    .put("canonicalHash", current.canonicalHash())
                    .put("reason", reason));
            return view(updated, false);
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
    public Page<PendingApproval> pendingApprovals(PageRequest page) {
        String tenant = context.tenant();
        String caller = context.callerWith(PERMISSION_READ);
        PageRequest request = page == null ? PageRequest.first() : page;
        int limit = limit(request);
        // Verified in memory before any transaction: a forged cursor never reaches the database (RLE-039).
        var after = Cursors.decisionPosition(cursorKeys.verify(tenant, "", CURSOR_PENDING, request.cursor()));
        java.util.Set<String> permissions = context.permissions();
        return context.withoutDooming(() -> {
            List<io.github.dhluong90.bpm.rules.internal.store.PendingVersion> rows =
                    store.listPendingApprovals(tenant, after, limit + 1);
            List<PendingApproval> items = rows.stream().limit(limit).map(v -> {
                List<String> allowed = VersionActions.allowed(VersionStatus.PENDING_APPROVAL, v.createdBy(),
                        v.requestedBy(), caller, permissions);
                return new PendingApproval(v.ruleSet(), v.version(), v.canonicalHash(), v.lockVersion(),
                        v.requestedBy(), v.requestedAt(), allowed,
                        VersionActions.pendingDenials(v.createdBy(), v.requestedBy(), caller, permissions));
            }).toList();
            if (rows.size() <= limit) {
                return new Page<>(items, null);
            }
            var last = rows.get(limit - 1);
            return new Page<>(items, cursorKeys.sign(tenant, "", CURSOR_PENDING,
                    Cursors.decisionKey(last.requestedAt(), last.id())));
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

    /** A withdrawal reason is optional (PD-089); a blank one is stored as none. It has the reject reason's bound. */
    private String optionalReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
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
                store.findVersion(tenant, ruleSet, version, false).map(v -> view(v, includeSource)));
    }

    @Override
    public void validate(String ruleSet, int version) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_READ);
        requireRuleSetName(ruleSet);
        StoredVersion stored = context.withoutDooming(() -> store.findVersion(tenant, ruleSet, version, false)
                .orElseThrow(() -> store.findRuleSetId(tenant, ruleSet).isPresent()
                        ? new VersionNotFoundException(ruleSet, version)
                        : new RuleSetNotFoundException(ruleSet)));
        recheck(stored);
        requireMaskDeclarations(tenant, stored.canonicalJson());
    }

    /**
     * Reads and compiles a stored version exactly as import would today: from the source document when it is kept,
     * so violations follow the order cells are written in (RLE-041), else from the canonical form.
     */
    private void recheck(StoredVersion stored) {
        if (stored.sourceDocument() != null
                && (stored.sourceFormat() == SourceFormat.YAML || stored.sourceFormat() == SourceFormat.DMN_XML)) {
            checkedDocument(stored.sourceFormat(), stored.sourceDocument().getBytes(StandardCharsets.UTF_8));
        } else {
            compileForPublication(RuleSetReader.readCanonical(stored.canonicalJson()));
        }
    }

    /**
     * Compiles a model with the host's configured limits, the ones evaluation will apply, and refuses a table whose
     * worst-case step count is over the step budget (TR-SEC-01), so it fails here and not on every evaluation.
     */
    /** The host functions a stored version calls, each with the version label registered now. */
    private java.util.Map<String, String> compiledFunctions(StoredVersion stored) {
        EngineSettings settings = context.settings();
        return CompiledRuleSet.compile(RuleSetReader.readCanonical(stored.canonicalJson()), settings.maxOutputBytes(),
                settings.maxStringLength(), settings.regexLimits(), settings.evaluationLimits(),
                context.functions().library()).hostFunctions();
    }

    private void compileForPublication(io.github.dhluong90.bpm.rules.internal.model.Definitions model) {
        EngineSettings settings = context.settings();
        CompiledRuleSet.compile(model, settings.maxOutputBytes(), settings.maxStringLength(), settings.regexLimits(),
                settings.evaluationLimits(), context.functions().library()).requireWithinStepBudget();
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

    /**
     * The caller's view of a version, with the warnings its author and approver should see (RLE-049): every undeclared
     * input whose name looks like personal data under the tenant's list. Computed from the stored definition and the
     * tenant's settings on each read, so the approver sees what the author saw at import. Each warning says whether an
     * approval must acknowledge it, by the same rule as the approve check (RLE-053).
     */
    private RuleSetVersion view(StoredVersion version, boolean includeSource) {
        return version.toView(includeSource).withWarnings(acknowledgements(version).warnings());
    }

    /**
     * What an approval put on the record (RLE-053, PD-104): the warnings accepted, empty when none needed it, and the
     * inputs masked by the tenant's default rather than by their declaration.
     */
    private record Acknowledgement(List<AcknowledgedWarning> accepted, List<String> maskedByDefault) {
    }

    /**
     * Accepts an approval only when the pairs it acknowledged equal the pairs that need an acknowledgement now (RLE-053,
     * PD-104, security review of #316). The needed pairs are recomputed from the stored definition and the tenant's
     * personal-data names and masking default in force, and filtered to the codes the host lists in
     * {@code lifecycle.acknowledge-warnings}. A warning on an input that the tenant's default masks needs none, because
     * its values never reach the log in plain text. Sets are compared, so order and duplicates do not matter; a missing
     * or an extra pair is refused with the full current set. Exactly that set is recorded.
     */
    private Acknowledgement requireAcknowledgement(StoredVersion version, java.util.Set<AcknowledgedWarning> sent) {
        Acknowledgements now = acknowledgements(version);
        List<VersionWarning> needing = now.warnings().stream().filter(VersionWarning::requiresAcknowledgement).toList();
        List<AcknowledgedWarning> needed = needing.stream()
                .map(w -> new AcknowledgedWarning(w.code(), w.input())).distinct().toList();
        if (!java.util.Set.copyOf(needed).equals(sent)) {
            throw new WarningsNotAcknowledgedException(version.ruleSet(), version.version(), needing.stream()
                    .map(w -> new WarningsNotAcknowledgedException.Unacknowledged(w.code(), w.input(), w.matchedName()))
                    .toList(), context.settings().acknowledgeWarnings());
        }
        return new Acknowledgement(needed, now.maskedByDefault());
    }

    /**
     * A version's warnings, each marked with whether an approval must acknowledge it, and the inputs masked only by
     * the tenant's default.
     */
    private record Acknowledgements(List<VersionWarning> warnings, List<String> maskedByDefault) {
    }

    /**
     * The one rule of which warnings need an acknowledgement (RLE-053, PD-104), used by the version view and the
     * approve check alike: the code is listed in {@code lifecycle.acknowledge-warnings} and the input is not masked
     * only by the tenant's {@code decision-log.mask-default}, whose values never reach the log in plain text.
     * Computed from the stored definition and the tenant's personal-data names and masking default in force now.
     */
    private Acknowledgements acknowledgements(StoredVersion version) {
        String tenant = version.tenantId();
        boolean maskByDefault = context.masking().maskByDefault(tenant);
        List<String> maskedByDefault = MaskDeclarations.statuses(version.canonicalJson(), maskByDefault).stream()
                .filter(s -> s.masking() == io.github.dhluong90.bpm.rules.api.VersionDiff.Masking.MASKED_BY_DEFAULT)
                .map(MaskDeclarations.Status::input)
                .sorted()
                .toList();
        List<String> listed = context.settings().acknowledgeWarnings();
        List<VersionWarning> warnings = MaskDeclarations.warnings(version.canonicalJson(),
                        context.masking().personalDataNames(tenant), maskByDefault).stream()
                .map(w -> w.withRequiresAcknowledgement(listed.contains(w.code())
                        && !maskedByDefault.contains(w.input())))
                .toList();
        return new Acknowledgements(warnings, maskedByDefault);
    }

    /** Most pairs an approval may acknowledge. */
    static final int ACKNOWLEDGED_MAX = 100;

    /** Longest input name an acknowledged pair may carry. */
    static final int ACKNOWLEDGED_INPUT_MAX = 100;

    /**
     * Validates the acknowledged pairs of an approval: at most {@value #ACKNOWLEDGED_MAX}, each a known warning code
     * and an input name of 1 to {@value #ACKNOWLEDGED_INPUT_MAX} characters, else {@code 400 INPUT_INVALID}.
     */
    private static java.util.Set<AcknowledgedWarning> acknowledgedPairs(List<AcknowledgedWarning> pairs) {
        if (pairs.size() > ACKNOWLEDGED_MAX) {
            throw new InputInvalidException("acknowledgedWarnings holds at most " + ACKNOWLEDGED_MAX + " entries, was "
                    + pairs.size());
        }
        for (AcknowledgedWarning pair : pairs) {
            if (!VersionWarning.CODES.contains(pair.code())) {
                throw new InputInvalidException("acknowledgedWarnings: " + pair.code() + " is not a warning code; known: "
                        + new java.util.TreeSet<>(VersionWarning.CODES));
            }
            if (pair.input().isEmpty() || pair.input().length() > ACKNOWLEDGED_INPUT_MAX) {
                throw new InputInvalidException("acknowledgedWarnings: an input name is 1 to " + ACKNOWLEDGED_INPUT_MAX
                        + " characters");
            }
        }
        return java.util.Set.copyOf(pairs);
    }

    /** The pairs sorted, for the idempotency request hash. */
    private static String sortedPairs(java.util.Set<AcknowledgedWarning> pairs) {
        return pairs.stream().map(p -> p.code() + ":" + p.input().length() + ":" + p.input()).sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    /** Strict mode (RLE-050): every input declares {@code mask}, or the command is refused before anything is stored. */
    private void requireMaskDeclarations(String tenant, String canonicalJson) {
        if (context.masking().requireExplicitMask(tenant)) {
            MaskDeclarations.requireDeclared(canonicalJson);
        }
    }

    private StoredVersion load(String tenant, String ruleSet, int version) {
        requireRuleSetName(ruleSet);
        return store.findVersion(tenant, ruleSet, version, true).orElseThrow(() ->
                store.findRuleSetId(tenant, ruleSet).isPresent()
                        ? new VersionNotFoundException(ruleSet, version)
                        : new RuleSetNotFoundException(ruleSet));
    }

    /**
     * An approval or a rejection must name the lock version the decision was based on (#234 security review, #275).
     * A version number can carry other content over time (replace in place, withdraw, request again), so without the
     * lock an approver could publish content nobody reviewed. Missing is {@code 428 PRECONDITION_REQUIRED}; a stale
     * value is {@code 409 VERSION_CONFLICT} in {@link #checkLock}.
     */
    private static void requireLockVersion(WriteOptions options, String operation) {
        if (options.expectedLockVersion() == null) {
            throw new PreconditionRequiredException(operation);
        }
    }

    private static void checkLock(StoredVersion current, WriteOptions options) {
        if (options.expectedLockVersion() != null && options.expectedLockVersion() != current.lockVersion()) {
            throw new VersionConflictException("Expected lock version " + options.expectedLockVersion()
                    + " but " + current.ruleSet() + " v" + current.version() + " is at " + current.lockVersion(),
                    current.canonicalHash(), current.lockVersion());
        }
    }

    /**
     * An approval or a rejection binds to the content the approver reviewed (RLE-084, #275). A version number can
     * carry other content over time (withdraw, replace in place, request again), and a client that refreshed the lock
     * version can still hold a decision made on older content, so when the caller names the hash it reviewed the
     * stored hash must equal it. The refusal writes nothing and names the content now stored.
     */
    private static void checkContent(StoredVersion current, WriteOptions options) {
        String expected = options.expectedCanonicalHash();
        if (expected != null && !expected.equals(current.canonicalHash())) {
            throw new ContentChangedException("The content of " + current.ruleSet() + " v" + current.version()
                    + " changed since it was reviewed: expected canonical hash " + expected + " but it is now "
                    + current.canonicalHash() + ", at lock version " + current.lockVersion(),
                    current.canonicalHash(), current.lockVersion());
        }
    }

    /**
     * With {@code approval.require-content-hash} on (the default), a decision that names no hash is refused before
     * anything is read, so an approval can never be bound to whatever happens to be stored (RLE-084, #275).
     */
    private void requireContentHash(WriteOptions options) {
        if (options.expectedCanonicalHash() == null && context.settings().requireContentHash()) {
            throw new ContentHashRequiredException();
        }
    }

    /** A hash the caller names is 64 lower-case hexadecimal characters, as {@code canonicalHash} is shown. */
    private static void requireHashShape(String hash) {
        if (hash != null && !hash.matches("[0-9a-f]{64}")) {
            throw new InputInvalidException("expectedCanonicalHash must be 64 lower-case hexadecimal characters");
        }
    }

    /**
     * Writes one lifecycle event with the full envelope (DESIGN section 3.3). The event id is the outbox row id. A
     * lifecycle command has no command id of its own, so {@code causationId} is null.
     */
    private void event(String tenant, String type, StoredVersion version, String actor, Instant at,
            String correlationId, java.util.function.Consumer<ObjectNode> extra) {
        String eventId = Ulid.next(at);
        ObjectNode payload = EventEnvelope.start(eventId, type, tenant, at, correlationId, null)
                .put("ruleSet", version.ruleSet())
                .put("version", version.version())
                .put("actor", actor);
        extra.accept(payload);
        outbox.append(tenant, eventId, EngineValues.write(payload), at);
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
