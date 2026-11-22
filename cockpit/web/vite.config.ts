import react from '@vitejs/plugin-react';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import { SECURITY_HEADERS } from './security-headers.ts';

// Relative base: the same bundle runs at /bpm/cockpit/ inside a host and at any path standalone.
// The dev server alone serves dev-public/ (the mock service worker); the build ships no mock and no registry.
const packageVersion = (JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as { version: string }).version;

export default defineConfig(({ command }) => ({
  base: './',
  // The cockpit's release, for the engine version check (TR-UI-08).
  define: { __COCKPIT_VERSION__: JSON.stringify(packageVersion) },
  plugins: [react()],
  publicDir: command === 'serve' ? 'dev-public' : false,
  build: {
    outDir: 'dist',
    sourcemap: false,
    // No inline <script> or data: module in index.html, so script-src 'self' holds.
    modulePreload: { polyfill: false },
    assetsInlineLimit: 0,
    // The standalone sign-in callback (auth.redirectPath, default auth/callback) is a page of the same application,
    // so a static web server serves it with no rewrite rule and its assets resolve relative to it.
    rolldownOptions: {
      input: {
        main: fileURLToPath(new URL('./index.html', import.meta.url)),
        callback: fileURLToPath(new URL('./auth/callback/index.html', import.meta.url)),
      },
    },
  },
  preview: {
    port: 4173,
    strictPort: true,
    headers: SECURITY_HEADERS,
  },
}));
