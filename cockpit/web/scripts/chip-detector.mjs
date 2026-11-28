/* global document, getComputedStyle */
// The rendered-DOM half of the "no chips" guard (the stylesheet half is check-no-chips.mjs). It runs inside the page
// and looks at what the browser drew, so it sees what a class-name check cannot: inline styles, SVG shapes, and classes
// named anything at all. It reports an element that has all of these:
//   - a corner radius of 4px or more,
//   - a fill or a border that is not transparent,
//   - its own short text with a letter or a digit in it (1 to 60 characters, at most 40px high; a lone glyph such as a
//     check mark is an icon, not a label),
//   - and is not a real control.
// A real control is a button, link, input, select, textarea or an element with a control role. In SVG, a rounded rect with
// a fill or a stroke and a text beside it is reported, except the BPMN symbols themselves (CHIP_EXEMPT below).
// Usage in a Playwright test: `await page.evaluate(detectChips, CHIP_EXEMPT)`, then expect the result to equal [].

/**
 * What the detector never reports, each by the element itself and why. An entry is a selector matched with
 * `element.closest()`, so it covers the element and what it contains: keep every entry as narrow as the thing it names.
 */
export const CHIP_EXEMPT = [
  { selector: 'button, a, input, select, textarea, summary, [role="button"], [role="tab"], [role="menuitem"], [role="option"], [role="switch"], [role="checkbox"], [role="radio"]', why: 'a real control: its box is the affordance' },
  { selector: '.avatar, .disc', why: 'the two-letter monogram of a person (avatar) or of a rule set or process (disc): an identity, not a state, count or tag' },
  { selector: 'kbd', why: 'a keyboard key cap in a shortcut hint is the picture of a key' },
  { selector: 'svg .dg-node, svg .dg-pool, svg .dg-lane, svg .dg-group', why: 'BPMN symbols: a task, pool, lane or group is drawn as a rounded box by the notation' },
  { selector: 'svg .dg__minimap', why: 'the overview map draws each step as a small box by design; it carries no text' },
];

/** Runs in the page: the elements that look like a chip. Self-contained, so Playwright can send it as text. */
export function detectChips(exempt) {
  const controls = exempt.map((entry) => entry.selector).join(', ');
  const visible = (color) => {
    if (color === 'transparent' || color === 'none') return false;
    const match = /rgba?\(([^)]+)\)/.exec(color);
    if (match === null) return color !== '';
    const parts = match[1].split(/[ ,/]+/).filter(Boolean);
    return parts.length < 4 || parseFloat(parts[3]) > 0;
  };
  const found = [];
  const describe = (el) => `${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''}${typeof el.className === 'string' && el.className ? `.${el.className.trim().split(/\s+/).join('.')}` : el.getAttribute('class') ? `.${el.getAttribute('class').trim().split(/\s+/).join('.')}` : ''}`;
  for (const el of document.body.querySelectorAll('*')) {
    if (el.closest(controls) !== null) continue;
    const style = getComputedStyle(el);
    const box = el.getBoundingClientRect();
    if (box.width < 8 || box.height < 8) continue;
    if (el.namespaceURI === 'http://www.w3.org/2000/svg') {
      // A drawn rounded rect with a fill or a stroke, in a group that also holds a short text.
      if (el.tagName.toLowerCase() !== 'rect') continue;
      const radius = Math.max(parseFloat(style.rx) || 0, parseFloat(el.getAttribute('rx') ?? '0') || 0);
      const filled = visible(style.fill) && parseFloat(style.fillOpacity) > 0;
      const stroked = visible(style.stroke) && parseFloat(style.strokeWidth) > 0;
      const text = [...(el.parentElement?.querySelectorAll('text') ?? [])].map((node) => (node.textContent ?? '').trim()).join(' ').trim();
      if (radius >= 4 && (filled || stroked) && text.length > 0 && text.length <= 60 && box.height <= 40) found.push({ element: describe(el), text: text.slice(0, 40), radius });
      continue;
    }
    const radius = Math.max(...['TopLeft', 'TopRight', 'BottomLeft', 'BottomRight'].map((corner) => parseFloat(style[`border${corner}Radius`]) || 0));
    if (radius < 4 || box.height > 40) continue;
    const filled = visible(style.backgroundColor);
    const bordered = ['Top', 'Right', 'Bottom', 'Left'].some((side) => parseFloat(style[`border${side}Width`]) > 0 && style[`border${side}Style`] !== 'none' && visible(style[`border${side}Color`]));
    if (!filled && !bordered) continue;
    const text = [...el.childNodes].filter((node) => node.nodeType === 3).map((node) => node.textContent ?? '').join(' ').trim();
    // A lone glyph (a check, a bullet, a menu mark) is an icon in a round holder, not words or a number.
    if (text.length === 0 || text.length > 60 || !/[\p{L}\p{N}]/u.test(text)) continue;
    found.push({ element: describe(el), text: text.slice(0, 40), radius });
  }
  return found;
}
