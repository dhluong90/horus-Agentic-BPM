import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { mockHost, setScenario } from '../mocks/node';

beforeAll(() => {
  mockHost.listen({ onUnhandledFrame: 'error' });
});
afterEach(() => {
  cleanup();
  setScenario('ready');
  mockHost.resetHandlers();
  window.localStorage.clear();
});
afterAll(() => {
  mockHost.close();
});
