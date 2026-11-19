import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import { SECURITY_HEADERS } from './security-headers.ts';

// Relative base: the same bundle runs at /bpm/cockpit/ inside a host and at any path standalone.
// The dev server alone serves dev-public/ (the mock service worker); the build ships no mock and no registry.
export default defineConfig(({ command }) => ({
  base: './',
  plugins: [react()],
  publicDir: command === 'serve' ? 'dev-public' : false,
  build: {
    outDir: 'dist',
    sourcemap: false,
    // No inline <script> or data: module in index.html, so script-src 'self' holds.
    modulePreload: { polyfill: false },
    assetsInlineLimit: 0,
  },
  preview: {
    port: 4173,
    strictPort: true,
    headers: SECURITY_HEADERS,
  },
}));
