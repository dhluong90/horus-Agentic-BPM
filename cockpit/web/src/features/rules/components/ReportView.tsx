import type { RuleSetVersion, SimulationReport, SimulationReportRow } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import { useReport, useReports } from '../api/useRules';
import { hiddenRows, shares, shortHash } from '../model/report';
import { formatCount, ReportStatus, useRunText, useScopeText } from './ReportParts';
import { EmptyNote, ReadingQueryState, ReadingStyleSwitch, readingIn, useViewerReadingStyle } from './ReadingParts';
import type { ReadingsByLanguage } from '../../../api/rules/schemas';

/**
 * The simulation report attached to a publication request, for the approver (PD-115, RLE-200): how many items the
 * run covered, how many outcomes changed, and for each changed item the outcome before and after in the engine's own
 * words. The report is immutable and bound to the content hash it was run on; when the draft changed afterwards the
 * engine marks it out of date and it is shown as such. Approval stays bound to the content the reviewer opened (the
 * decision card), never to the report.
 */
export function ReportView({ version }: { version: Pick<RuleSetVersion, 'ruleSet' | 'version' | 'canonicalHash'> }) {
  const { t } = useI18n();
  const reports = useReports(version.ruleSet, version.version);
  const attached = reports.data?.find((report) => report.attached);
  const full = useReport(version.ruleSet, version.version, attached?.id);
  if (reports.isSuccess && attached === undefined) {
    return <div className="report"><EmptyNote text="report.none" /></div>;
  }
  return (
    <div className="report">
      <ReadingQueryState query={attached === undefined ? { isPending: reports.isPending, isError: reports.isError, error: reports.error, data: undefined, refetch: reports.refetch } : full}
        rows={5} tooLarge={<EmptyNote text="report.none" />}
        ready={(report) => <Report report={report} version={version} />} />
      <span className="sr-only" aria-live="polite">{full.isSuccess ? t('report.loaded') : ''}</span>
    </div>
  );
}

function Report({ report, version }: { report: SimulationReport; version: Pick<RuleSetVersion, 'canonicalHash'> }) {
  const { t, locale } = useI18n();
  const scope = useScopeText(report);
  const run = useRunText(report);
  const { counts } = report;
  const share = shares(counts);
  const hidden = hiddenRows(report);
  const outOfDate = report.status === 'OUT_OF_DATE' || report.canonicalHash !== version.canonicalHash;
  const tiles: readonly { key: 'changed' | 'unchanged' | 'failed' | 'notReplayable'; value: number }[] = [
    { key: 'changed', value: counts.changed },
    { key: 'unchanged', value: counts.unchanged },
    ...(counts.failed > 0 ? [{ key: 'failed' as const, value: counts.failed }] : []),
    ...(counts.notReplayable > 0 ? [{ key: 'notReplayable' as const, value: counts.notReplayable }] : []),
  ];
  return (
    <>
      <header className="report__head">
        <div>
          <h2 className="report__title">{t('report.title')} <ReportStatus status={outOfDate ? 'OUT_OF_DATE' : 'CURRENT'} /></h2>
          <p className="report__meta">{scope} · {run}</p>
        </div>
        <p className="report__hash" title={report.canonicalHash}>
          <span className="muted">{t('report.boundTo')}</span> <code className="tnum">{shortHash(report.canonicalHash)}</code>
        </p>
      </header>
      {outOfDate ? (
        <div className="alert alert--warn report__stale" role="status">
          <span className="alert__icon" aria-hidden="true">▲</span>
          <div className="alert__body"><p>{t('report.outOfDate')}</p></div>
        </div>
      ) : null}
      <div className="report__tiles" role="group" aria-label={t('report.counts')}>
        {tiles.map((tile) => (
          <div key={tile.key} className={tile.key === 'changed' ? 'tile tile--lead' : 'tile'}>
            <span className="tile__value tnum">{formatCount(tile.value, locale)}</span>
            <span className="tile__label">{t(`report.count.${tile.key}`)}</span>
          </div>
        ))}
        <svg className="report__bar" viewBox="0 0 100 8" preserveAspectRatio="none" role="img" aria-label={t('report.bar', { changed: formatCount(counts.changed, locale), total: formatCount(counts.total, locale) })}>
          <rect className="report__bar-bg" x="0" y="0" width="100" height="8" rx="4" />
          <rect className="report__bar-same" x={share.changed} y="0" width={share.unchanged} height="8" />
          <rect className="report__bar-changed" x="0" y="0" width={share.changed} height="8" />
        </svg>
      </div>
      {report.rows.length === 0 ? (
        <EmptyNote text="report.noChanges" />
      ) : (
        <>
          <div className="report__listhead">
            <h3 className="report__sub">{t('report.rows.title')}</h3>
            <ReadingStyleSwitch />
          </div>
          <div className="report__scroll">
            <table className="qtable report__table">
              <thead>
                <tr>
                  <th scope="col">{t('report.col.case')}</th>
                  <th scope="col">{t('report.col.before')}</th>
                  <th scope="col">{t('report.col.after')}</th>
                </tr>
              </thead>
              <tbody>
                {report.rows.map((row, index) => <ReportRow key={`${row.reference ?? row.decisionId ?? 'row'}-${String(index)}`} row={row} index={index} />)}
              </tbody>
            </table>
          </div>
          {hidden > 0 || report.rowsTruncated ? <p className="list-note report__more" role="note">{t('report.more', { shown: formatCount(report.rows.length, locale), total: formatCount(counts.changed + counts.failed, locale) })}</p> : null}
        </>
      )}
    </>
  );
}

function Outcome({ readings }: { readings: ReadingsByLanguage | undefined }) {
  const { t, locale } = useI18n();
  const [style] = useViewerReadingStyle();
  if (readings === undefined) return <span className="muted" aria-label={t('report.noOutcome')}>—</span>;
  return <>{readingIn(readings, locale, style) ?? <span className="muted">{t('report.noReading')}</span>}</>;
}

function ReportRow({ row, index }: { row: SimulationReportRow; index: number }) {
  const { t } = useI18n();
  // A replayed decision is named by its id (only for a holder of the log permission); a sample by S1, S2.
  const name = row.reference ?? (row.decisionId !== null ? row.decisionId.slice(-8) : t('report.row', { n: String(index + 1) }));
  return (
    <tr className={row.category === 'FAILED' ? 'report__row report__row--failed' : 'report__row'}>
      <th scope="row" className="tnum">
        <div className="report__case">
          <span>{name}</span>
          {row.category === 'FAILED' ? <span className="status status--bad">{t('report.failed')}</span> : null}
        </div>
      </th>
      <td className="report__before"><Outcome readings={row.before?.readings} /></td>
      <td className="report__after"><Outcome readings={row.after?.readings} /></td>
    </tr>
  );
}
