import { useId, useState } from 'react';
import { toHostError } from '../../../api/hostError';
import type { DefinitionVersion } from '../../../api/processes/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import type { MessageKey } from '../../../i18n/en';
import { ProblemNotice } from '../../rules/components/ProblemNotice';
import { formatDateTime } from '../../rules/model/dates';
import { shortHash, type ApprovalBlock } from '../model/graphDiff';

/** What the dock may start: approve with a comment, reject with a reason. Both wait for the engine's answer. */
export interface DockAction<TArgs> {
  start: (args: TArgs) => void;
  isPending: boolean;
  error: unknown;
}

const PROBLEM_CODES = [
  'SELF_APPROVAL_FORBIDDEN', 'CONTENT_CHANGED', 'CHANGE_SET_TRUNCATED', 'CONTENT_HASH_REQUIRED', 'VERSION_SUPERSEDED',
  'VERSION_CONFLICT', 'INVALID_STATE', 'GUARDRAIL_CONTRACT_INVALID', 'VALIDATION_FAILED', 'PERMISSION_DENIED',
] as const;

/** The sentence for a refused approval or rejection, in the words of this screen; undefined for any other answer. */
export function refusalMessage(error: unknown): MessageKey | undefined {
  if (error === undefined || error === null) return undefined;
  const hostError = toHostError(error);
  if (hostError.kind !== 'problem') return undefined;
  const code = PROBLEM_CODES.find((known) => known === hostError.code);
  return code !== undefined ? `approvalProblem.${code}` : undefined;
}

/** The refusal codes after which the content on screen is no longer the content to approve: review again. */
export function asksForReview(error: unknown): boolean {
  if (error === undefined || error === null) return false;
  const hostError = toHostError(error);
  return hostError.kind === 'problem' && (hostError.code === 'CONTENT_CHANGED' || hostError.code === 'VERSION_CONFLICT');
}

/** The four-eyes blocks under which Reject is not offered either: the reviewer is not a second person here. */
const NO_DECISION: readonly ApprovalBlock[] = ['waiting', 'self-requested', 'self-created'];

/**
 * The four-eyes decision on a definition version waiting for approval (CKP-024), docked at the bottom of the
 * screen so Approve stays in view while the change is read. Approve sends the hash of the content the user
 * reviewed; when it is not offered, one line says why beside the disabled button. The engine checks everything
 * again and its refusal is shown in this screen's words, with the code behind Details.
 */
export function ApprovalDock({ version, block, hash, approve, reject, onReviewAgain, onReload }: {
  version: DefinitionVersion;
  block: ApprovalBlock | undefined;
  /** The definitionHash the approval names: the content on screen. */
  hash: string | undefined;
  approve: DockAction<{ comment: string }>;
  reject: DockAction<{ reason: string }>;
  /** The user confirms they checked the content now on screen. */
  onReviewAgain: () => void;
  /** Read the version and its change list again, after the engine said they moved on. */
  onReload: () => void;
}) {
  const { t, locale } = useI18n();
  const [text, setText] = useState('');
  const textId = useId();
  const reasonId = useId();
  const hintId = useId();
  const decides = block === undefined || !NO_DECISION.includes(block);
  const busy = approve.isPending || reject.isPending;
  const error: unknown = approve.error ?? reject.error;
  // After the engine said the content moved on, the same approval is never sent again: the user reloads first.
  const stale = asksForReview(error);
  const canApprove = block === undefined && !stale;
  const problemId = useId();
  const message = refusalMessage(error);
  // Read once when the dock opens: the hint must not flip while the approver is reading it.
  const [now] = useState(() => Date.now());
  const effectiveFrom = version.effectiveFrom ?? undefined;
  const when = effectiveFrom !== undefined && Date.parse(effectiveFrom) > now ? formatDateTime(effectiveFrom, locale) : undefined;
  const reason = block !== undefined && block !== 'checking' ? t(`approval.block.${block}`, { version: String(version.version) }) : undefined;
  return (
    <section className="card decision decision--dock pdock" aria-labelledby="pdock-title">
      <h2 id="pdock-title" className="rules-card-title">{t('decision.title')}</h2>
      <div className="decision__body">
        {reason !== undefined ? (
          <p className="pdock__reason">
            <span aria-hidden="true">⊘</span> <span id={reasonId}>{reason}</span>
            {block === 'changed' ? <> <button type="button" className="linkbtn hit" onClick={onReviewAgain}>{t('decision.reviewAgain')}</button></> : null}
          </p>
        ) : null}
        {decides ? (
          <label className="field" htmlFor={textId}>
            <span className="field__label">{t('decision.comment')}</span>
            <textarea id={textId} className="field__input" rows={1} value={text} placeholder={t('decision.commentHint')} maxLength={2000}
              aria-describedby={hintId} onChange={(event) => { setText(event.target.value); }} />
          </label>
        ) : null}
      </div>
      <div className="decision__actions">
        <div className="gate__buttons">
          {decides ? (
            <button type="button" className="btn btn--danger-quiet" disabled={text.trim() === '' || busy} aria-describedby={hintId} onClick={() => { reject.start({ reason: text.trim() }); }}>
              {reject.isPending ? t('approval.rejecting') : t('approval.reject')}
            </button>
          ) : null}
          <button type="button" className="btn btn--primary" disabled={!canApprove || busy} aria-busy={approve.isPending || block === 'checking'}
            {...(reason !== undefined ? { 'aria-describedby': reasonId } : stale ? { 'aria-describedby': problemId } : {})}
            onClick={() => { approve.start({ comment: text.trim() }); }}>
            <span aria-hidden="true">✓</span> {approve.isPending ? t('approval.approving') : t('approval.approve')}
          </button>
        </div>
        {canApprove && hash !== undefined ? <p className="dock__hint">{t('approval.content')} <span className="mono">{shortHash(hash)}</span></p> : null}
        {canApprove && when !== undefined ? <p className="dock__hint">{t('decision.takesEffectOn', { when })}</p> : null}
        {decides ? <p id={hintId} className="sr-only">{t('dock.reasonHint')}</p> : null}
      </div>
      {error !== undefined && error !== null ? (
        <div className="pdock__problem" id={problemId}>
          <ProblemNotice error={error} message={message !== undefined ? t(message) : undefined} />
          {asksForReview(error) ? <button type="button" className="btn btn--small hit" onClick={onReload}>{t('approval.reload')}</button> : null}
        </div>
      ) : null}
    </section>
  );
}

/** After the engine's answer: what the decision did, in one line, announced politely. */
export function DecidedLine({ version, outcome }: { version: DefinitionVersion; outcome: 'approved' | 'rejected' }) {
  const { t, locale } = useI18n();
  const [now] = useState(() => Date.now());
  const effectiveFrom = version.effectiveFrom ?? undefined;
  const when = effectiveFrom !== undefined ? formatDateTime(effectiveFrom, locale) : undefined;
  const text = outcome === 'rejected'
    ? t('approval.rejected', { version: String(version.version) })
    : when !== undefined && effectiveFrom !== undefined && Date.parse(effectiveFrom) > now ? t('approval.done', { when }) : t('approval.doneNow');
  return (
    <section className="card decision decision--dock pdock pdock--done" aria-labelledby="pdock-title">
      <h2 id="pdock-title" className="rules-card-title">{t('decision.title')}</h2>
      <p className="pdock__done" role="status" aria-live="polite"><span className="pdock__check" aria-hidden="true">{outcome === 'approved' ? '✓' : '↩'}</span> {text}</p>
    </section>
  );
}
