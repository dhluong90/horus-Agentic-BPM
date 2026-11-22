import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { withMermaid } from 'vitepress-plugin-mermaid';

const here = dirname(fileURLToPath(import.meta.url));
const sidebar = JSON.parse(readFileSync(join(here, 'sidebar.json'), 'utf8'));

// SITE_BASE is the path the site is served under, for example /1.0/. Default: the root.
export default withMermaid({
  title: 'HORUS Agentic BPM',
  description: 'Documentation of the Rule Engine, Process Engine and cockpit.',
  base: process.env.SITE_BASE || '/',
  cleanUrls: true,
  // Links to repository files and the GitHub wiki are external by design; internal ones were checked by prepare.mjs.
  ignoreDeadLinks: false,
  themeConfig: {
    sidebar,
    // Search runs in the browser on an index built into the site: no request to another origin.
    search: { provider: 'local' },
    outline: [2, 3]
  },
  mermaid: { theme: 'neutral' }
});
