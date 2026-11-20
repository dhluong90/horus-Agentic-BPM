/**
 * Hands a file to the browser's download, under the given name, without reading or changing its bytes.
 * The object URL is released after the click has started the download.
 */
export function saveFile(file: Blob, fileName: string, doc: Document = document): void {
  const url = URL.createObjectURL(file);
  const link = doc.createElement('a');
  link.href = url;
  link.download = fileName;
  link.rel = 'noopener';
  link.hidden = true;
  doc.body.append(link);
  try {
    link.click();
  } finally {
    link.remove();
    window.setTimeout(() => { URL.revokeObjectURL(url); }, 0);
  }
}
