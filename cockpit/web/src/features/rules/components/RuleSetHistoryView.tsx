import { useState } from 'react';
import type { RuleSetEvent, VersionHistoryLine } from '../../../api/rules/auditSchemas';
import { usePersonName } from '../../../app/people';
import { CopyButton } from '../../../components/CopyButton';
import { LoadingRows } from '../../../components/PageParts';
import { useI18n } from '../../../i18n/I18nProvider';
import '../../../components/audit.css';
import { useRuleSetHistory } from '../../decisions/api/useAudit';
import { AuditError, statusOf } from '../../decisions/components/AuditParts';
import { fileSize, shortHash } from '../../decisions/model/references';
import { formatDateTime } from '../model/dates';
import { HistoryEntryItem } from './HistoryList';
import { StatusBadge } from './StatusBadge';

/**
 * A rule set's history across its versions (CKP-072, PD-116): one line per version, newest first, with its status
 * in words, when it was in effect, who created, sent, approved or rejected it and their comments. Each line opens
 * to its events, oldest first. The rule set's evidence exports follow.
 */
export function RuleSetHistoryView({ ruleSet, openVersion }: { ruleSet: string; openVersion: number }) {
  const { t } = useI18n();
  const history = useRuleSetHistory(ruleSet);
  const [open, setOpen] = useState<ReadonlySet<number>>(() => new Set([openVersion]));
  if (history.isPending) return <div className="rsh"><LoadingRows rows={4} /></div>;
  if (history.isError) {
    const status = statusOf(history.error);
    const message = status === 403 ? t('rsh.error.forbidden') : status === 404 ? t('rsh.error.notFound') : t('area.error.generic');
    return <div className="rsh"><AuditError message={message} error={history.error} onRetry={status === 403 || status === 404 ? undefined : () => { void history.refetch(); }} /></div>;
  }
  const lines = history.data.pages.flatMap((page) => page.data);
  const exports = history.data.pages[0]?.ruleSetEvents ?? [];
  const toggle = (version: number) => {
    setOpen((current) => {
      const next = new Set(current);
      if (next.has(version)) next.delete(version);
      else next.add(version);
      return next;
    });
  };
  return (
    <section className="rsh" aria-labelledby="rsh-title">
      <h2 id="rsh-title" className="rules-card-title">{t('rsh.title', { count: String(lines.length) })}</h2>
      {lines.length === 0 ? <p className="dock__hint">{t('rsh.empty')}</p> : (
        <ol className="rsh__versions">
          {lines.map((line) => <VersionLine key={line.version} line={line} open={open.has(line.version)} onToggle={() => { toggle(line.version); }} />)}
        </ol>
      )}
      {history.hasNextPage ? (
        <button type="button" className="btn btn--quiet rsh__more" disabled={history.isFetchingNextPage} onClick={() => { void history.fetchNextPage(); }}>{t('rsh.more')}</button>
      ) : null}
      <Exports events={exports} truncated={history.data.pages[0]?.ruleSetEventsTruncated ?? false} />
    </section>
  );
}

function VersionLine({ line, open, onToggle }: { line: VersionHistoryLine; open: boolean; onToggle: () => void }) {
  const { t, locale } = useI18n();
  const name = usePersonName();
  const when = (iso: string | null | undefined) => formatDateTime(iso, locale);
  const panel = `rsh-events-${String(line.version)}`;
  const people: { label: string; who: string | null | undefined; at: string | null | undefined; bad?: boolean }[] = [
    { label: t('rsh.createdBy'), who: line.createdBy, at: line.createdAt },
    { label: t('rsh.requestedBy'), who: line.requestedBy, at: line.requestedAt },
    { label: t('rsh.approvedBy'), who: line.approvedBy, at: line.approvedAt },
    { label: t('rsh.rejectedBy'), who: line.rejectedBy, at: line.rejectedAt, bad: true },
  ];
  return (
    <li className={open ? 'rsh__line rsh__line--open' : 'rsh__line'}>
      <button type="button" className="rsh__toggle" aria-expanded={open} aria-controls={panel} onClick={onToggle}>
        <span className="rsh__chevron" aria-hidden="true">{open ? '▾' : '▸'}</span>
        <span className="rsh__version">{t('rules.version', { version: String(line.version) })}</span>
        <StatusBadge status={line.status} />
        <span className="rsh__effective tnum">
          {line.effectiveFrom !== null && line.effectiveFrom !== undefined
            ? t(line.effectiveTo !== null && line.effectiveTo !== undefined ? 'rsh.effectiveRange' : 'rsh.effectiveFrom', { from: when(line.effectiveFrom) ?? '', to: when(line.effectiveTo) ?? '' })
            : <span className="muted">{t('rsh.neverInEffect')}</span>}
        </span>
        <span className="sr-only">{t(open ? 'rsh.hideEvents' : 'rsh.showEvents')}</span>
      </button>
      <dl className="rsh__people">
        {people.filter((person) => person.who !== null && person.who !== undefined).map((person) => (
          <div key={person.label} className={person.bad === true ? 'rsh__person rsh__person--bad' : 'rsh__person'}>
            <dt>{person.label}</dt>
            <dd>{name(person.who ?? '')}{person.at !== null && person.at !== undefined ? <span className="muted tnum"> · {when(person.at)}</span> : null}</dd>
          </div>
        ))}
      </dl>
      {line.approvalComment || line.rejectionReason ? (
        <dl className="rsh__comments">
          {line.rejectionReason ? <div><dt>{t('rsh.rejectionReason')}</dt><dd><q>{line.rejectionReason}</q></dd></div> : null}
          {line.approvalComment ? <div><dt>{t('rsh.approvalComment')}</dt><dd><q>{line.approvalComment}</q></dd></div> : null}
        </dl>
      ) : null}
      <div id={panel} hidden={!open} className="rsh__events">
        {open ? (
          <ol className="history__list" aria-label={t('rsh.eventsOf', { version: String(line.version) })}>
            {line.events.map((entry) => <HistoryEntryItem key={`${entry.action}-${String(entry.lockVersion)}-${entry.occurredAt}`} entry={entry} />)}
          </ol>
        ) : null}
        {open && line.eventsTruncated ? <p className="dock__hint">{t('rsh.eventsTruncated')}</p> : null}
      </div>
    </li>
  );
}

/** Each evidence export, newest first: who, what and when, and the file's SHA-256 as a short reference with Copy. */
function Exports({ events, truncated }: { events: readonly RuleSetEvent[]; truncated: boolean }) {
  const { t, locale } = useI18n();
  const name = usePersonName();
  return (
    <section className="rsh__exports" aria-labelledby="rsh-exports-title">
      <h3 id="rsh-exports-title" className="rsh__subtitle">{t('rsh.exports', { count: String(events.length) })}</h3>
      {events.length === 0 ? <p className="dock__hint">{t('rsh.noExports')}</p> : (
        <ul className="rsh__export-list">
          {events.map((event) => {
            const size = fileSize(event.evidence.bytes, locale);
            return (
              <li key={`${event.occurredAt}-${event.evidence.contentSha256}`} className="rsh__export">
                <span className="rsh__export-what">
                  {t('rsh.exportLine', { who: name(event.actor), versions: t('evidence.versionCount', { count: String(event.evidence.versions.length) }), decisions: t('evidence.decisionCount', { count: String(event.evidence.decisionIds.length) }) })}
                </span>
                <span className="muted tnum">{event.evidence.format.toUpperCase()} · {t(`evidence.size.${size.unit}`, { value: size.value })} · {formatDateTime(event.occurredAt, locale)}</span>
                <span className="rsh__export-hash">
                  <span className="muted">{t('evidence.sha')}</span> <span className="ref-text" title={event.evidence.contentSha256}>{shortHash(event.evidence.contentSha256)}</span>
                  <CopyButton value={event.evidence.contentSha256} label={t('evidence.copySha')} copiedLabel={t('evidence.shaCopied')} />
                </span>
              </li>
            );
          })}
        </ul>
      )}
      {truncated ? <p className="dock__hint">{t('rsh.exportsTruncated')}</p> : null}
    </section>
  );
}
