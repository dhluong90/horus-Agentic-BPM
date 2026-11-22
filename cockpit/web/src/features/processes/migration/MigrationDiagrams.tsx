import { useMemo } from 'react';
import { useI18n } from '../../../i18n/I18nProvider';
import type { Mark } from '../model/graphDiff';
import type { DiagramGraph } from '../model/bpmnGraph';
import DefinitionDiagram, { type DiagramOverlay, type StepBadge } from '../diagram/DefinitionDiagram';
import { DOCK_RESERVE } from '../approval/dock';

/** The docked next step here is taller than a decision: it may hold a reason field, so more room is kept for it. */
const RESERVE = DOCK_RESERVE + 40;
import { diagramMarks, type StepRow } from './model/migration';
import { counted } from './model/counted';

/*
 * The migration plan as two diagrams side by side (CKP-027), each drawn by the same diagram as the change view: the
 * version the cases are on now, and the version they move to. A step the plan carries over is marked on both sides;
 * a step it does not cover is marked on the old one. A badge gives the cases waiting there once the plan is checked.
 * Picking a row of the list, or a step on either diagram, outlines the pair: that is how the two are linked.
 */

export default function MigrationDiagrams({ source, target, sourceVersion, targetVersion, rows, selected, onSelect }: {
  source: DiagramGraph;
  target: DiagramGraph;
  sourceVersion: number;
  targetVersion: number;
  rows: readonly StepRow[];
  /** The id, in the old version, of the picked row. */
  selected: string | undefined;
  onSelect: (rowId: string | undefined) => void;
}) {
  const { t } = useI18n();
  const marks = useMemo(() => diagramMarks(rows), [rows]);
  // Until one is picked, the first changed step is the pair both diagrams open on, so the carried-over steps are in view.
  const row = rows.find((item) => item.id === selected) ?? rows[0];
  const markLabel = (mark: Mark) => (mark === 'removed' ? t('migration.mark.removed') : t(mark === 'added' ? 'migration.mark.added' : 'migration.mark.changed'));

  const sourceBadges = useMemo(() => {
    const badges = new Map<string, StepBadge>();
    for (const item of rows) {
      if (item.cases === undefined) continue;
      const unmapped = item.state === 'unmapped';
      badges.set(item.id, { tone: unmapped ? 'warn' : 'token', icon: unmapped ? '!' : '●', text: String(item.cases), label: t(counted('migration.badge.cases', item.cases), { count: String(item.cases) }) });
    }
    return badges;
  }, [rows, t]);
  const targetBadges = useMemo(() => {
    const arriving = new Map<string, number>();
    for (const item of rows) if (item.to !== undefined && item.cases !== undefined) arriving.set(item.to, (arriving.get(item.to) ?? 0) + Math.max(0, item.cases - item.inFlight));
    const badges = new Map<string, StepBadge>();
    for (const [id, count] of arriving) badges.set(id, { tone: 'token', icon: '●', text: String(count), label: t(counted('migration.badge.arrive', count), { count: String(count) }) });
    return badges;
  }, [rows, t]);

  const sourceOverlay: DiagramOverlay = { badges: sourceBadges, selected: row?.id, onSelect: (id) => { onSelect(rows.some((item) => item.id === id) ? id : undefined); } };
  const targetOverlay: DiagramOverlay = { badges: targetBadges, selected: row?.to, onSelect: (id) => { onSelect(rows.find((item) => item.to === id)?.id); } };
  const side = (version: number, label: 'migration.side.now' | 'migration.side.new') => <span className="mdiagrams__side">{t(label, { version: String(version) })}</span>;

  return (
    <div className="mdiagrams">
      <div className="mdiagrams__pane">
        <DefinitionDiagram graph={source} marks={marks.source} markLabel={markLabel} overlay={sourceOverlay} toolbar={side(sourceVersion, 'migration.side.now')} reserve={RESERVE} fill
          label={t('migration.diagramLabel', { version: String(sourceVersion), count: String(source.nodes.length) })} />
      </div>
      <div className="mdiagrams__pane">
        <DefinitionDiagram graph={target} marks={marks.target} markLabel={markLabel} overlay={targetOverlay} toolbar={side(targetVersion, 'migration.side.new')} reserve={RESERVE} fill
          label={t('migration.diagramLabel', { version: String(targetVersion), count: String(target.nodes.length) })} />
      </div>
    </div>
  );
}
