import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { withMermaid } from 'vitepress-plugin-mermaid';

const here = dirname(fileURLToPath(import.meta.url));
const sidebar = JSON.parse(readFileSync(join(here, 'sidebar.json'), 'utf8'));

const repo = 'https://github.com/dhluong90/horus-Agentic-BPM';
// SITE_BASE is the path the site is served under. Default: the project page of the GitHub Pages site.
const base = process.env.SITE_BASE || '/horus-Agentic-BPM/';
export default withMermaid({
  title: 'HORUS Agentic BPM',
  description: 'Embeddable BPMN, DMN and rules engine for Java, with a cockpit.',
  base,
  head: [['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}logo.svg` }]],
  cleanUrls: true,
  // Links to repository files and the GitHub wiki are external by design; internal ones were checked by prepare.mjs.
  ignoreDeadLinks: false,
  markdown: { theme: { light: 'github-light-high-contrast', dark: 'github-dark-high-contrast' } },
  themeConfig: {
    sidebar,
    // Search runs in the browser on an index built into the site: no request to another origin.
    search: { provider: 'local' },
    logo: { src: '/logo.svg', alt: '' },
    nav: [
      { text: 'Docs', link: '/docs/' },
      { text: 'Compare', link: '/Compare-and-Choose' },
      { text: 'Cockpit', link: '/Cockpit' },
      { text: 'Release notes', link: '/Release-Notes' }
    ],
    socialLinks: [{ icon: 'github', link: repo, ariaLabel: 'GitHub' }],
    // The pages are generated from wiki/, so the edit link points at the wiki source in the repository.
    editLink: { pattern: `${repo}/edit/main/wiki/:path`, text: 'Edit this page on GitHub' },
    docFooter: { prev: 'Previous page', next: 'Next page' },
    outline: { level: [2, 3], label: 'On this page' }
  },
  mermaid: { theme: 'neutral' }
});
