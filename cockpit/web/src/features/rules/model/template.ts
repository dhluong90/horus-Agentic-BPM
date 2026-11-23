import type { RuleDocument } from '../../../api/rules/rulesClient';
import type { RuleSetTemplate } from '../../../api/rules/schemas';

/** The rule set name as the document schema writes it: kebab-case, starting with a letter. */
export const NAME_PATTERN = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/;
export const NAME_MAX = 64;
export const DISPLAY_NAME_MAX = 100;

/**
 * A name made from the words a person typed: "Card annual fee" gives card-annual-fee. Accents are dropped (Vietnamese
 * "Phí thẻ" gives phi-the), so the key stays plain ASCII; the display name keeps them. Empty when no letter or digit remains.
 */
export function suggestName(displayName: string): string {
  const plain = displayName.normalize('NFD').replace(/\p{M}/gu, '').replaceAll('đ', 'd').replaceAll('Đ', 'D').toLowerCase();
  const slug = plain.replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').replace(/^[0-9-]+/, '').slice(0, NAME_MAX).replace(/-+$/, '');
  return slug;
}

/** The first problem with a name, or undefined; only the format is checked here, a name already in use is the engine's answer. */
export function nameProblem(name: string): 'empty' | 'format' | undefined {
  if (name === '') return 'empty';
  return NAME_PATTERN.test(name) && name.length <= NAME_MAX ? undefined : 'format';
}

/**
 * The document that creates a rule set from a template: the template's hit policy, columns and first rows under the
 * name and display name the author chose. The engine checks it as any imported document (JSON is YAML), so a template
 * it lists always composes into one it accepts.
 */
export function composeDocument(template: RuleSetTemplate, name: string, displayName: string): RuleDocument {
  const document = {
    schemaVersion: 1,
    ruleSet: name,
    displayName,
    hitPolicy: template.hitPolicy,
    inputs: template.inputs.map((input) => ({ name: input.name, type: input.type, ...(input.allowed.length > 0 ? { allowed: input.allowed } : {}) })),
    outputs: template.outputs.map((output) => ({ name: output.name, type: output.type })),
    rules: template.rows.map((row) => ({ id: row.id, when: row.when, then: row.then })),
  };
  return { contentType: 'application/yaml', text: JSON.stringify(document, null, 2) };
}

/** The text in the user's language, else English, else the first the host gave. */
export function inLanguage(texts: Readonly<Record<string, string>>, locale: string): string {
  return texts[locale] ?? texts['en'] ?? Object.values(texts)[0] ?? '';
}
