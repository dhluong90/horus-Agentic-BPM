/*
 * Characters outside ASCII in a literal, for the approver (ADR-0026): a Cyrillic "О" in "GОLD" reads like
 * GOLD but is not. Each character is named by its code point so a reviewer can tell look-alikes apart.
 */
export function nonAsciiCharacters(text: string): string[] {
  const found = new Set<string>();
  for (const character of text) {
    const code = character.codePointAt(0) ?? 0;
    if (code > 0x7f) found.add(`${character} U+${code.toString(16).toUpperCase().padStart(4, '0')}`);
  }
  return [...found];
}

/** The text of a cell as stored, for scanning: conditions and outputs as the engine keeps them. */
export function cellText(value: unknown): string {
  if (value === undefined || value === null) return '';
  return typeof value === 'string' ? value : JSON.stringify(value);
}
