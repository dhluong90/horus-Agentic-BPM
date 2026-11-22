import { useEffect, useId, useRef, useState } from 'react';
import { toHostError } from '../../../api/hostError';
import type { RuleSetVersion } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import { useImportSpreadsheet, type PickedSheet } from '../api/useRules';
import { useFilePicker } from './ImportFileButton';
import { ProblemNotice } from './ProblemNotice';

/**
 * Confirms a spreadsheet import before anything is sent (CKP-039). It states the version the file is based on and
 * that the result is a new draft. The engine's refusal of a cell (422) stores nothing and is listed per row and
 * column; a conflict (409) or any other failure keeps the file and the dialog, so the user's work is not lost.
 */
export function ImportSpreadsheetDialog({ ruleSet, base, first, columnLabel, onClose, onCreated }: {
  ruleSet: string;
  /** The version whose columns, hit policy and masks the file uses. */
  base: number;
  first: PickedSheet;
  columnLabel: (name: string) => string;
  onClose: () => void;
  onCreated: (version: RuleSetVersion, fileName: string) => void;
}) {
  const { t } = useI18n();
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const [sheet, setSheet] = useState(first);
  const [attempt, setAttempt] = useState(0);
  const importing = useImportSpreadsheet(ruleSet, (created) => { onCreated(created, sheet.fileName); });
  const picker = useFilePicker(undefined, (picked) => { importing.reset(); setSheet(picked); setAttempt(0); });

  // The native dialog gives the focus trap, Escape and focus return.
  useEffect(() => {
    const element = dialog.current;
    if (!element || element.open) return;
    // jsdom has no showModal; the open attribute is enough there.
    if (typeof element.showModal === 'function') element.showModal();
    else element.setAttribute('open', '');
  }, []);

  const problem = importing.isError ? toHostError(importing.error) : undefined;
  const status = problem?.kind === 'problem' ? problem.status : undefined;
  const refused = status === 422 || status === 400 || status === 403 || status === 404 || status === 413;
  const send = (next: number) => { importing.start({ base, sheet, attempt: next }); };

  let notice: React.ReactNode = null;
  if (problem !== undefined && status === 409) notice = <p className="field__error" role="alert">{t('sheet.conflict')}</p>;
  else if (problem !== undefined && refused) notice = <ProblemNotice error={importing.error} refusedFile={{ fileName: sheet.fileName, columnLabel }} />;
  else if (problem !== undefined) notice = <p className="field__error" role="alert">{t('sheet.failed')}</p>;

  return (
    <dialog ref={dialog} className="dialog dialog--wide" aria-labelledby={titleId} onClose={onClose}>
      <div className="dialog__body">
        <h2 id={titleId} className="dialog__title">{t('sheet.title')}</h2>
        <p className="dialog__text">{t('sheet.based', { version: String(base) })}</p>
        <p className="dialog__file"><span className="dialog__file-label">{t('sheet.fileLabel')}</span> <strong>{sheet.fileName}</strong></p>
        {problem === undefined ? <p className="dialog__text">{t('sheet.text')}</p> : null}
        {notice}
        <div className="dialog__actions">
          <button type="button" className="btn btn--quiet" onClick={onClose}>{problem !== undefined ? t('sheet.close') : t('action.cancel')}</button>
          {refused ? (
            <button type="button" className="btn btn--primary" onClick={picker.open}>{t('sheet.another')}</button>
          ) : (
            <button type="button" className="btn btn--primary" disabled={importing.isPending} aria-busy={importing.isPending}
              onClick={() => { const next = problem !== undefined ? attempt + 1 : attempt; setAttempt(next); send(next); }}>
              {importing.isPending ? t('sheet.importing') : problem !== undefined ? t('sheet.retry') : t('sheet.confirm')}
            </button>
          )}
        </div>
        {/* After the buttons, so the first thing the keyboard reaches is Cancel. */}
        {picker.input}
      </div>
    </dialog>
  );
}
