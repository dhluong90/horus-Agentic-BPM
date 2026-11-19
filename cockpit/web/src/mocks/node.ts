import { setupServer } from 'msw/node';
import type { Scenario } from './fixtures';
import { handlers } from './handlers';

let scenario: Scenario = 'ready';

export const mockHost = setupServer(...handlers(() => scenario));

export function setScenario(next: Scenario): void {
  scenario = next;
}
