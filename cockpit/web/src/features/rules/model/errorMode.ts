import type { MessageKey } from '../../../i18n/en';

/** The words for an error mode: what people read and the one line that explains it (RLE-236). The code is never shown. */
export interface ErrorModeWords {
  label: MessageKey;
  explanation: MessageKey;
}

/**
 * A mode this cockpit knows reads in words; one it does not know (a later engine) reads as a newer mode, never as its
 * code. A row that carries no mode (an older engine) shows nothing.
 */
export function errorModeWords(mode: string | undefined): ErrorModeWords | undefined {
  switch (mode) {
    case undefined: return undefined;
    case 'strict': return { label: 'errorMode.strict', explanation: 'errorMode.strict.why' };
    case 'spec': return { label: 'errorMode.spec', explanation: 'errorMode.spec.why' };
    case 'unknown': return { label: 'errorMode.unknown', explanation: 'errorMode.unknown.why' };
    default: return { label: 'errorMode.other', explanation: 'errorMode.other.why' };
  }
}
