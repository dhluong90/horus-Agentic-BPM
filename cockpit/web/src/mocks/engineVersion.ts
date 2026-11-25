import packageJson from '../../package.json' with { type: 'json' };

/**
 * The engine version that mock hosts and test fixtures advertise. Every module ships under one project version, and
 * the cockpit supports engines of its own release line (CKP-014), so the fixtures follow the cockpit's own
 * package.json and need no edit on a version bump. Tests of the skew gate itself name their versions explicitly.
 */
export const ENGINE_VERSION: string = packageJson.version;
