import { useId, useState, type ReactNode } from 'react';
import type { RuleSetVersion } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import { useRulesSession } from '../api/rulesContext';
import { formatDateTime } from '../model/dates';
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
 * Who may do what next is decided by the engine. This release returns no allowed actions, so the dock reads
 * the version's author and requester, which the engine itself names as unable to approve, and the user's
 * permissions from /me. A stale click still gets the engine's 403, shown with its code.
 */
function FourEyesGate({ version, approve }: { version: RuleSetVersion; approve: Action<string> }) {
  const { t } = useI18n();
  const { userId, can } = useRulesSession();
  const [comment, setComment] = useState('');
  const commentId = useId();
  const reasonId = useId();
  const self = version.requestedBy === userId ? 'dock.selfRequested' : version.createdBy === userId ? 'dock.selfAuthored' : undefined;
  if (!can('rules:approve') && self === undefined) return <p className="dock__note">{t('dock.waiting')}</p>;
  return (
    <div className="gate">
      {self === undefined ? (
        <label className="field" htmlFor={commentId}>
          <span className="field__label">{t('dock.comment')}</span>
          <textarea id={commentId} className="field__input" rows={2} value={comment} onChange={(e) => { setComment(e.target.value); }} />
        </label>
      ) : (
        <p id={reasonId} className="gate__reason">
          <span aria-hidden="true">⊘</span> {t(self)}
        </p>
      )}
      <div className="gate__buttons">
        <button
          type="button"
          className="btn btn--primary btn--ok"
          disabled={self !== undefined || approve.isPending}
          {...(self !== undefined ? { 'aria-describedby': reasonId } : {})}
          onClick={() => { approve.start(comment.trim()); }}
        >
          {approve.isPending ? t('dock.approving') : t('dock.approve')}
        </button>
        {can('rules:approve') ? (
          <button type="button" className="btn btn--danger-quiet" disabled title={t('dock.rejectUnavailable')} aria-describedby={`${reasonId}-reject`}>
            {t('dock.reject')}
          </button>
        ) : null}
      </div>
      {can('rules:approve') ? <p id={`${reasonId}-reject`} className="dock__hint">{t('dock.rejectUnavailable')}</p> : null}
    </div>
  );
}

function Actions({ version, editing, onEdit, requestPublish, approve }: Pick<DockProps, 'version' | 'editing' | 'onEdit' | 'requestPublish' | 'approve'>): ReactNode {
  const { t } = useI18n();
  const { can } = useRulesSession();
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
    case 'DRAFT':
      if (!can('rules:author')) return <p className="dock__note">{t('dock.readOnly')}</p>;
      return (
        <div className="dock__buttons">
          <button type="button" className="btn btn--primary" disabled={requestPublish.isPending} onClick={() => { requestPublish.start(); }}>
            {requestPublish.isPending ? t('dock.requesting') : t('dock.requestPublish')}
          </button>
          <button type="button" className="btn" onClick={onEdit}>{t('editor.edit')}</button>
        </div>
      );
    case 'PENDING_APPROVAL':
      return <FourEyesGate version={version} approve={approve} />;
    case 'PUBLISHED':
      return (
        <div className="dock__buttons">
          {can('rules:author') ? <button type="button" className="btn btn--primary" onClick={onEdit}>{t('editor.newDraft')}</button> : null}
          <p className="dock__hint">{t('dock.publishedNote')}</p>
        </div>
      );
    case 'RETIRED':
      return <p className="dock__note">{t('dock.retiredNote')}</p>;
  }
}

export function ActionDock(props: DockProps) {
  const { t, locale } = useI18n();
  const { version, latestVersion, notice, onOpen, onReload, editing, requestPublish, approve } = props;
  const error: unknown = editing ? undefined : requestPublish.error ?? approve.error;
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
    </section>
  );
}
