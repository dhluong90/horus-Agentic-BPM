import { useI18n } from '../../../i18n/I18nProvider';
import type { CellReading, Value } from '../model/reading';

export function ValueText({ value }: { value: Value }) {
  const { t } = useI18n();
  switch (value.kind) {
    case 'text':
      return <>{value.text}</>;
    case 'yes':
      return <>{t('table.yes')}</>;
    case 'no':
      return <>{t('table.no')}</>;
    case 'empty':
      return <>{t('table.empty')}</>;
  }
}

/** One table cell as a human reading. Wildcards are a quiet dash with a spoken label. */
export function CellValue({ reading }: { reading: CellReading }) {
  const { t } = useI18n();
  switch (reading.kind) {
    case 'any':
      return (
        <span className="cell-any" title={t('table.any')}>
          <span aria-hidden="true">—</span>
          <span className="sr-only">{t('table.any')}</span>
        </span>
      );
    case 'values':
      return (
        <span className="cell-values">
          {reading.negated ? <span className="cell-op">{t('table.not')}</span> : null}
          {reading.values.map((value, index) => (
            <span key={index} className="value-pill">
              <ValueText value={value} />
            </span>
          ))}
        </span>
      );
    case 'compare':
      return (
        <span className="cell-values">
          <span className="cell-op">{reading.operator}</span>
          <span className="value-pill tnum">
            <ValueText value={reading.value} />
          </span>
        </span>
      );
    case 'value':
      return (
        <span className="cell-output tnum">
          <ValueText value={reading.value} />
        </span>
      );
    case 'group':
      return <span className="cell-raw">{t('table.group')}</span>;
    case 'raw':
      return <span className="cell-raw mono">{reading.text}</span>;
  }
}
