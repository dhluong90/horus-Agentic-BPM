import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, beforeEach } from 'vitest';
import { resetProcessEngine, resetRuleEngine } from '../mocks/handlers';
import { mockHost, setScenario } from '../mocks/node';

beforeAll(() => {
  mockHost.listen({ onUnhandledFrame: 'error' });
});
// The dev server's engine carries a demo report; a test starts from the plain demo data.
beforeEach(() => {
  resetRuleEngine();
});
afterEach(() => {
  cleanup();
  setScenario('ready');
  mockHost.resetHandlers();
  window.localStorage.clear();
  window.location.hash = '';
  resetRuleEngine();
  resetProcessEngine();
});
afterAll(() => {
  mockHost.close();
});
