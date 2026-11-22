import { useId, useState, type ReactNode } from 'react';
import { toHostError } from '../../../api/hostError';
import type { MigrationDryRun, MigrationPlan } from '../../../api/processes/schemas';
import { formatRoute } from '../../../app/route';
import { useI18n } from '../../../i18n/I18nProvider';
import type { MessageKey } from '../../../i18n/en';
import { ProblemNotice } from '../../rules/components/ProblemNotice';
import { counted } from './model/counted';
import type { NextStep } from './model/migration';

/** What a command of the dock is doing and what the engine answered. */
export interface DockCommand<TArgs = void> {
  start: (args: TArgs) => void;
  isPending: boolean;
  error: unknown;
}

const PROBLEM_CODES = [
  'DRY_RUN_REQUIRED', 'SELF_APPROVAL_FORBIDDEN', 'VERSION_CONFLICT', 'INVALID_STATE', 'MIGRATION_PLAN_INVALID', 'MIGRATION_NOT_FOUND',
  'DEFINITION_NOT_FOUND', 'PERMISSION_DENIED',
] as const;

/** The sentence for a refused command in the words of this screen; undefined for any other answer. */
export function migrationRefusal(error: unknown): MessageKey | undefined {
  if (error === undefined || error === null) return undefined;
  const host = toHostError(error);
  const code = host.kind === 'problem' ? PROBLEM_CODES.find((known) => known === host.code) : undefined;
  return code !== undefined ? `migration.problem.${code}` : undefined;
}

/** The engine says the proposal moved on, or the check no longer holds: what is on screen is not what it would act on. */
export function problemCode(error: unknown): string | undefined {
  if (error === undefined || error === null) return undefined;
  const host = toHostError(error);
  return host.kind === 'problem' ? host.code : undefined;
}

/** A line saying why the one action is not offered, with a quiet ⊘. */
function WhyNot({ id, children }: { id: string; children: ReactNode }) {
  return <p className="pdock__reason"><span aria-hidden="true">⊘</span> <span id={id}>{children}</span></p>;
}

/**
 * The one next action of a migration, docked at the bottom so it stays in view (CKP-057). It is a single primary
 * button whose meaning follows the state: Check plan, then ask for approval with a reason, then (as a second person)
 * approve. When the action is not offered, the same button is shown disabled with one line saying why beside it.
 * Approve names the proposal the approver was shown, which the page holds.
 */
export function MigrationDock({ step, plan, dryRun, check, propose, approve, reject, onReviewAgain }: {
  step: NextStep;
  plan: MigrationPlan;
  dryRun: MigrationDryRun | undefined;
  check: DockCommand;
  propose: DockCommand<{ reason: string }>;
  approve: DockCommand;
  reject: DockCommand<{ reason: string }>;
  onReviewAgain: () => void;
}) {
  const { t } = useI18n();
  const [text, setText] = useState('');
  const textId = useId();
  const hintId = useId();
  const whyId = useId();
  const errorId = useId();
  const error: unknown = check.error ?? propose.error ?? approve.error ?? reject.error;
  const busy = check.isPending || propose.isPending || approve.isPending || reject.isPending;
  const message = migrationRefusal(error);
  const secondPerson = dryRun?.approval.secondPersonRequired ?? plan.approval.secondPersonRequired;

  const reasonField = (label: MessageKey, hint: MessageKey, placeholder?: MessageKey) => (
    <div className="field">
      <label className="field__label" htmlFor={textId}>{t(label)}</label>
      <textarea id={textId} className="field__input" rows={1} value={text} maxLength={2000} aria-describedby={hintId}
        {...(placeholder !== undefined ? { placeholder: t(placeholder) } : {})} onChange={(event) => { setText(event.target.value); }} />
      <span id={hintId} className="field__hint">{t(hint)}</span>
    </div>
  );
  const primary = (label: string, props: { disabled: boolean; busy?: boolean; describedBy?: string; onClick?: () => void }) => (
    <button type="button" className="btn btn--primary" disabled={props.disabled || busy} aria-busy={props.busy === true}
      {...(props.describedBy !== undefined ? { 'aria-describedby': props.describedBy } : {})} onClick={props.onClick}>{label}</button>
  );

  let body: ReactNode = null;
  let actions: ReactNode = null;
  switch (step.kind) {
    case 'check': {
      const hint = step.because === 'first' ? 'migration.hint.first' : `migration.hint.${step.because}` as const;
      body = <p className="mdock__hint" id={whyId}>{t(hint)}</p>;
      actions = primary(check.isPending ? t('migration.checking') : t('migration.check'), { disabled: false, busy: check.isPending, describedBy: whyId, onClick: () => { check.start(); } });
      break;
    }
    case 'propose': {
      const count = String(step.movable);
      body = reasonField('migration.reasonLabel', 'migration.reasonKept', 'migration.reasonHint');
      actions = primary(
        propose.isPending ? t('migration.proposing') : t(counted(secondPerson ? 'migration.propose' : 'migration.moveNow', step.movable), { count }),
        { disabled: text.trim() === '', busy: propose.isPending, describedBy: hintId, onClick: () => { propose.start({ reason: text.trim() }); } },
      );
      break;
    }
    case 'cannot-propose':
      body = (
        <WhyNot id={whyId}>
          {step.why === 'conflicts' ? t('migration.cannotPropose.conflicts') : t('migration.cannotPropose.empty', { source: String(plan.sourceVersion) })}
          {step.why === 'conflicts' ? <> <a className="linkbtn hit" href={formatRoute({ name: 'migrate', key: plan.definitionKey, version: plan.targetVersion, from: plan.sourceVersion })}>{t('migration.newPlan')}</a></> : null}
        </WhyNot>
      );
      actions = primary(t(counted('migration.propose', dryRun?.instances.movable ?? 0), { count: String(dryRun?.instances.movable ?? 0) }), { disabled: true, describedBy: whyId });
      break;
    case 'approve':
    case 'cannot-approve': {
      const movable = step.kind === 'approve' ? step.movable : dryRun?.instances.movable;
      const label = movable !== undefined ? t(counted('migration.approve', movable), { count: String(movable) }) : t('migration.approve.other', { count: '…' });
      const offered = step.kind === 'approve';
      const canSendBack = step.kind === 'approve' || step.why === 'conflicts';
      body = (
        <>
          {step.kind === 'cannot-approve' ? (
            <WhyNot id={whyId}>
              {t(`migration.cannotApprove.${step.why}`)}
              {step.why === 'changed' ? <> <button type="button" className="linkbtn hit" onClick={onReviewAgain}>{t('decision.reviewAgain')}</button></> : null}
            </WhyNot>
          ) : null}
          {canSendBack ? reasonField('migration.rejectReason', 'migration.rejectReasonHint') : null}
        </>
      );
      actions = (
        <div className="gate__buttons">
          {canSendBack ? (
            <button type="button" className="btn btn--danger-quiet" disabled={text.trim() === '' || busy} aria-describedby={hintId} onClick={() => { reject.start({ reason: text.trim() }); }}>
              {reject.isPending ? t('migration.rejecting') : t('migration.reject')}
            </button>
          ) : null}
          {primary(approve.isPending ? t('migration.approving') : label, { disabled: !offered, busy: approve.isPending, ...(step.kind === 'cannot-approve' ? { describedBy: whyId } : {}), onClick: () => { approve.start(); } })}
        </div>
      );
      break;
    }
    case 'watch':
      body = <p className="mdock__hint">{t('migration.watch')}</p>;
      break;
    case 'done':
      body = <p className="mdock__hint">{t('migration.finished.note')}</p>;
      break;
    case 'read-only':
      body = <WhyNot id={whyId}>{t('migration.readOnly')}</WhyNot>;
      break;
  }

  return (
    <section className="card decision decision--dock pdock mdock" aria-labelledby="mdock-title">
      <h2 id="mdock-title" className="rules-card-title">{t('migration.dock.title')}</h2>
      <div className="decision__body">{body}</div>
      {actions !== null ? <div className="decision__actions">{actions}</div> : null}
      {error !== undefined && error !== null ? (
        <div className="pdock__problem" id={errorId}>
          <ProblemNotice error={error} message={message !== undefined ? t(message) : undefined} />
        </div>
      ) : null}
    </section>
  );
}
