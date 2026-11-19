import { describe, expect, it } from 'vitest';
import type { BpmMetaV1 } from '../api/generated/bpm-meta.v1';
import { cockpitDraftsOn } from './AppFrame';

function meta(settings?: Record<string, boolean | number | string>): BpmMetaV1 {
  return {
    schemaVersion: 1,
    modules: [{ module: 'rules', engineVersion: '0.1.0', apiVersion: 'v1', features: [], ...(settings ? { settings } : {}) }],
    tenancy: { switching: false },
    limits: { maxPageSize: 200 },
  };
}

describe('cockpit draft editing setting', () => {
  it('PD_094_draft_editing_is_off_when_the_host_reports_no_setting', () => {
    expect(cockpitDraftsOn(meta())).toBe(false);
    expect(cockpitDraftsOn(meta({}))).toBe(false);
  });

  it('PD_094_draft_editing_is_on_only_when_the_host_says_true', () => {
    expect(cockpitDraftsOn(meta({ 'authoring.cockpit-drafts': true }))).toBe(true);
    expect(cockpitDraftsOn(meta({ 'authoring.cockpit-drafts': 'true' }))).toBe(true);
    expect(cockpitDraftsOn(meta({ 'rules.authoring.cockpit-drafts': true }))).toBe(true);
    expect(cockpitDraftsOn(meta({ 'authoring.cockpit-drafts': false }))).toBe(false);
    expect(cockpitDraftsOn(meta({ 'authoring.cockpit-drafts': 'yes' }))).toBe(false);
  });
});
