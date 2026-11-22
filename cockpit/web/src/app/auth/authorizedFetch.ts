import { HostCallError } from '../../api/hostError';
import { TokenError } from './oidc';

/**
 * A fetch for one host in standalone mode: every request carries that host's bearer token and no cookie, so the
 * existing clients work unchanged and a token never reaches another host (cockpit DESIGN section 5.1, TR-SEC-12).
 * A token that cannot be had becomes the host error the screens already show.
 */
export function authorizedFetch(base: typeof fetch, token: () => Promise<string>): typeof fetch {
  return async (input, init) => {
    let value: string;
    try {
      value = await token();
    } catch (cause) {
      if (cause instanceof TokenError) {
        if (cause.problem.reason === 'no-access') throw new HostCallError({ kind: 'no-access' });
        if (cause.problem.reason === 'session-ended') throw new HostCallError({ kind: 'signed-out' });
      }
      throw new HostCallError({ kind: 'unreachable' });
    }
    const headers = new Headers(init?.headers);
    headers.set('Authorization', `Bearer ${value}`);
    // The host's CSRF token belongs to a session cookie; a bearer request has neither.
    headers.delete('X-XSRF-TOKEN');
    return base(input, { ...init, headers, credentials: 'omit' });
  };
}
