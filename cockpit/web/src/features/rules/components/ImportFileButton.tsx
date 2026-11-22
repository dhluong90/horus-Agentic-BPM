import { useRef, type ReactNode } from 'react';
import type { RuleDocument } from '../../../api/rules/rulesClient';
import type { PickedSheet } from '../api/useRules';

const DMN = /\.(dmn|xml)$/i;
const CSV = /\.csv$/i;

async function readSheet(file: File): Promise<PickedSheet> {
  // The bytes go to the engine exactly as picked: the engine reads the encoding, so nothing is decoded here.
  const bytes = await file.arrayBuffer();
  return { bytes, fileName: file.name, fingerprint: fingerprintOf(new Uint8Array(bytes)) };
}

/** cyrb53 over the bytes plus their count: enough to tell two files apart for one Idempotency-Key (no secure context needed). */
function fingerprintOf(data: Uint8Array): string {
  let h1 = 0xdeadbeef;
  let h2 = 0x41c6ce57;
  for (const byte of data) {
    h1 = Math.imul(h1 ^ byte, 2654435761);
    h2 = Math.imul(h2 ^ byte, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return `${String(data.length)}-${(h2 >>> 0).toString(16)}${(h1 >>> 0).toString(16)}`;
}

async function readDocument(file: File): Promise<RuleDocument> {
  const text = await file.text();
  // A DMN decision table is XML; the engine reads it into the same checks, hash and approval as YAML.
  if (DMN.test(file.name)) return { contentType: 'application/xml', text };
  // This engine release imports YAML only and refuses application/json. JSON text is valid YAML 1.2, so a
  // .json file is sent as YAML until the engine accepts JSON; then send it as application/json.
  return { contentType: 'application/yaml', text };
}

/**
 * A hidden file input for YAML, JSON or DMN and a function that opens it; one request per pick. With `onSheet` it
 * also takes a spreadsheet (.csv), handed on as its bytes for the user to confirm before anything is sent.
 */
export function useFilePicker(onDocument: ((document: RuleDocument, fileName: string) => void) | undefined, onSheet?: (sheet: PickedSheet) => void): { input: ReactNode; open: () => void } {
  const fileInput = useRef<HTMLInputElement>(null);
  const input = (
    <input ref={fileInput} type="file" accept={[onDocument ? '.yaml,.yml,.json,.dmn,.xml' : '', onSheet ? '.csv' : ''].filter(Boolean).join(',')} className="sr-only" tabIndex={-1} aria-hidden="true"
      onChange={(e) => {
        const file = e.target.files?.[0];
        e.target.value = '';
        if (file && onSheet && CSV.test(file.name)) void readSheet(file).then(onSheet);
        else if (file && onDocument) void readDocument(file).then((document) => { onDocument(document, file.name); });
      }} />
  );
  return { input, open: () => fileInput.current?.click() };
}

/** A button that picks a YAML, JSON or DMN file and hands its document on. */
export function ImportFileButton({ label, busyLabel, busy, primary, onDocument }: {
  label: string;
  busyLabel: string;
  busy: boolean;
  primary: boolean;
  onDocument: (document: RuleDocument, fileName: string) => void;
}) {
  const picker = useFilePicker(onDocument);
  return (
    <>
      {picker.input}
      <button type="button" className={primary ? 'btn btn--primary' : 'btn'} disabled={busy} aria-busy={busy} onClick={picker.open}>
        {busy ? busyLabel : label}
      </button>
    </>
  );
}
