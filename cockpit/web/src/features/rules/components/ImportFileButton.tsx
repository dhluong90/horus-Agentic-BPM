import { useRef, type ReactNode } from 'react';
import type { RuleDocument } from '../../../api/rules/rulesClient';

const DMN = /\.(dmn|xml)$/i;

async function readDocument(file: File): Promise<RuleDocument> {
  const text = await file.text();
  // A DMN decision table is XML; the engine reads it into the same checks, hash and approval as YAML.
  if (DMN.test(file.name)) return { contentType: 'application/xml', text };
  // This engine release imports YAML only and refuses application/json. JSON text is valid YAML 1.2, so a
  // .json file is sent as YAML until the engine accepts JSON; then send it as application/json.
  return { contentType: 'application/yaml', text };
}

/** A hidden file input for YAML, JSON or DMN and a function that opens it; one request per pick. */
export function useFilePicker(onDocument: (document: RuleDocument) => void): { input: ReactNode; open: () => void } {
  const fileInput = useRef<HTMLInputElement>(null);
  const input = (
    <input ref={fileInput} type="file" accept=".yaml,.yml,.json,.dmn,.xml" className="sr-only" tabIndex={-1} aria-hidden="true"
      onChange={(e) => {
        const file = e.target.files?.[0];
        e.target.value = '';
        if (file) void readDocument(file).then(onDocument);
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
  onDocument: (document: RuleDocument) => void;
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
