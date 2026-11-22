import { describe, expect, it } from 'vitest';
import { z } from 'zod';
import { callHostIfChanged } from './hostCall';
import { HostCallError } from './hostError';

const schema = z.object({ n: z.number() });
const request = { method: 'GET' as const, path: '/api/bpm/processes/v1/tasks' };

describe('CKP-019 conditional list reads', () => {
  it('CKP_019_a_held_etag_goes_out_as_if_none_match_and_a_304_is_unchanged', async () => {
    const seen: Record<string, string>[] = [];
    const fetchImpl = ((_url: string, init: RequestInit) => {
      seen.push(init.headers as Record<string, string>);
      return Promise.resolve(new Response(null, { status: 304, headers: { ETag: '"v1"' } }));
    }) as unknown as typeof fetch;
    const answer = await callHostIfChanged(fetchImpl, 'https://loans.test', request, schema, '"v1"');
    expect(answer).toEqual({ changed: false, etag: '"v1"' });
    expect(seen[0]?.['If-None-Match']).toBe('"v1"');
  });

  it('CKP_019_no_held_etag_sends_no_if_none_match_and_reads_the_body', async () => {
    const seen: Record<string, string>[] = [];
    const fetchImpl = ((_url: string, init: RequestInit) => {
      seen.push(init.headers as Record<string, string>);
      return Promise.resolve(Response.json({ n: 1 }, { headers: { ETag: '"v2"' } }));
    }) as unknown as typeof fetch;
    const answer = await callHostIfChanged(fetchImpl, 'https://loans.test', request, schema, undefined);
    expect(answer).toEqual({ changed: true, etag: '"v2"', data: { n: 1 } });
    expect(seen[0]).not.toHaveProperty('If-None-Match');
  });

  it('CKP_019_a_host_without_an_etag_answers_in_full_and_keeps_no_etag', async () => {
    const fetchImpl = (() => Promise.resolve(Response.json({ n: 3 }))) as unknown as typeof fetch;
    expect(await callHostIfChanged(fetchImpl, 'https://loans.test', request, schema, '"old"')).toEqual({ changed: true, etag: undefined, data: { n: 3 } });
  });

  it('CKP_019_a_redirect_is_still_a_signed_out_session_not_an_unchanged_page', async () => {
    const fetchImpl = (() => Promise.resolve(new Response(null, { status: 302 }))) as unknown as typeof fetch;
    await expect(callHostIfChanged(fetchImpl, 'https://loans.test', request, schema, undefined)).rejects.toBeInstanceOf(HostCallError);
  });
});
