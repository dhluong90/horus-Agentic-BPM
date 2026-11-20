import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { applyTheme, initialTheme } from './preferences/theme';
import './components/tokens.css';
import './components/base.css';
import './components/shell.css';
import './components/rules.css';
import './components/processes.css';

applyTheme(initialTheme());

async function enableDevMocks(): Promise<void> {
  // Development only: the mock host answers /api/bpm/meta and /me until a real host is running.
  // import.meta.env.DEV is false in the production build, so the mock code is not shipped.
  if (!import.meta.env.DEV || import.meta.env['VITE_MOCKS'] === 'off') return;
  const { startDevMocks } = await import('./mocks/browser');
  await startDevMocks();
}

const root = document.getElementById('root');
if (root) {
  void enableDevMocks().then(() => {
    createRoot(root).render(
      <StrictMode>
        <App />
      </StrictMode>,
    );
  });
}
