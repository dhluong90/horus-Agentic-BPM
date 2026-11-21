import type { BpmMetaV1 } from '../../api/generated/bpm-meta.v1';

declare const __COCKPIT_VERSION__: string;

/** This cockpit's release, from package.json at build time. */
export const COCKPIT_VERSION: string = typeof __COCKPIT_VERSION__ === 'string' ? __COCKPIT_VERSION__ : '0.0.0';

interface MajorMinor {
  major: number;
  minor: number;
}

function majorMinor(version: string): MajorMinor | undefined {
  const match = /^(\d+)\.(\d+)(?:[.-]|$)/.exec(version);
  return match ? { major: Number(match[1]), minor: Number(match[2]) } : undefined;
}

/** The engine lines this cockpit supports: its own minor and the one before (TR-UI-08, DESIGN section 5.2). */
export function supportedLines(cockpitVersion: string = COCKPIT_VERSION): string[] {
  const own = majorMinor(cockpitVersion);
  if (!own) return [];
  const lines = own.minor > 0 ? [own.minor - 1, own.minor] : [own.minor];
  return lines.map((minor) => `${String(own.major)}.${String(minor)}`);
}

export type VersionCheck = { supported: true } | { supported: false; engineVersion: string; lines: string[] };

/**
 * A host is supported when every engine it reports is on a supported line. Within the range, screens follow the
 * features in /meta, never version numbers.
 */
export function checkVersion(meta: BpmMetaV1, cockpitVersion: string = COCKPIT_VERSION): VersionCheck {
  const lines = supportedLines(cockpitVersion);
  for (const module of meta.modules) {
    const line = majorMinor(module.engineVersion);
    const name = line ? `${String(line.major)}.${String(line.minor)}` : '';
    if (!lines.includes(name)) return { supported: false, engineVersion: module.engineVersion, lines };
  }
  return { supported: true };
}
