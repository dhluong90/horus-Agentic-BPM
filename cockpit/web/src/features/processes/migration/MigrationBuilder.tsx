import { useEffect, useId, useMemo, useState, type ReactNode } from 'react';
import { setCrumbTrail } from '../../../app/crumbs';
import { formatRoute } from '../../../app/route';
import { AreaError, EmptyState, LoadingRows, PageHead } from '../../../components/PageParts';
import { useI18n } from '../../../i18n/I18nProvider';
import { ProblemNotice } from '../../rules/components/ProblemNotice';
import { useProcessesSession } from '../api/processesContext';
import { useDefinitionGraph, useDefinitionVersion } from '../api/useProcesses';
import type { DiagramGraph } from '../model/bpmnGraph';
import { nodeTypeLabel } from '../model/nodeLabels';
import { migrationRefusal } from './MigrationDock';
import { useCreateMigrationPlan } from './api/useMigrations';
import { openChoices, planInstructions, type StepChoice } from './model/migration';

/**
 * Plan a migration (CKP-027): the operator names the version the cases are on now, and for every step that version has
 * and the new one does not, what happens to the cases waiting there. Creating the plan moves nothing; it opens the
 * plan, where it is checked, proposed and approved. A step the operator leaves open is flagged and blocks the plan.
 */
export function MigrationBuilder({ definitionKey, target, from }: { definitionKey: string; target: number; from: number | undefined }) {
  const { t } = useI18n();
  const { can } = useProcessesSession();
  const version = useDefinitionVersion(definitionKey, target);
  const name = version.data?.name ?? undefined;
  useEffect(() => {
    setCrumbTrail([
      ...(name !== undefined ? [{ label: name, href: formatRoute({ name: 'process', key: definitionKey, version: target }) }] : []),
      { label: t('migration.crumb') },
    ]);
    return () => { setCrumbTrail(undefined); };
  }, [name, definitionKey, target, t]);
  const source = from !== undefined && from < target ? from : target - 1;
  const head = (subtitle: string) => <PageHead id="migrate-title" title={t('migration.title')} subtitle={subtitle} />;
  if (!can('processes:migration:manage')) {
    return <section className="workspace" aria-labelledby="migrate-title">{head(t('migrate.subtitle'))}<article className="card"><EmptyState icon="processes" text={t('migrate.noPermission')} /></article></section>;
  }
  if (target < 2) {
    return <section className="workspace" aria-labelledby="migrate-title">{head(t('migrate.subtitle'))}<article className="card"><EmptyState icon="processes" text={t('migrate.noVersions')} /></article></section>;
  }
  if (version.isPending) return <section className="workspace" aria-busy="true"><LoadingRows rows={6} /></section>;
  if (version.isError) return <section className="workspace" aria-labelledby="migrate-title">{head(t('migrate.subtitle'))}<article className="card"><AreaError error={version.error} onRetry={() => { void version.refetch(); }} /></article></section>;
  if (version.data.status !== 'ACTIVE') {
    return <section className="workspace" aria-labelledby="migrate-title">{head(t('migrate.subtitle'))}<article className="card"><EmptyState icon="processes" text={t('migrate.notActive')} /></article></section>;
  }
  return <Builder definitionKey={definitionKey} target={target} initialSource={source} head={head(t('migrate.subtitle'))} />;
}

function Builder({ definitionKey, target, initialSource, head }: { definitionKey: string; target: number; initialSource: number; head: ReactNode }) {
  const { t } = useI18n();
  const [source, setSource] = useState(initialSource);
  const sourceGraph = useDefinitionGraph(definitionKey, source);
  const targetGraph = useDefinitionGraph(definitionKey, target);
  const create = useCreateMigrationPlan();
  const [choices, setChoices] = useState<Readonly<Record<string, { choice: string; reason: string }>>>({});
  const selectId = useId();
  const hintId = useId();
  const gone = useMemo(() => {
    if (sourceGraph.data === undefined || targetGraph.data === undefined) return [];
    const kept = new Set(targetGraph.data.nodes.map((node) => node.id));
    return sourceGraph.data.nodes.filter((node) => !kept.has(node.id));
  }, [sourceGraph.data, targetGraph.data]);
  const list: StepChoice[] = gone.map((node) => ({ step: node.id, choice: choices[node.id]?.choice ?? '', reason: choices[node.id]?.reason ?? '' }));
  const open = openChoices(list);
  const set = (id: string, next: Partial<{ choice: string; reason: string }>) => { setChoices((current) => ({ ...current, [id]: { choice: '', reason: '', ...current[id], ...next } })); };
  const versions = Array.from({ length: target - 1 }, (_, index) => target - 1 - index);
  const ready = sourceGraph.data !== undefined && targetGraph.data !== undefined;
  const message = migrationRefusal(create.error);

  return (
    <section className="workspace workspace--docked" aria-labelledby="migrate-title">
      {head}
      <article className="card builder">
        <div className="builder__from">
          <label className="field field--inline" htmlFor={selectId}>
            <span className="field__label">{t('migrate.from')}</span>
            <select id={selectId} className="field__input field__input--compact" value={source} onChange={(event) => { setSource(Number(event.target.value)); setChoices({}); }}>
              {versions.map((value) => <option key={value} value={value}>{t('migrate.fromVersion', { version: String(value) })}</option>)}
            </select>
          </label>
        </div>
        {!ready ? (sourceGraph.isError || targetGraph.isError ? <AreaError error={sourceGraph.error ?? targetGraph.error} onRetry={() => { void sourceGraph.refetch(); void targetGraph.refetch(); }} /> : <LoadingRows rows={4} />) : gone.length === 0 ? (
          <p className="mdiagrams__none">{t('migration.steps.none')}</p>
        ) : (
          <section aria-labelledby="builder-steps">
            <h2 id="builder-steps" className="rules-card-title">{t('migrate.steps.title')}</h2>
            <p className="builder__help">{t('migrate.steps.help')}</p>
            <table className="qtable builder__table">
              <thead>
                <tr>
                  <th scope="col">{t('migrate.steps.col.step', { version: String(source) })}</th>
                  <th scope="col">{t('migrate.steps.col.action')}</th>
                </tr>
              </thead>
              <tbody>
                {gone.map((node) => (
                  <StepChoiceRow key={node.id} node={node} target={targetGraph.data} value={choices[node.id] ?? { choice: '', reason: '' }} onChange={(next) => { set(node.id, next); }} />
                ))}
              </tbody>
            </table>
          </section>
        )}
      </article>
      <section className="card decision decision--dock pdock mdock" aria-labelledby="mdock-title">
        <h2 id="mdock-title" className="rules-card-title">{t('migration.dock.title')}</h2>
        <div className="decision__body">
          {open > 0 ? <p className="pdock__reason"><span aria-hidden="true">⊘</span> <span id={hintId}>{t('migrate.open', { count: String(open) })}</span></p> : <p className="mdock__hint" id={hintId}>{t('migration.hint.first')}</p>}
        </div>
        <div className="decision__actions">
          <button type="button" className="btn btn--primary" disabled={!ready || open > 0 || create.isPending} aria-busy={create.isPending} aria-describedby={hintId}
            onClick={() => {
              const instructions = planInstructions(list);
              create.mutate({ definitionKey, sourceVersion: source, targetVersion: target, ...instructions }, {
                onSuccess: (plan) => { window.location.hash = formatRoute({ name: 'migration', migrationId: plan.id }); },
              });
            }}>
            {create.isPending ? t('migrate.creating') : t('migrate.create')}
          </button>
        </div>
        {create.error !== null ? <div className="pdock__problem"><ProblemNotice error={create.error} message={message !== undefined ? t(message) : undefined} /></div> : null}
      </section>
    </section>
  );
}

/** One step that is gone: pick where its waiting cases go, or cancel their work and continue elsewhere with a reason. */
function StepChoiceRow({ node, target, value, onChange }: {
  node: DiagramGraph['nodes'][number]; target: DiagramGraph; value: { choice: string; reason: string }; onChange: (next: Partial<{ choice: string; reason: string }>) => void;
}) {
  const { t } = useI18n();
  const selectId = useId();
  const reasonId = useId();
  const label = node.name ?? node.id;
  const sameType = target.nodes.filter((item) => item.type === node.type);
  const continues = target.nodes.filter((item) => item.type !== 'startEvent');
  const stopping = value.choice.startsWith('stop:');
  return (
    <tr>
      <th scope="row">
        <span className="builder__step">{label}</span>
        <span className="builder__type muted">{nodeTypeLabel(node.type, t)}</span>
      </th>
      <td>
        <label className="sr-only" htmlFor={selectId}>{`${t('migrate.steps.col.action')}: ${label}`}</label>
        <select id={selectId} className="field__input" value={value.choice} aria-invalid={value.choice === ''} onChange={(event) => { onChange({ choice: event.target.value }); }}>
          <option value="">{t('migrate.choose')}</option>
          {sameType.length > 0 ? (
            <optgroup label={t('migrate.group.move')}>
              {sameType.map((item) => <option key={item.id} value={`move:${item.id}`}>{item.name ?? item.id}</option>)}
            </optgroup>
          ) : null}
          <optgroup label={t('migrate.group.stop')}>
            {continues.map((item) => <option key={item.id} value={`stop:${item.id}`}>{item.name ?? item.id}</option>)}
          </optgroup>
        </select>
        {stopping ? (
          <label className="field builder__reason" htmlFor={reasonId}>
            <span className="field__label">{t('migrate.stopReason')}</span>
            <input id={reasonId} className="field__input" type="text" value={value.reason} maxLength={2000} placeholder={t('migrate.stopReasonHint')} onChange={(event) => { onChange({ reason: event.target.value }); }} />
          </label>
        ) : null}
      </td>
    </tr>
  );
}
