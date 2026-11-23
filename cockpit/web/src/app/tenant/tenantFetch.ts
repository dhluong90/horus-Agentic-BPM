import type { BpmMeV1 } from '../../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../../api/generated/bpm-meta.v1';

/** A tenant the host refused: the tenant the cockpit asked for and the reference of the refusal. */
export interface TenantRefusal {
  tenant: string;
  correlationId: string | undefined;
}

/**
 * The tenants the switcher may offer (CKP-022): only when the host reports switching on, and only the tenants its
 * /me lists from the verified token's allowed-tenants claim. One tenant, or switching off, offers nothing.
 */
export function switchableTenants(meta: BpmMetaV1, me: BpmMeV1): readonly string[] {
  if (!meta.tenancy.switching) return [];
  // Only what the token allows: the cockpit never adds a tenant of its own, so a development tenant the token does
  // not list (the host fixes it in the dev profile only) is never offered.
  return me.tenant.allowed.length > 1 ? me.tenant.allowed : [];
}

/** The host's display name of a tenant from /me (#474); the id itself when the host gives none. */
export function tenantLabel(me: BpmMeV1, tenant: string): string {
  return me.tenant.names?.[tenant] ?? tenant;
}

/**
 * A fetch for one host that selects `tenant` with `X-Tenant-Id` (ADR-0014). Without a selection no header is
 * sent and the host uses the token's own tenant. A `403 TENANT_NOT_ALLOWED` answer is reported once to
 * `onRefused`, and the answer still reaches the screen that asked.
 */
export function tenantFetch(base: typeof fetch, tenant: string | undefined, onRefused: (refusal: TenantRefusal) => void): typeof fetch {
  if (tenant === undefined) return base;
  return async (input, init) => {
    const headers = new Headers(init?.headers);
    headers.set('X-Tenant-Id', tenant);
    const response = await base(input, { ...init, headers });
    if (response.status === 403) {
      const body: unknown = await response.clone().json().catch(() => undefined);
      if (isRecord(body) && body['code'] === 'TENANT_NOT_ALLOWED') {
        onRefused({ tenant, correlationId: typeof body['correlationId'] === 'string' ? body['correlationId'] : undefined });
      }
    }
    return response;
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
