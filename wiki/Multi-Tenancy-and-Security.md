# Multi-Tenancy and Security

## Tenancy
- Every table carries `tenant_id`; every query filters on it. The engine takes the tenant from the host's security context (default), from a trusted header behind a gateway, or from a custom resolver bean. It never takes it from a request body.
- Rule sets, versions, decisions, process definitions and instances are tenant-scoped. There is no cross-tenant read path.

## Authentication and authorisation
The engines add no authentication of their own; they rely on the host's Spring Security. The REST layer maps to the permissions listed in [[Rule Engine Configuration]]. Maker-checker is enforced in the service layer.

## No code execution
Expressions are parsed into an evaluation tree; there is no scripting engine, no reflection, no class loading from definitions. Host-registered functions are the only extension point and are listed in the cockpit.

## Data protection
Inputs marked `mask: true` are stored as keyed hashes in the decision log. Logs and history are append-only; retention is the host's responsibility (monthly partitions are provided).

## Supply chain
Permissively licensed third-party dependencies only (Apache 2.0, MIT, BSD); SBOM published with each release; container images for the examples are non-root and scanned. Report vulnerabilities as described in the repository's `SECURITY.md`.
