import { describe, expect, it } from 'vitest';
import { licenceViolations } from './licences.mjs';

describe('licence rule', () => {
  it('TR_LIC_01_mit_apache_bsd_and_isc_pass_and_dual_licences_pass_on_one_allowed_branch', () => {
    expect(licenceViolations([
      { name: 'react', version: '18.3.1', license: 'MIT' },
      { name: 'oidc-client-ts', version: '3.0.0', license: 'Apache-2.0' },
      { name: 'x', version: '1.0.0', license: 'BSD-3-Clause' },
      { name: 'd3-color', version: '3.1.0', license: 'ISC' },
      { name: 'y', version: '1.0.0', license: '(MIT OR GPL-3.0)' },
    ])).toEqual([]);
  });

  it('TR_LIC_01_copyleft_missing_and_unknown_licences_fail', () => {
    expect(licenceViolations([
      { name: 'a', version: '1.0.0', license: 'EPL-2.0' },
      { name: 'b', version: '1.0.0', license: undefined },
      { name: 'c', version: '1.0.0', license: 'MPL-2.0' },
    ])).toHaveLength(3);
  });

  it('TR_LIC_01_bpmn_js_elkjs_and_jointjs_are_rejected_by_name', () => {
    expect(licenceViolations([
      { name: 'bpmn-js', version: '17.0.0', license: 'SEE LICENSE IN LICENSE' },
      { name: 'elkjs', version: '0.9.0', license: 'EPL-2.0' },
      { name: 'jointjs', version: '3.7.0', license: 'MPL-2.0' },
    ]).map((problem) => problem.split(':')[0])).toEqual(['bpmn-js@17.0.0', 'elkjs@0.9.0', 'jointjs@3.7.0']);
  });
});
