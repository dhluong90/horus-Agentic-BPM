/*
 * Characters outside ASCII in a literal, for the approver (ADR-0026): a Cyrillic "О" in "GОLD" reads like
 * GOLD but is not. Each character is named by its code point so a reviewer can tell look-alikes apart.
 */
export function nonAsciiCharacters(text: string, locale: string = 'en'): string[] {
  const found = new Set<string>();
  for (const character of text) {
    const code = character.codePointAt(0) ?? 0;
    if (code <= 0x7f) continue;
    const point = `U+${code.toString(16).toUpperCase().padStart(4, '0')}`;
    const script = scriptName(character, locale);
    found.add(script === undefined ? `${character} (${point})` : `${script} ${character} (${point})`);
  }
  return [...found];
}

const SCRIPTS: readonly { test: RegExp; code: string }[] = [
  { test: /\p{Script=Latin}/u, code: 'Latn' },
  { test: /\p{Script=Cyrillic}/u, code: 'Cyrl' },
  { test: /\p{Script=Greek}/u, code: 'Grek' },
  { test: /\p{Script=Armenian}/u, code: 'Armn' },
  { test: /\p{Script=Hebrew}/u, code: 'Hebr' },
  { test: /\p{Script=Arabic}/u, code: 'Arab' },
  { test: /\p{Script=Han}/u, code: 'Hani' },
  { test: /\p{Script=Hiragana}/u, code: 'Hira' },
  { test: /\p{Script=Katakana}/u, code: 'Kana' },
  { test: /\p{Script=Hangul}/u, code: 'Hang' },
  { test: /\p{Script=Thai}/u, code: 'Thai' },
  { test: /\p{Script=Devanagari}/u, code: 'Deva' },
];

/** The script of a character in the reader's language ("Cyrillic"), so a look-alike is named before its code point. */
function scriptName(character: string, locale: string): string | undefined {
  const script = SCRIPTS.find((candidate) => candidate.test.test(character));
  if (script === undefined) return undefined;
  const name = new Intl.DisplayNames([locale], { type: 'script' }).of(script.code);
  return name === undefined || name === script.code ? undefined : name;
}

/** The text of a cell as stored, for scanning: conditions and outputs as the engine keeps them. */
export function cellText(value: unknown): string {
  if (value === undefined || value === null) return '';
  return typeof value === 'string' ? value : JSON.stringify(value);
}
