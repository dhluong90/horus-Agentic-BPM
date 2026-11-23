import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { toHostError } from '../../../api/hostError';
import type { RuleSetTemplate, RuleSetVersion } from '../../../api/rules/schemas';
import { AreaError, LoadingRows } from '../../../components/PageParts';
import { useI18n } from '../../../i18n/I18nProvider';
import { useImportRuleSet, useTemplates } from '../api/useRules';
import { columnLabel } from '../model/reading';
import { composeDocument, DISPLAY_NAME_MAX, inLanguage, nameProblem, NAME_MAX, suggestName } from '../model/template';
import { ProblemNotice } from './ProblemNotice';

const POLICY_KEY = { first: 'policy.FIRST', unique: 'policy.UNIQUE', collect: 'policy.COLLECT' } as const;

/**
 * Create a rule set from a template (PD-112). The author picks one of the host's templates, names the rule set for
 * people (display name) and for the system (a short key, filled in from the display name), and lands in the new
 * draft. The engine checks the document as any import: a name already used in the tenant is its 409, shown at the
 * name, and nothing is created.
 */
export function NewRuleSetDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (version: RuleSetVersion) => void }) {
  const { t, locale } = useI18n();
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const nameId = useId();
  const keyId = useId();
  const keyHintId = useId();
  const templates = useTemplates(true);
  const [picked, setPicked] = useState<string | undefined>(undefined);
  const [displayName, setDisplayName] = useState('');
  const [typedKey, setTypedKey] = useState<string | undefined>(undefined);
  const [tried, setTried] = useState(false);
  const create = useImportRuleSet(onCreated);

  // The native dialog gives the focus trap, Escape and focus return.
  useEffect(() => {
    const element = dialog.current;
    if (!element || element.open) return;
    // jsdom has no showModal; the open attribute is enough there.
    if (typeof element.showModal === 'function') element.showModal();
    else element.setAttribute('open', '');
  }, []);

  const template: RuleSetTemplate | undefined = templates.data?.find((candidate) => candidate.id === picked);
  const key = typedKey ?? suggestName(displayName);
  const keyProblem = nameProblem(key);
  const nameMissing = displayName.trim() === '';
  const problem = create.isError ? toHostError(create.error) : undefined;
  const clash = problem?.kind === 'problem' && problem.code === 'RULE_SET_EXISTS';
  const reason = template === undefined ? 'newSet.reason.template' : nameMissing ? 'newSet.reason.name' : keyProblem !== undefined ? 'newSet.reason.key' : undefined;
  const reasonId = useId();
  const choices = useMemo(() => templates.data ?? [], [templates.data]);

  const submit = () => {
    setTried(true);
    if (template === undefined || nameMissing || keyProblem !== undefined) return;
    create.start(composeDocument(template, key, displayName.trim()));
  };

  return (
    <dialog ref={dialog} className="dialog dialog--wide new-set" aria-labelledby={titleId} onClose={onClose}>
      <form className="dialog__body" noValidate onSubmit={(event) => { event.preventDefault(); submit(); }}>
        <h2 id={titleId} className="dialog__title">{t('newSet.title')}</h2>
        <p className="dialog__text">{t('newSet.text')}</p>

        <fieldset className="new-set__templates">
          <legend className="field__label">{t('newSet.pick')}</legend>
          {templates.isPending ? <LoadingRows rows={2} /> : null}
          {templates.isError ? <AreaError error={templates.error} onRetry={() => { void templates.refetch(); }} /> : null}
          {templates.isSuccess && choices.length === 0 ? <p className="muted" role="status">{t('newSet.none')}</p> : null}
          <div className="new-set__grid">
            {choices.map((item) => {
              const policy = item.hitPolicy in POLICY_KEY ? POLICY_KEY[item.hitPolicy as keyof typeof POLICY_KEY] : 'policy.FIRST';
              return (
                <label key={item.id} className={picked === item.id ? 'tpl tpl--picked' : 'tpl'}>
                  <input type="radio" name="template" value={item.id} checked={picked === item.id} onChange={() => { setPicked(item.id); create.reset(); }} />
                  <span className="tpl__label">{inLanguage(item.labels, locale)}</span>
                  <span className="tpl__text">{inLanguage(item.descriptions, locale)}</span>
                  <span className="tpl__meta">
                    <span>{t('newSet.columns', { inputs: item.inputs.map((input) => columnLabel(input.name, undefined)).join(', ') || '—', outputs: item.outputs.map((output) => columnLabel(output.name, undefined)).join(', ') || '—' })}</span>
                    <span>{t('newSet.rowsPolicy', { count: String(item.rows.length), policy: t(policy) })}</span>
                  </span>
                </label>
              );
            })}
          </div>
        </fieldset>

        <div className="new-set__fields">
          <label className="field" htmlFor={nameId}>
            <span className="field__label">{t('newSet.displayName')}</span>
            <input id={nameId} className="field__input" value={displayName} maxLength={DISPLAY_NAME_MAX} autoComplete="off" placeholder={t('newSet.displayName.hint')}
              aria-invalid={tried && nameMissing ? true : undefined}
              onChange={(event) => { setDisplayName(event.target.value); create.reset(); }} />
          </label>
          <div className="field">
            <label className="field__label" htmlFor={keyId}>{t('newSet.key')}</label>
            <input id={keyId} className="field__input tnum" value={key} maxLength={NAME_MAX} autoComplete="off" spellCheck={false}
              aria-invalid={clash || (tried && keyProblem !== undefined) ? true : undefined} aria-describedby={keyHintId}
              onChange={(event) => { setTypedKey(event.target.value.toLowerCase()); create.reset(); }} />
            {clash ? <span id={keyHintId} className="field__error" role="alert">{t('newSet.clash', { name: key })}</span>
              : tried && keyProblem === 'format' ? <span id={keyHintId} className="field__error" role="alert">{t('newSet.keyFormat')}</span>
              : <span id={keyHintId} className="field__hint">{t('newSet.keyHint')}</span>}
          </div>
        </div>

        {problem !== undefined && !clash ? <ProblemNotice error={create.error} located /> : null}
        <p className="dialog__text new-set__draft">{t('newSet.draftNote')}</p>

        <div className="dialog__actions">
          {tried && reason !== undefined ? <p id={reasonId} className="reason-line new-set__reason"><span aria-hidden="true">⊘</span> {t(reason)}</p> : null}
          <button type="button" className="btn btn--quiet" onClick={onClose}>{t('action.cancel')}</button>
          <button type="submit" className="btn btn--primary" disabled={create.isPending} aria-busy={create.isPending} {...(tried && reason !== undefined ? { 'aria-describedby': reasonId } : {})}>
            {create.isPending ? t('newSet.creating') : t('newSet.create')}
          </button>
        </div>
      </form>
    </dialog>
  );
}
