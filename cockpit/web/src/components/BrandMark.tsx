export function BrandMark() {
  return (
    <svg className="brand-mark" viewBox="0 0 28 28" aria-hidden="true" focusable="false">
      <rect className="brand-mark__tile" x="1" y="1" width="26" height="26" rx="7" />
      <path className="brand-mark__glyph" d="M8 7v14M20 7v14M8 14h12" strokeWidth="2.4" strokeLinecap="round" />
      <circle className="brand-mark__dot" cx="14" cy="14" r="2.4" />
    </svg>
  );
}
