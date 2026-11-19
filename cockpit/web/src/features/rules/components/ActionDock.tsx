import { useId, useState, type ReactNode } from 'react';
import type { RuleSetVersion } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import { useRulesSession } from '../api/rulesContext';
import { formatDateTime } from '../model/dates';
import { HistoryList } from './HistoryList';
import { ProblemNotice } from './ProblemNotice';
import { StatusBadge } from './StatusBadge';

export interface Action<TArgs = void> {
  start: (args: TArgs) => void;
  isPending: boolean;
  error: unknown;
}

export interface DockEditing {
  dirty: boolean;
  saving: boolean;
  error: unknown;
  onSave: () => void;
  onCancel: () => void;
}

interface DockProps {
  version: RuleSetVersion;
  latestVersion: number;
  editing: DockEditing | undefined;
  notice: string | undefined;
  onEdit: () => void;
  onOpen: (version: number) => void;
  onReload: () => void;
  requestPublish: Action;
  approve: Action<string>;
  reject: Action<string>;
}

function Fact({ label, value }: { label: string; value: string | null | undefined }) {
  if (value === null || value === undefined) return null;
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

/**
 * The four-eyes gate. What the user may do comes from the engine's allowedActions for this caller, and a
 * disabled Approve always carries its reason. A stale click still gets the engine's answer, shown with its code.
 */
function FourEyesGate({ version, approve, reject }: Pick<DockProps, 'version' | 'approve' | 'reject'>) {
  const { t } = useI18n();
  const { userId } = useRulesSession();
  const [text, setText] = useState('');
  const textId = useId();
  const reasonId = useId();
  const hintId = useId();
  const allowed = new Set(version.allowedActions);
  const self = version.requestedBy === userId ? 'dock.selfRequested' : version.createdBy === userId ? 'dock.selfAuthored' : undefined;
  const canApprove = allowed.has('approve') && self === undefined;
  // PD-089: a requester never rejects their own request; withdrawing gets its own action (:withdraw).
  const canReject = allowed.has('reject') && self === undefined;
  if (!canApprove && !canReject && self === undefined) return <p className="dock__note">{t('dock.waiting')}</p>;
  // A disabled Approve always says why: four eyes for the maker, otherwise the engine withheld it.
  const unavailable = canApprove ? undefined : self ?? 'dock.approveUnavailable';
  const busy = approve.isPending || reject.isPending;
  return (
    <div className="gate">
      {unavailable !== undefined ? (
        <p id={reasonId} className="gate__reason">
          <span aria-hidden="true">⊘</span> {t(unavailable)}
        </p>
      ) : null}
      {canApprove || canReject ? (
        <label className="field" htmlFor={textId}>
          <span className="field__label">{t('dock.reason')}</span>
          <textarea id={textId} className="field__input" rows={2} value={text} aria-describedby={canReject ? hintId : undefined} onChange={(e) => { setText(e.target.value); }} />
        </label>
      ) : null}
      <div className="gate__buttons">
        <button
          type="button"
          className="btn btn--primary btn--ok"
          disabled={!canApprove || busy}
          {...(unavailable !== undefined ? { 'aria-describedby': reasonId } : {})}
          onClick={() => { approve.start(text.trim()); }}
        >
          {approve.isPending ? t('dock.approving') : t('dock.approve')}
        </button>
        {canReject ? (
          <button type="button" className="btn btn--danger-quiet" disabled={text.trim() === '' || busy} aria-describedby={hintId} onClick={() => { reject.start(text.trim()); }}>
            {reject.isPending ? t('dock.rejecting') : t('dock.reject')}
          </button>
        ) : null}
      </div>
      {canReject ? <p id={hintId} className="dock__hint">{t('dock.reasonHint')}</p> : null}
    </div>
  );
}

function Actions({ version, editing, onEdit, requestPublish, approve, reject }: Pick<DockProps, 'version' | 'editing' | 'onEdit' | 'requestPublish' | 'approve' | 'reject'>): ReactNode {
  const { t } = useI18n();
  const { can, cockpitDrafts } = useRulesSession();
  // Editing imports a new draft: it needs rules:author, and the host may keep changes in its release pipeline.
  const canEdit = can('rules:author') && cockpitDrafts;
  const draftsOff = can('rules:author') && !cockpitDrafts ? <p className="dock__hint">{t('dock.draftsOff')}</p> : null;
  if (editing) {
    return (
      <div className="dock__buttons">
        <button type="button" className="btn btn--primary" disabled={!editing.dirty || editing.saving} onClick={editing.onSave}>
          {editing.saving ? t('editor.saving') : t('editor.save')}
        </button>
        <button type="button" className="btn btn--quiet" disabled={editing.saving} onClick={editing.onCancel}>{t('editor.cancel')}</button>
        <p className="dock__hint" role="status">{editing.dirty ? t('editor.unsaved') : t('editor.noChanges')}</p>
        <p className="dock__hint">{t('editor.saveNote')}</p>
      </div>
    );
  }
  switch (version.status) {
    case 'DRAFT': {
      const canRequest = version.allowedActions.includes('request-publish');
      if (!canRequest && !canEdit) return draftsOff ?? <p className="dock__note">{t('dock.readOnly')}</p>;
      return (
        <div className="dock__buttons">
          {canRequest ? (
            <button type="button" className="btn btn--primary" disabled={requestPublish.isPending} onClick={() => { requestPublish.start(); }}>
              {requestPublish.isPending ? t('dock.requesting') : t('dock.requestPublish')}
            </button>
          ) : null}
          {canEdit ? <button type="button" className="btn" onClick={onEdit}>{t('editor.edit')}</button> : null}
          {draftsOff}
        </div>
      );
    }
    case 'PENDING_APPROVAL':
      return <FourEyesGate version={version} approve={approve} reject={reject} />;
    case 'PUBLISHED':
      return (
        <div className="dock__buttons">
          {canEdit ? <button type="button" className="btn btn--primary" onClick={onEdit}>{t('editor.newDraft')}</button> : null}
          {draftsOff ?? <p className="dock__hint">{t('dock.publishedNote')}</p>}
        </div>
      );
    case 'RETIRED':
      return <p className="dock__note">{t('dock.retiredNote')}</p>;
  }
}

export function ActionDock(props: DockProps) {
  const { t, locale } = useI18n();
  const { version, latestVersion, notice, onOpen, onReload, editing, requestPublish, approve, reject } = props;
  const error: unknown = editing ? undefined : requestPublish.error ?? approve.error ?? reject.error;
  return (
    <section className="card dock" aria-labelledby="dock-title">
      <div className="dock__head">
        <h2 id="dock-title" className="card__title">{t('dock.title')}</h2>
        <StatusBadge status={version.status} />
      </div>
      <dl className="dock__facts">
        <Fact label={t('dock.author')} value={version.createdBy} />
        <Fact label={t('dock.created')} value={formatDateTime(version.createdAt, locale)} />
        <Fact label={t('dock.requester')} value={version.requestedBy} />
        <Fact label={t('dock.approver')} value={version.approvedBy} />
        <Fact label={t('dock.effective')} value={formatDateTime(version.effectiveFrom, locale)} />
      </dl>
      <Actions {...props} />
      {error !== undefined && error !== null ? <ProblemNotice error={error} onReload={onReload} /> : null}
      <p className="dock__live" role="status" aria-live="polite">{notice ?? ''}</p>
      {!editing && version.version < latestVersion ? (
        <p className="dock__hint">
          {t('dock.newer')}{' '}
          <button type="button" className="linkbtn hit" onClick={() => { onOpen(latestVersion); }}>
            {t('dock.openVersion', { version: String(latestVersion) })}
          </button>
        </p>
      ) : null}
      {!editing ? <HistoryList ruleSet={version.ruleSet} version={version.version} /> : null}
    </section>
  );
}
