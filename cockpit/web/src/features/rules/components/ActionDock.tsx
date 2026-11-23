import { useId, useState, type ReactNode } from 'react';
import { isUnclassified, readPendingWarnings, type AcknowledgedWarning, type DiscardedVersion, type RuleSetVersion } from '../../../api/rules/schemas';
import { DiscardDraft } from './DiscardDraft';
import { useI18n } from '../../../i18n/I18nProvider';
import type { MessageKey } from '../../../i18n/en';
import { useRulesSession } from '../api/rulesContext';
import { formatDateTime } from '../model/dates';
import { toHostError } from '../../../api/hostError';
import { asksForReview } from '../../processes/approval/ApprovalDock';
import { PersonalDataWarnings } from './PersonalDataWarnings';
import { RequestPublishDialog, type PublishArgs } from './RequestPublishDialog';
import { WithdrawDialog } from '../../approvals/WithdrawDialog';
import { useNames, useWantNames } from '../../processes/api/displayNames';
import { ProblemNotice } from './ProblemNotice';

export interface Action<TArgs = void> {
  start: (args: TArgs) => void;
  isPending: boolean;
  error: unknown;
}

/**
 * Why the user cannot change this version in the grid, in one line; undefined when they can. Only a draft is ever
 * edited, only by its author (the engine refuses anyone else), and only when the host allows cockpit drafts.
 */
export function editBlock(version: RuleSetVersion, session: { can: (permission: string) => boolean; cockpitDrafts: boolean; userId: string }): MessageKey | undefined {
  // What the version is comes first: a reviewer reads that a pending version cannot change, whoever they are.
  if (version.status === 'PENDING_APPROVAL') return 'editor.block.pending';
  if (version.status !== 'DRAFT') return 'editor.block.approved';
  if (!session.can('rules:author')) return 'editor.block.reader';
  if (!session.cockpitDrafts) return 'dock.draftsOff';
  if (version.createdBy !== session.userId) return 'editor.block.notAuthor';
  return undefined;
}

/**
 * Whether the header shows Edit draft for this version, enabled or disabled with its reason: always for a version
 * waiting for approval, so a reviewer reads whose turn it is; for a draft, to anyone who may author drafts here.
 */
export function showsEdit(version: RuleSetVersion, session: { can: (permission: string) => boolean; cockpitDrafts: boolean }): boolean {
  if (version.status === 'PENDING_APPROVAL') return true;
  return version.status === 'DRAFT' && session.can('rules:author') && session.cockpitDrafts;
}

/** Edit draft, disabled with its reason in one line beside it when the user cannot change this version. */
function EditButton({ version, onEdit }: { version: RuleSetVersion; onEdit: () => void }) {
  const { t } = useI18n();
  const session = useRulesSession();
  const reasonId = useId();
  const block = editBlock(version, session);
  return (
    <span className="rs-head__action">
      {block !== undefined ? <span id={reasonId} className="reason-line"><span aria-hidden="true">⊘</span> {t(block, { who: version.createdBy ?? '' })}</span> : null}
      <button type="button" className="btn" disabled={block !== undefined} onClick={onEdit} {...(block !== undefined ? { 'aria-describedby': reasonId } : {})}>
        <span aria-hidden="true">✎</span> {t('editor.edit')}
      </button>
    </span>
  );
}

/**
 * Actions beside the version picker: Start from this version, Edit draft, Request publish, Withdraw. One primary per screen: where the
 * view has its own focal action (the Simulate view's Run), Request publish is drawn secondary.
 */
export function HeaderActions({ version, onEdit, newDraft, requestPublish, onDiscarded, onWithdrawn, demoteRequest = false }: { version: RuleSetVersion; onEdit: () => void; newDraft: Action; requestPublish: Action<PublishArgs>; onDiscarded: (discarded: DiscardedVersion) => void; onWithdrawn: (message: string) => void; demoteRequest?: boolean }) {
  const { t } = useI18n();
  const session = useRulesSession();
  // Every edit is of a stored draft: it needs rules:author, and the host may keep changes in its release pipeline.
  const author = session.can('rules:author') && session.cockpitDrafts;
  const edit = showsEdit(version, session) ? <EditButton version={version} onEdit={onEdit} /> : null;
  const [asking, setAsking] = useState(false);
  const [withdrawing, setWithdrawing] = useState(false);
  if (version.status === 'DRAFT') {
    const canRequest = version.allowedActions.includes('request-publish');
    // When the host lists this rule set in lifecycle.require-simulation-report, the request goes through the report dialog.
    const needsReport = session.simulationReports && session.requiresReport(version.ruleSet);
    return (
      <>
        <DiscardDraft version={version} onDiscarded={onDiscarded} />
        {edit}
        {canRequest ? (
          <button type="button" className={demoteRequest ? 'btn' : 'btn btn--primary'} disabled={requestPublish.isPending} aria-busy={requestPublish.isPending}
            onClick={() => { if (needsReport) setAsking(true); else requestPublish.start({ report: undefined, effectiveFrom: undefined }); }}>
            {requestPublish.isPending && !asking ? t('dock.requesting') : t('dock.requestPublish')}
          </button>
        ) : null}
        {/* RLE-210: a start date is optional, so the one-click request stays and a quiet button opens the dialog. */}
        {canRequest && !needsReport ? (
          <button type="button" className="btn btn--quiet" disabled={requestPublish.isPending} onClick={() => { setAsking(true); }}>{t('dock.chooseDate')}</button>
        ) : null}
        {asking ? <RequestPublishDialog version={version} request={requestPublish} needsReport={needsReport} onClose={() => { setAsking(false); }} /> : null}
      </>
    );
  }
  // A published or retired version starts a new draft with the same content (RLE-062): the way back to an earlier version.
  if ((version.status === 'PUBLISHED' || version.status === 'RETIRED') && author) {
    return (
      <button type="button" className={version.status === 'PUBLISHED' ? 'btn btn--primary' : 'btn'} disabled={newDraft.isPending} aria-busy={newDraft.isPending} onClick={() => { newDraft.start(); }}>
        <span aria-hidden="true">＋</span> {newDraft.isPending ? t('editor.creating') : t('editor.newDraft')}
      </button>
    );
  }
  // The requester takes their own request back to a draft; the engine lists withdraw only for them (PD-089).
  if (version.status === 'PENDING_APPROVAL' && version.allowedActions.includes('withdraw')) {
    return (
      <>
        {/* The requester's one action; a disabled Edit draft beside it would only repeat that the version cannot change. */}
        <button type="button" className="btn" onClick={() => { setWithdrawing(true); }}>
          <span aria-hidden="true">↩</span> {t('approvals.withdraw')}
        </button>
        {withdrawing ? <WithdrawDialog request={{ ruleSet: version.ruleSet, version: version.version, lockVersion: version.lockVersion }} onClose={() => { setWithdrawing(false); }} onDone={(message) => { setWithdrawing(false); onWithdrawn(message); }} /> : null}
      </>
    );
  }
  return edit;
}

/** A line under the header when the host keeps rule changes in its release pipeline. */
export function DraftsOffNote() {
  const { t } = useI18n();
  const { can, cockpitDrafts } = useRulesSession();
  return can('rules:author') && !cockpitDrafts ? <p className="rs-head__note">{t('dock.draftsOff')}</p> : null;
}

/**
 * Whether what is on screen is what Approve would publish: `ok`; `checking` while the diff of the current content
 * loads; `changed` when the version moved on since the user opened it, or the diff on screen is of other content.
 */
export type Review = 'ok' | 'checking' | 'changed';

/** A scheduled date, when the version has one in the future; otherwise nothing (the header already says when). */
function TakesEffect({ effectiveFrom }: { effectiveFrom: string | null | undefined }) {
  const { t, locale } = useI18n();
  // Read once when the card opens: the hint must not flip while the approver is reading it.
  const [now] = useState(() => Date.now());
  const future = effectiveFrom !== null && effectiveFrom !== undefined && Date.parse(effectiveFrom) > now;
  const when = future ? formatDateTime(effectiveFrom, locale) : undefined;
  return when === undefined ? null : <p className="dock__hint">{t('decision.takesEffectOn', { when })}</p>;
}

/**
 * The four-eyes decision for a version waiting for approval. What the user may do comes from the engine's
 * allowedActions for this caller, and a disabled Approve always says why. A stale click still gets the
 * engine's answer, shown with its code behind Details. Docked, it sits at the bottom of the screen under the
 * side-by-side diff, so Approve stays in view while the approver reads what changed.
 */
function isVersionConflict(error: unknown): boolean {
  if (error === undefined || error === null) return false;
  const hostError = toHostError(error);
  return hostError.kind === 'problem' && hostError.code === 'VERSION_CONFLICT';
}

/** What Approve sends: the comment, and the exact { code, input } pairs the approver accepted (PD-104, RLE-053). */
export interface ApproveArgs { comment: string; acknowledgedWarnings: AcknowledgedWarning[] }

/**
 * The set the engine needs accepted, from a 409 WARNINGS_NOT_ACKNOWLEDGED (its `warnings`, possibly empty);
 * undefined when the error is not that refusal.
 */
function refusedSet(error: unknown): AcknowledgedWarning[] | undefined {
  if (error === undefined || error === null) return undefined;
  const hostError = toHostError(error);
  if (hostError.kind !== 'problem' || hostError.code !== 'WARNINGS_NOT_ACKNOWLEDGED') return undefined;
  return readPendingWarnings(hostError.members?.['warnings']) ?? [];
}

export function DecisionCard({ version, approve, reject, onReload, docked = false, review = 'ok', onReviewAgain }: {
  version: RuleSetVersion; approve: Action<ApproveArgs>; reject: Action<string>; onReload: () => void; docked?: boolean; review?: Review; onReviewAgain?: () => void;
}) {
  const { t } = useI18n();
  const { userId } = useRulesSession();
  const [text, setText] = useState('');
  // The tick remembers the refusal on screen when it was given: a new refusal clears it, so the approver reads the
  // warnings again (the version is read again with them) and ticks again.
  const [tick, setTick] = useState<{ on: boolean; refusal: unknown }>({ on: false, refusal: undefined });
  const acceptId = useId();
  // What needs accepting: after a refusal, exactly the set the engine returned; otherwise the warnings the engine
  // marks requiresAcknowledgement (RLE-053). The cockpit reads the engine's answer and never works it out itself.
  const refusal = refusedSet(approve.error);
  const refused = refusal !== undefined;
  const personalData = version.warnings.filter((w) => !isUnclassified(w));
  const toAccept: AcknowledgedWarning[] = refusal ?? personalData
    .filter((w) => w.requiresAcknowledgement)
    .map(({ code, input }) => ({ code, input }));
  const shown = refusal !== undefined && refusal.length > 0 ? refusal : personalData;
  const accepted = tick.on && (!refused || tick.refusal === approve.error);
  const setAccepted = (on: boolean) => { setTick({ on, refusal: refused ? approve.error : undefined }); };
  const textId = useId();
  const reasonId = useId();
  const hintId = useId();
  const allowed = new Set(version.allowedActions);
  const self = version.requestedBy === userId ? 'dock.selfRequested' : version.createdBy === userId ? 'dock.selfAuthored' : undefined;
  const mayApprove = allowed.has('approve') && self === undefined;
  const needsAcceptance = mayApprove && toAccept.length > 0 && !accepted;
  const error: unknown = approve.error ?? reject.error;
  // After the engine said the content moved on, the same approval is never sent again: the reviewer reloads first.
  const conflict = isVersionConflict(error);
  const stale = !conflict && asksForReview(error);
  // A 409 VERSION_CONFLICT reads as "changed while you had it open": the version is read again and the approver checks it.
  const reviewNow: Review = conflict ? 'changed' : review;
  const canApprove = mayApprove && reviewNow === 'ok' && !needsAcceptance && !stale;
  // PD-089: a requester never rejects their own request; withdrawing is its own action (:withdraw).
  const canReject = allowed.has('reject') && self === undefined;
  // A disabled Approve says why; while the current diff loads it is only busy for a moment.
  const unavailable = canApprove || needsAcceptance || stale || (mayApprove && reviewNow === 'checking') ? undefined : self ?? (mayApprove ? 'decision.changed' : 'dock.approveUnavailable');
  const busy = approve.isPending || reject.isPending;
  const tip: MessageKey | undefined = unavailable === 'dock.selfRequested' ? 'dock.tip.selfRequested' : unavailable === 'dock.selfAuthored' ? 'dock.tip.selfAuthored' : unavailable === 'dock.approveUnavailable' ? 'dock.tip.unavailable' : undefined;
  return (
    <section className={docked ? 'card decision decision--dock' : 'card decision'} aria-labelledby="decision-title">
      <h2 id="decision-title" className="rules-card-title">{t('decision.title')}</h2>
      {!mayApprove && !canReject && self === undefined ? (
        <p className="dock__note">{t('dock.waiting')}</p>
      ) : (
        <>
          <div className="decision__body">
            <PersonalDataWarnings warnings={shown} definition={version.definition}
              acceptance={mayApprove && toAccept.length > 0 ? { checked: accepted, onChange: setAccepted, needed: refused && !accepted, id: acceptId, inputs: [...new Set(toAccept.map((w) => w.input))] } : undefined} />
            {unavailable === 'decision.changed' ? (
              <p className="gate__reason">
                <span aria-hidden="true">⊘</span> <span id={reasonId}>{t(unavailable, { version: String(version.version) })}</span>
                {onReviewAgain ? (
                  <> <button type="button" className="linkbtn hit" onClick={onReviewAgain}>{t('decision.reviewAgain')}</button></>
                ) : null}
              </p>
            ) : null}
            {/* Why Approve is off is a short tooltip on the button, and the full sentence for a screen reader. */}
            {unavailable !== undefined && unavailable !== 'decision.changed' ? <span id={reasonId} className="sr-only">{t(unavailable, { version: String(version.version) })}</span> : null}
            {mayApprove || canReject ? (
              <label className="field" htmlFor={textId}>
                <span className="field__label">{t('decision.comment')}</span>
                <textarea id={textId} className="field__input" rows={docked ? 1 : 2} value={text} placeholder={t('decision.commentHint')}
                  aria-describedby={canReject ? hintId : undefined} onChange={(e) => { setText(e.target.value); }} />
              </label>
            ) : null}
          </div>
          <div className="decision__actions">
            <div className="gate__buttons">
              <span className={tip !== undefined ? 'has-tip has-tip--above' : undefined} {...(tip !== undefined ? { 'data-tip': t(tip), tabIndex: 0 } : {})}>
                <button type="button" className="btn btn--primary btn--ok" disabled={!canApprove || busy} aria-busy={approve.isPending || review === 'checking'}
                  {...(unavailable !== undefined ? { 'aria-describedby': reasonId } : needsAcceptance ? { 'aria-describedby': acceptId } : {})}
                  onClick={() => { approve.start({ comment: text.trim(), acknowledgedWarnings: accepted ? toAccept : [] }); }}>
                  <span aria-hidden="true">✓</span> {approve.isPending ? t('dock.approving') : t('dock.approve')}
                </button>
              </span>
              {canReject ? (
                <button type="button" className="btn btn--danger-quiet" disabled={text.trim() === '' || busy} aria-describedby={hintId} onClick={() => { reject.start(text.trim()); }}>
                  {reject.isPending ? t('dock.rejecting') : t('dock.reject')}
                </button>
              ) : null}
            </div>
            {/* The placeholder already says Reject needs a reason; the hint stays as Reject's spoken description. */}
            {canReject ? <p id={hintId} className="sr-only">{t('dock.reasonHint')}</p> : null}
            {mayApprove ? <TakesEffect effectiveFrom={version.effectiveFrom} /> : null}
          </div>
        </>
      )}
      {error !== undefined && error !== null && !conflict && !(refused && error === approve.error) ? <ProblemNotice error={error} onReload={onReload} /> : null}
    </section>
  );
}

function Step({ title, when }: { title: string; when: string | undefined }) {
  return (
    <li className="timeline__step">
      <span className="timeline__dot" aria-hidden="true">✓</span>
      <span className="timeline__title">{title}</span>
      {when !== undefined ? <span className="timeline__when tnum">{when}</span> : null}
    </li>
  );
}

/** Who drafted, requested and approved the version, and since when it applies. The content hash is in History. */
/** `importedFrom` is the file this version was imported from in this session, when the user imported it here. */
export function DetailsPanel({ version, importedFrom }: { version: RuleSetVersion; importedFrom?: string | undefined }) {
  const { t, locale } = useI18n();
  // The people who drafted, requested and approved read by name, as the header does, never by login.
  const { user } = useNames();
  useWantNames({ users: [version.createdBy, version.requestedBy, version.approvedBy].filter((id): id is string => typeof id === 'string' && id !== '') });
  const policy = version.hitPolicy.toUpperCase();
  const policyKey = policy === 'UNIQUE' ? 'policy.UNIQUE' : policy === 'COLLECT' ? 'policy.COLLECT' : 'policy.FIRST';
  return (
    <div className="details">
      <ol className="timeline">
        {version.createdBy ? <Step title={importedFrom !== undefined ? t('details.imported', { who: user(version.createdBy), file: importedFrom }) : t('details.drafted', { who: user(version.createdBy) })} when={formatDateTime(version.createdAt, locale)} /> : null}
        {version.requestedBy ? <Step title={t('details.requested', { who: user(version.requestedBy) })} when={formatDateTime(version.requestedAt, locale)} /> : null}
        {version.approvedBy ? <Step title={t('details.approved', { who: user(version.approvedBy) })} when={formatDateTime(version.approvedAt, locale)} /> : null}
        {version.effectiveFrom ? <Step title={t('details.inEffect')} when={t('details.from', { when: formatDateTime(version.effectiveFrom, locale) ?? '' })} /> : null}
      </ol>
      <dl className="dock__facts">
        <div>
          <dt>{t('details.hitPolicy')}</dt>
          <dd>{t(policyKey)}</dd>
        </div>
        <div>
          <dt>{t('details.rows')}</dt>
          <dd className="tnum">{version.definition.rules.length}</dd>
        </div>
      </dl>
    </div>
  );
}

/** A save refused because someone else saved first: what they changed, and the way to their version. */
export interface DockConflict {
  /** The cells they changed ("R3 Fee"), or empty while their version is read or when no cell differs. */
  cells: string;
  /** Whether their version has been read, so Load the latest version can replace the edits. */
  ready: boolean;
  onLoad: () => void;
}

/**
 * The save dock, always at the bottom of the editor and aligned to the content: what is unsaved, what is left to fix,
 * whether the engine is checking, Discard and Save draft. A disabled Save says why once, beside it. After a conflict
 * the dock carries the warning and Load the latest version, so the grid above never moves.
 */
export function EditDock({ unsaved, toFix, checking, saving, blocked, conflict, onSave, onDiscard, onViewChanges }: {
  unsaved: number; toFix: number; checking: boolean; saving: boolean; blocked: MessageKey | undefined; conflict?: DockConflict | undefined;
  onSave: () => void; onDiscard: () => void; onViewChanges: () => void;
}) {
  const { t } = useI18n();
  const reasonId = useId();
  const conflictId = useId();
  const unsavedText = unsaved === 1 ? t('editor.unsavedOne') : unsaved > 1 ? t('editor.unsavedCount', { count: String(unsaved) }) : undefined;
  // With nothing changed the status stays empty: the reason beside Save already says there is nothing to save.
  const status: ReactNode[] = [
    unsavedText !== undefined ? <span key="unsaved"><span className="edit-dock__dot" aria-hidden="true" /> {unsavedText}</span> : null,
    toFix > 0 ? <span key="fix" className="edit-dock__fix">{t('editor.toFix', { count: String(toFix) })}</span> : null,
    checking ? <span key="checking">{t('editor.checking')}</span> : null,
  ].filter((part) => part !== null);
  // The reason beside Save is the one line that explains a disabled Save; with a conflict the warning line does.
  const reason = blocked !== undefined && !saving && conflict === undefined ? blocked : undefined;
  const describedBy = conflict !== undefined ? conflictId : reason !== undefined ? reasonId : undefined;
  return (
    <div className={conflict !== undefined ? 'edit-dock edit-dock--conflict' : 'edit-dock'} role="region" aria-label={t('editor.dockLabel')}>
      {conflict !== undefined ? (
        <div className="edit-dock__conflict" role="alert">
          <p id={conflictId} className="edit-dock__conflict-text">
            <span aria-hidden="true" className="edit-dock__warn">▲</span> <strong>{t('editor.conflict.title')}</strong>{' '}
            {conflict.cells !== '' ? t('editor.conflict.cells', { cells: conflict.cells }) : t('editor.conflict.body')}
          </p>
          <button type="button" className="btn" disabled={!conflict.ready} aria-busy={!conflict.ready} onClick={conflict.onLoad}>
            {conflict.ready ? t('editor.conflict.load') : t('editor.conflict.loading')}
          </button>
        </div>
      ) : null}
      <div className="edit-dock__row">
        <p className="edit-dock__status" role="status">
          {status.flatMap((part, index) => (index === 0 ? [part] : [' · ', part]))}
          {unsaved > 0 ? <button type="button" className="linkbtn hit edit-dock__view" onClick={onViewChanges}>{t('editor.viewChanges')}</button> : null}
        </p>
        {reason !== undefined ? <p id={reasonId} className="reason-line edit-dock__reason"><span aria-hidden="true">⊘</span> {t(reason)}</p> : null}
        <button type="button" className="btn btn--ghost" disabled={saving} onClick={onDiscard}>{t('editor.cancel')}</button>
        <button type="button" className="btn btn--primary" disabled={blocked !== undefined || saving} aria-busy={saving}
          {...(describedBy !== undefined && !saving ? { 'aria-describedby': describedBy } : {})} onClick={onSave}>
          {saving ? t('editor.saving') : t('editor.save')}
        </button>
      </div>
    </div>
  );
}
