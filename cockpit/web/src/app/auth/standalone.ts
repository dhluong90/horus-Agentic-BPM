import type { HostRegistry, RegistryHost } from '../config/registry';
import type { OidcSettings } from './oidc';

/*
 * Where the standalone cockpit lives and what it asks the identity provider for (cockpit DESIGN sections 4 and
 * 5.1). The sign-in callback is the page `auth.redirectPath` below the cockpit; the build ships that page
 * (auth/callback/index.html) with the same application.
 */

function trimSlashes(path: string): string {
  return path.replace(/^\/+|\/+$/g, '');
}

/** True when `pathname` is the sign-in callback page, with or without its trailing slash. */
export function isCallbackPath(pathname: string, redirectPath: string): boolean {
  const callback = trimSlashes(redirectPath);
  return callback !== '' && (pathname.endsWith(`/${callback}`) || pathname.endsWith(`/${callback}/`));
}

export function enabledHosts(registry: HostRegistry): RegistryHost[] {
  return registry.hosts.filter((host) => host.enabled);
}

/**
 * The sign-in request asks once for every enabled host's scope, so each host's token can then be had with a
 * refresh grant naming that scope alone (per-host mode). `openid` is added by the client.
 */
export function oidcSettings(registry: HostRegistry, origin: string, root: string): OidcSettings {
  const auth = registry.auth;
  const hostScopes = auth.tokenMode === 'per-host' ? enabledHosts(registry).flatMap((host) => (host.scope === undefined ? [] : [host.scope])) : [];
  return {
    issuer: auth.issuer ?? '',
    clientId: auth.clientId ?? '',
    scopes: [...new Set([...(auth.scopes ?? []), ...hostScopes])],
    // The callback is a directory page (auth/callback/index.html), so its address ends with a slash: any static
    // server then serves that page, and its relative assets resolve, at any cockpit path.
    redirectUri: `${origin}${root}${trimSlashes(auth.redirectPath)}/`,
    postLogoutRedirectUri: `${origin}${root}${trimSlashes(auth.postLogoutRedirectPath)}`,
  };
}

/** The scope a host's token is requested with: its own in per-host mode, none (the sign-in token) in shared mode. */
export function tokenScope(registry: HostRegistry, host: RegistryHost): string | undefined {
  return registry.auth.tokenMode === 'per-host' ? host.scope : undefined;
}
