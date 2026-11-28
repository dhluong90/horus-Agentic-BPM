import type { MessageKey } from '../../../i18n/en';
import { readableName } from '../../tasks/model/queue';

/*
 * How a calendar and its time zone read in words. The engine names a calendar by its key and a zone by its IANA id;
 * neither is shown as written. The key reads in sentence case, as roles do (the engine sends no display name), and a
 * zone reads as its city with the offset from UTC at the given moment: "Ho Chi Minh City (UTC+7)".
 */

/** A calendar's display name from its key: "vn-banking-hours" reads "Vn banking hours". */
export const calendarName = readableName;

/** The calendar key a typed name makes: lower-case words and digits joined by hyphens, accents dropped. */
export function keyFromName(name: string): string {
  return name.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/gi, 'd').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
}

/** Cities whose common name differs from the last part of their zone id; the words live in the message files. */
export const ZONE_CITY_KEYS: Readonly<Record<string, MessageKey>> = {
  'Asia/Ho_Chi_Minh': 'zone.hoChiMinh',
  'Asia/Saigon': 'zone.hoChiMinh',
  'Asia/Singapore': 'zone.singapore',
  'Asia/Bangkok': 'zone.bangkok',
  UTC: 'zone.utc',
};

/** "UTC+7", "UTC+5:30", "UTC" for the zone at that moment; undefined for an id the runtime does not know. */
export function zoneOffset(zone: string, at: Date): string | undefined {
  try {
    const part = new Intl.DateTimeFormat('en', { timeZone: zone, timeZoneName: 'shortOffset' }).formatToParts(at).find((p) => p.type === 'timeZoneName')?.value;
    if (part === undefined) return undefined;
    return part === 'GMT' ? 'UTC' : part.replace('GMT', 'UTC').replace('−', '-');
  } catch {
    return undefined;
  }
}

/** The zone for people: its city and offset. `cityOf` gives the translated city for ids that have one. */
export function zoneLabel(zone: string, cityOf: (key: MessageKey) => string, at: Date = new Date()): string {
  const known = ZONE_CITY_KEYS[zone];
  const city = known !== undefined ? cityOf(known) : (zone.split('/').pop() ?? zone).replace(/_/g, ' ');
  const offset = zoneOffset(zone, at);
  return offset === undefined || offset === 'UTC' && known === 'zone.utc' ? city : `${city} (${offset})`;
}
