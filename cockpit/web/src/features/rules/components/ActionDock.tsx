import { useId, useState } from 'react';
import type { RuleSetVersion } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import { useRulesSession } from '../api/rulesContext';
import { formatDateTime } from '../model/dates';
import { ProblemNotice } from './ProblemNotice';

export interface Action<TArgs = void> {
  start: (args: TArgs) => void;
  isPending: boolean;
  error: unknown;
}

/** Actions beside the version picker: New draft, Edit draft, Request publish. One primary per screen. */
export function HeaderActions({ version, onEdit, requestPublish }: { version: RuleSetVersion; onEdit: () => void; requestPublish: Action }) {
  const { t } = useI18n();
  const { can, cockpitDrafts } = useRulesSession();
  // Editing imports a new draft: it needs rules:author, and the host may keep changes in its release pipeline.
  const canEdit = can('rules:author') && cockpitDrafts;
  if (version.status === 'DRAFT') {
    const canRequest = version.allowedActions.includes('request-publish');
    return (
      <>
        {canEdit ? <button type="button" className="btn" onClick={onEdit}><span aria-hidden="true">✎</span> {t('editor.edit')}</button> : null}
        {canRequest ? (
          <button type="button" className="btn btn--primary" disabled={requestPublish.isPending} aria-busy={requestPublish.isPending} onClick={() => { requestPublish.start(); }}>
            {requestPublish.isPending ? t('dock.requesting') : t('dock.requestPublish')}
          </button>
        ) : null}
      </>
    );
  }
  if (version.status === 'PUBLISHED' && canEdit) {
    return <button type="button" className="btn btn--primary" onClick={onEdit}><span aria-hidden="true">＋</span> {t('editor.newDraft')}</button>;
  }
  return null;
}

/** A line under the header when the host keeps rule changes in its release pipeline. */
export function DraftsOffNote() {
  const { t } = useI18n();
  const { can, cockpitDrafts } = useRulesSession();
  return can('rules:author') && !cockpitDrafts ? <p className="rs-head__note">{t('dock.draftsOff')}</p> : null;
}

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
export function DecisionCard({ version, approve, reject, onReload, docked = false }: { version: RuleSetVersion; approve: Action<string>; reject: Action<string>; onReload: () => void; docked?: boolean }) {
  const { t } = useI18n();
  const { userId } = useRulesSession();
  const [text, setText] = useState('');
  const textId = useId();
  const reasonId = useId();
  const hintId = useId();
  const allowed = new Set(version.allowedActions);
  const self = version.requestedBy === userId ? 'dock.selfRequested' : version.createdBy === userId ? 'dock.selfAuthored' : undefined;
  const canApprove = allowed.has('approve') && self === undefined;
  // PD-089: a requester never rejects their own request; withdrawing is its own action (:withdraw).
  const canReject = allowed.has('reject') && self === undefined;
  const error: unknown = approve.error ?? reject.error;
  const unavailable = canApprove ? undefined : self ?? 'dock.approveUnavailable';
  const busy = approve.isPending || reject.isPending;
  return (
    <section className={docked ? 'card decision decision--dock' : 'card decision'} aria-labelledby="decision-title">
      <h2 id="decision-title" className="rules-card-title">{t('decision.title')}</h2>
      {!canApprove && !canReject && self === undefined ? (
        <p className="dock__note">{t('dock.waiting')}</p>
      ) : (
        <>
          <div className="decision__body">
            {unavailable !== undefined ? (
              <p id={reasonId} className="gate__reason">
                <span aria-hidden="true">⊘</span> {t(unavailable)}
              </p>
            ) : null}
            {canApprove || canReject ? (
              <label className="field" htmlFor={textId}>
                <span className="field__label">{t('decision.comment')}</span>
                <textarea id={textId} className="field__input" rows={docked ? 1 : 2} value={text} placeholder={t('decision.commentHint')}
                  aria-describedby={canReject ? hintId : undefined} onChange={(e) => { setText(e.target.value); }} />
              </label>
            ) : null}
          </div>
          <div className="decision__actions">
            <div className="gate__buttons">
              <button type="button" className="btn btn--primary btn--ok" disabled={!canApprove || busy} aria-busy={approve.isPending}
                {...(unavailable !== undefined ? { 'aria-describedby': reasonId } : {})} onClick={() => { approve.start(text.trim()); }}>
                <span aria-hidden="true">✓</span> {approve.isPending ? t('dock.approving') : t('dock.approve')}
              </button>
              {canReject ? (
                <button type="button" className="btn btn--danger-quiet" disabled={text.trim() === '' || busy} aria-describedby={hintId} onClick={() => { reject.start(text.trim()); }}>
                  {reject.isPending ? t('dock.rejecting') : t('dock.reject')}
                </button>
              ) : null}
            </div>
            {/* The placeholder already says Reject needs a reason; the hint stays as Reject's spoken description. */}
            {canReject ? <p id={hintId} className="sr-only">{t('dock.reasonHint')}</p> : null}
            {canApprove ? <TakesEffect effectiveFrom={version.effectiveFrom} /> : null}
          </div>
        </>
      )}
      {error !== undefined && error !== null ? <ProblemNotice error={error} onReload={onReload} /> : null}
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

/** Who drafted, requested and approved the version, since when it applies, and its content hash. */
export function DetailsPanel({ version }: { version: RuleSetVersion }) {
  const { t, locale } = useI18n();
  const policy = version.hitPolicy.toUpperCase();
  const policyKey = policy === 'UNIQUE' ? 'policy.UNIQUE' : policy === 'COLLECT' ? 'policy.COLLECT' : 'policy.FIRST';
  return (
    <div className="details">
      <ol className="timeline">
        {version.createdBy ? <Step title={t('details.drafted', { who: version.createdBy })} when={formatDateTime(version.createdAt, locale)} /> : null}
        {version.requestedBy ? <Step title={t('details.requested', { who: version.requestedBy })} when={formatDateTime(version.requestedAt, locale)} /> : null}
        {version.approvedBy ? <Step title={t('details.approved', { who: version.approvedBy })} when={formatDateTime(version.approvedAt, locale)} /> : null}
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
        <div>
          <dt>{t('details.hash')}</dt>
          <dd><span className="mono" title={version.canonicalHash}>{version.canonicalHash.slice(0, 12)}</span></dd>
        </div>
      </dl>
    </div>
  );
}

/** The sticky save dock at the bottom of the editor: what is unsaved, what is left to fix, Discard and Save draft. */
export function EditDock({ unsaved, toFix, saving, onSave, onDiscard, onViewChanges }: { unsaved: number; toFix: number; saving: boolean; onSave: () => void; onDiscard: () => void; onViewChanges: () => void }) {
  const { t } = useI18n();
  return (
    <div className="edit-dock" role="region" aria-label={t('editor.save')}>
      <p className="edit-dock__status" role="status">
        {unsaved > 0 ? <span className="edit-dock__dot" aria-hidden="true" /> : null}
        {unsaved === 1 ? t('editor.unsavedOne') : unsaved > 0 ? t('editor.unsavedCount', { count: String(unsaved) }) : t('editor.noChanges')}
        {toFix > 0 ? ` · ${t('editor.toFix', { count: String(toFix) })}` : ''}
        {unsaved > 0 ? <button type="button" className="linkbtn hit edit-dock__view" onClick={onViewChanges}>{t('editor.viewChanges')}</button> : null}
      </p>
      <button type="button" className="btn btn--ghost" disabled={saving} onClick={onDiscard}>{t('editor.cancel')}</button>
      <button type="button" className="btn btn--primary" disabled={unsaved === 0 || saving} aria-busy={saving} onClick={onSave}>
        {saving ? t('editor.saving') : t('editor.save')}
      </button>
    </div>
  );
}
