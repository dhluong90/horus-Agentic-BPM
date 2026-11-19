import { describe, expect, it } from 'vitest';
import { auditKeywords } from './generate-schemas.mjs';

describe('schema generator keyword audit', () => {
  it('TR_UI_01_a_keyword_the_generator_would_drop_stops_generation', () => {
    const schema = { type: 'object', properties: { a: { type: 'string', format: 'uri' } } };
    expect(() => auditKeywords('x.json', schema, {})).toThrow(/properties\/a\/format/);
  });

  it('TR_UI_01_if_then_and_required_on_a_map_are_refused_unless_mapped_to_a_refinement', () => {
    const schema = {
      type: 'object',
      if: { properties: { mode: { const: 'oidc' } } },
      then: { required: ['issuer'] },
      properties: { label: { type: 'object', required: ['en'], additionalProperties: { type: 'string' } } },
    };
    expect(() => auditKeywords('x.json', schema, {})).toThrow(/\/if/);
    expect(auditKeywords('x.json', schema, {
      'x.json': { '/if': 'r-oidc', '/then': 'r-oidc', '/properties/label/required': 'r-en' },
    })).toEqual(['r-en', 'r-oidc']);
  });

  it('TR_UI_01_a_known_gap_that_left_the_schema_is_reported_as_stale', () => {
    expect(() => auditKeywords('x.json', { type: 'string' }, { 'x.json': { '/if': 'r' } })).toThrow(/no longer in the schema/);
  });
});
