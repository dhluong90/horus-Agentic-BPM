import { useRef } from 'react';
import type { RuleDocument } from '../../../api/rules/rulesClient';

async function readDocument(file: File): Promise<RuleDocument> {
  const text = await file.text();
  // This engine release imports YAML only and refuses application/json. JSON text is valid YAML 1.2, so a
  // .json file is sent as YAML until the engine accepts JSON; then send it as application/json.
  return { contentType: 'application/yaml', text };
}

/** A button that picks a YAML or JSON file and hands its document on; one request per pick. */
export function ImportFileButton({ label, busyLabel, busy, primary, onDocument }: {
  label: string;
  busyLabel: string;
  busy: boolean;
  primary: boolean;
  onDocument: (document: RuleDocument) => void;
}) {
  const fileInput = useRef<HTMLInputElement>(null);
  return (
    <>
      <input ref={fileInput} type="file" accept=".yaml,.yml,.json" className="sr-only" tabIndex={-1} aria-hidden="true"
        onChange={(e) => {
          const file = e.target.files?.[0];
          e.target.value = '';
          if (file) void readDocument(file).then(onDocument);
        }} />
      <button type="button" className={primary ? 'btn btn--primary' : 'btn'} disabled={busy} onClick={() => fileInput.current?.click()}>
        {busy ? busyLabel : label}
      </button>
    </>
  );
}
