import { useId, useState } from 'react';
import { toHostError, type HostError, type Violation } from '../../../api/hostError';
import { CopyButton } from '../../../components/CopyButton';
import type { MessageKey } from '../../../i18n/en';
import { placeError } from '../model/engineErrors';
import { refusalReason } from '../model/refusalReasons';
import { useI18n } from '../../../i18n/I18nProvider';

const KNOWN_CODES = [
  'VALIDATION_FAILED',
  'SELF_APPROVAL_FORBIDDEN',
  'VERSION_CONFLICT',
  'CONTENT_CHANGED',
  'CONTENT_HASH_REQUIRED',
  'INVALID_STATE',
  'PERMISSION_DENIED',
  'RULE_SET_EXISTS',
  'NO_EFFECTIVE_VERSION',
  'INPUT_INVALID',
  'EVALUATION_ERROR',
  'QUERY_INVALID',
  'PAYLOAD_TOO_LARGE',
] as const;

function messageKey(error: HostError): MessageKey {
  switch (error.kind) {
    case 'problem': {
      const code = KNOWN_CODES.find((known) => known === error.code);
      return code ? `problem.${code}` : 'error.problem.title';
    }
    case 'signed-out':
      return 'error.signedOut.title';
    case 'unreachable':
      return 'error.unreachable.title';
    case 'unexpected-response':
      return 'error.unexpected.title';
  }
}

/** A refused file: its name, and how a column name reads in the table (its display name). */
export interface RefusedFile {
  fileName: string;
  columnLabel: (name: string) => string;
}

/**
 * Every located problem of a refused document: the place (the file when known, the row and the column by its
 * display name) and the reason in the user's language; a reason not known here is the engine's English sentence.
 */
function Located({ violations, file, columnLabel }: { violations: readonly Violation[]; file: string | undefined; columnLabel: (name: string) => string }) {
  const { t } = useI18n();
  const place = (v: Violation): string | undefined => {
    if (v.ruleId === null) return file;
    if (file !== undefined) return v.column !== null ? t('import.placeCell', { file, row: v.ruleId, column: columnLabel(v.column) }) : t('import.placeRow', { file, row: v.ruleId });
    return v.column !== null ? t('editor.errorCell', { row: v.ruleId, column: columnLabel(v.column) }) : t('editor.errorRow', { row: v.ruleId });
  };
  return (
    <ul className="notice__located" aria-label={t('problem.locatedTitle')}>
      {violations.map((v, index) => {
        const where = place(v);
        const reason = refusalReason(v.message);
        return (
          <li key={index}>
            {where !== undefined ? <strong>{where}</strong> : null}
            {where !== undefined ? ': ' : null}
            {reason !== undefined ? <span>{t(reason)}</span> : <span lang="en">{v.message}</span>}
          </li>
        );
      })}
    </ul>
  );
}

/** The located problems of a 422; an engine that names one place only in `detail` is read as one problem. */
function violationsOf(problem: Extract<HostError, { kind: 'problem' }>): readonly Violation[] {
  if (problem.violations && problem.violations.length > 0) return problem.violations;
  if (problem.code !== 'VALIDATION_FAILED' || problem.detail === undefined) return [];
  const place = placeError(problem.detail);
  if (place.kind === 'cell') return [{ ruleId: place.ruleId, column: place.column, message: place.message }];
  if (place.kind === 'row') return [{ ruleId: place.ruleId, column: null, message: place.message }];
  return [{ ruleId: null, column: null, message: place.message }];
}

const asIs = (name: string) => name;

/**
 * `located` lists every violation of a refused document by row and column, for screens without the editor grid.
 * `refusedFile` is for an import: the title says nothing was saved, and each place names the file.
 */
export function ProblemNotice({ error, onReload, onRestart, showDetail = true, located = false, refusedFile, columnLabel = asIs, message }: {
  error: unknown;
  /** The sentence to show instead of the generic one for the error's code, for a screen that knows the action better. */
  message?: string | undefined;
  onReload?: () => void;
  onRestart?: () => void;
  showDetail?: boolean;
  located?: boolean;
  refusedFile?: RefusedFile | undefined;
  columnLabel?: (name: string) => string;
}) {
  const { t } = useI18n();
  const hostError = toHostError(error);
  const problem = hostError.kind === 'problem' ? hostError : undefined;
  // A 4xx refusal of an import stored nothing; an answer that never came (or a 5xx) cannot promise that.
  const refused = refusedFile !== undefined && problem !== undefined && problem.status >= 400 && problem.status < 500;
  const places = (located || refused) && problem ? violationsOf(problem) : [];
  const title = refused ? t('import.refused') : (message ?? t(messageKey(hostError)));
  const correlationId = hostError.kind === 'problem' || hostError.kind === 'unexpected-response' ? hostError.correlationId : undefined;
  const detail = showDetail ? problem?.detail : undefined;
  const hasDetails = problem?.code !== undefined || correlationId !== undefined || detail !== undefined;
  const [open, setOpen] = useState(false);
  const detailsId = useId();
  return (
    <div className="notice notice--bad notice--line" role="alert">
      <div className="notice__row">
        <p className="notice__title">
          <span className="notice__icon" aria-hidden="true">✕</span>
          {title}
        </p>
        {hasDetails ? (
          <button type="button" className="notice__toggle hit" aria-expanded={open} aria-controls={detailsId} onClick={() => { setOpen((value) => !value); }}>
            {t('try.details')} <span aria-hidden="true">{open ? '▴' : '▾'}</span>
          </button>
        ) : null}
      </div>
      {places.length > 0 ? <Located violations={places} file={refused ? refusedFile.fileName : undefined} columnLabel={refusedFile?.columnLabel ?? columnLabel} /> : null}
      {refused && places.length === 0 ? <p className="notice__subject"><strong>{refusedFile.fileName}</strong>: {t(messageKey(hostError))}</p> : null}
      {hasDetails ? (
        <div id={detailsId} className="notice__more" hidden={!open}>
          {detail !== undefined ? <p className="notice__detail" lang="en">{detail}</p> : null}
          <dl className="notice__facts">
            {problem?.code !== undefined ? (
              <div>
                <dt>{t('error.code')}</dt>
                <dd className="mono" data-testid="problem-code">{problem.code}</dd>
              </div>
            ) : null}
            {correlationId !== undefined ? (
              <div>
                <dt>{t('error.correlationId')}</dt>
                <dd className="facts__with-action">
                  <span className="mono">{correlationId}</span>
                  <CopyButton value={correlationId} />
                </dd>
              </div>
            ) : null}
          </dl>
        </div>
      ) : null}
      {onReload && problem?.code === 'VERSION_CONFLICT' ? (
        <button type="button" className="btn btn--small hit" onClick={onReload}>
          {t('problem.reload')}
        </button>
      ) : null}
      {onRestart ? (
        <button type="button" className="btn btn--small hit" onClick={onRestart}>
          {t('problem.restart')}
        </button>
      ) : null}
    </div>
  );
}
