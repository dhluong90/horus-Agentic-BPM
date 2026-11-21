import { describe, expect, it } from 'vitest';
import contract from './engineReadings.contract.json';
import { readRule, type ReadableDefinition } from './ruleReadings';

// The cockpit mock must read rows the way the engine does (PD-027). The sample is the engine's own expected
// output for the card-fee table; update it from RuleReadingsTest when the engine's wording changes.
describe('CKP_076 the mock reader matches the engine contract sample', () => {
  const definition = contract.definition as unknown as ReadableDefinition;
  for (const [id, languages] of Object.entries(contract.expected)) {
    it(`reads ${id} as the engine does`, () => {
      const rule = definition.rules.find((r) => r.id === id);
      if (rule === undefined) throw new Error(id);
      const readings = readRule(rule, definition);
      for (const [language, texts] of Object.entries(languages) as [('en' | 'vi'), { short?: string; full?: string }][]) {
        if (texts.short !== undefined) expect(readings[language].short).toBe(texts.short);
        if (texts.full !== undefined) expect(readings[language].full).toBe(texts.full);
      }
    });
  }
  it('never writes a symbol, an arrow or an upper-case code in a short form', () => {
    for (const rule of definition.rules) for (const language of ['en', 'vi'] as const) {
      expect(readRule(rule, definition)[language].short).not.toMatch(/=|->|≥|≤|'|GOLD|FIRST_TIME/);
    }
  });
});
