import { useId } from 'react';
import type { RuleSetDefinition, VersionWarning } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import { columnLabel } from '../model/reading';

/** The approver's acceptance of the warnings (PD-104): ticked or not, and whether the engine refused without it. */
export interface Acceptance {
  checked: boolean;
  onChange: (checked: boolean) => void;
  /** The engine answered 409 WARNINGS_NOT_ACKNOWLEDGED: say plainly that the box is needed. */
  needed: boolean;
  /** The id Approve points to for its description while the box is not ticked. */
  id: string;
}

/**
 * The engine's personal-data warnings for a version (RLE-049), one short line per input named by its column label,
 * then what follows from it. With `acceptance`, the approver ticks one box naming the inputs before Approve works
 * (PD-104). Nothing renders when there is no warning.
 */
export function PersonalDataWarnings({ warnings, definition, acceptance }: { warnings: readonly VersionWarning[]; definition: RuleSetDefinition; acceptance?: Acceptance | undefined }) {
  const { t } = useI18n();
  const titleId = useId();
  if (warnings.length === 0) return null;
  const label = (input: string) => columnLabel(input, definition.inputs.find((clause) => clause.name === input)?.description);
  const inputs = [...new Set(warnings.map((warning) => warning.input))].map(label);
  const many = inputs.length > 1;
  return (
    <div className="pii-warning" role="group" aria-labelledby={titleId}>
      <p id={titleId} className="pii-warning__title">
        <span aria-hidden="true" className="pii-warning__icon">▲</span> {t('pii.title')}
      </p>
      <ul className="pii-warning__list">
        {inputs.map((input) => <li key={input}>{t('pii.line', { input })}</li>)}
      </ul>
      <p className="pii-warning__why">{t(many ? 'pii.consequenceMany' : 'pii.consequenceOne')}</p>
      {acceptance !== undefined ? (
        <>
          {acceptance.needed ? <p className="pii-warning__why pii-warning__needed" role="alert">{t('pii.needed')}</p> : null}
          <label className="pii-warning__accept">
            <input type="checkbox" checked={acceptance.checked} onChange={(e) => { acceptance.onChange(e.target.checked); }} />
            <span id={acceptance.id}>{t(many ? 'pii.acceptMany' : 'pii.acceptOne', { inputs: inputs.join(', ') })}</span>
          </label>
        </>
      ) : null}
    </div>
  );
}
