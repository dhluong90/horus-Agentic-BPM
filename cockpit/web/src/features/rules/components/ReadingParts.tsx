import type { ReactNode } from 'react';
import { toHostError } from '../../../api/hostError';
import type { ReadingsByLanguage, RuleSetDefinition } from '../../../api/rules/schemas';
import { AreaError, LoadingRows } from '../../../components/PageParts';
import type { MessageKey } from '../../../i18n/en';
import { useI18n } from '../../../i18n/I18nProvider';
import { useReadingStyle } from '../../../preferences/readingStyle';
import type { ReadingStyle } from '../../../preferences/storage';
import { useRulesSession } from '../api/rulesContext';

/*
 * The pieces every reading screen shares. The engine writes each sentence in each language (PD-027); the cockpit
 * picks the user's language and the viewer's style, and never builds or edits a sentence.
 */

const STYLES: readonly ReadingStyle[] = ['full', 'short'];

/** The viewer's style, with the host's setting as the default until the viewer picks one. */
export function useViewerReadingStyle(): [ReadingStyle, (style: ReadingStyle) => void] {
  const { readingStyle } = useRulesSession();
  return useReadingStyle(readingStyle);
}

/** Sentence | Short. The choice is remembered in this browser and applies to every reading on screen. */
export function ReadingStyleSwitch() {
  const { t } = useI18n();
  const [style, setStyle] = useViewerReadingStyle();
  return (
    <div className="seg reading-style" role="group" aria-label={t('readings.style')}>
      {STYLES.map((option) => (
        <button key={option} type="button" className="hit" aria-pressed={style === option} onClick={() => { setStyle(option); }}>
          {t(option === 'full' ? 'readings.full' : 'readings.short')}
        </button>
      ))}
    </div>
  );
}

/** The text of a reading in the user's language and the chosen style; undefined when the engine has no such language. */
export function readingIn(readings: ReadingsByLanguage | null | undefined, locale: string, style: ReadingStyle): string | undefined {
  const reading = readings?.[locale];
  if (reading === undefined) return undefined;
  return style === 'full' ? reading.full : reading.short;
}

/** The inputs whose values the decision log masks, as declared in the version. */
export function maskedInputs(definition: RuleSetDefinition): ReadonlySet<string> {
  return new Set(definition.inputs.filter((input) => input.mask === true).map((input) => input.name));
}

/** True when a row tests a masked input: its reading names the input and never writes the test. */
export function testsMasked(when: Readonly<Record<string, unknown>> | undefined, masked: ReadonlySet<string>): boolean {
  if (when === undefined) return false;
  return Object.entries(when).some(([name, cell]) => masked.has(name) && !(typeof cell === 'string' && cell.trim() === ''));
}

/** A quiet line under a reading whose row tests masked personal data. */
export function MaskedNote() {
  const { t } = useI18n();
  return (
    <p className="reading__note">
      <span aria-hidden="true">◐</span> {t('readings.masked')}
    </p>
  );
}

/** A calm note, not an error: the engine reads a bounded number of rows per request, and nothing was cut short. */
export function TooLargeNote({ text, action }: { text: MessageKey; action?: { label: string; onSelect: () => void } | undefined }) {
  const { t } = useI18n();
  return (
    <div className="alert alert--info reading-limit" role="status">
      <span className="alert__icon alert__icon--info" aria-hidden="true">ⓘ</span>
      <div className="alert__body">
        <p className="reading-limit__title">{t('readings.tooLarge.title')}</p>
        <p>{t(text)}</p>
      </div>
      {action !== undefined ? <button type="button" className="btn btn--quiet btn--small hit" onClick={action.onSelect}>{action.label}</button> : null}
    </div>
  );
}

/** One plain sentence, no retry: the service says this user may not read rows in plain words. */
export function ForbiddenNote({ text = 'readings.forbidden' }: { text?: MessageKey }) {
  const { t } = useI18n();
  return (
    <div className="empty-state reading-empty" role="status">
      <span className="empty-state__icon" aria-hidden="true">⊘</span>
      <p>{t(text)}</p>
    </div>
  );
}

export function EmptyNote({ text }: { text: MessageKey }) {
  const { t } = useI18n();
  return (
    <div className="empty-state reading-empty" role="status">
      <span className="empty-state__icon" aria-hidden="true">≡</span>
      <p>{t(text)}</p>
    </div>
  );
}

export type ReadingFailure = 'forbidden' | 'too-large' | 'other';

/** What a failed reading call means on screen. */
export function failureOf(error: unknown): ReadingFailure {
  const host = toHostError(error);
  if (host.kind === 'problem' && host.status === 403) return 'forbidden';
  if (host.kind === 'problem' && host.code === 'READINGS_TOO_LARGE') return 'too-large';
  return 'other';
}

/** The loading and failure states of a reading query; `ready` renders the answer. */
export function ReadingQueryState<T>({ query, rows = 4, tooLarge, ready }: {
  query: { isPending: boolean; isError: boolean; error: unknown; data: T | undefined; refetch: () => unknown };
  rows?: number;
  tooLarge: ReactNode;
  ready: (data: T) => ReactNode;
}) {
  if (query.isError) {
    const failure = failureOf(query.error);
    if (failure === 'forbidden') return <ForbiddenNote />;
    if (failure === 'too-large') return <>{tooLarge}</>;
    return <AreaError error={query.error} onRetry={() => { void query.refetch(); }} />;
  }
  if (query.isPending || query.data === undefined) return <LoadingRows rows={rows} />;
  return <>{ready(query.data)}</>;
}
