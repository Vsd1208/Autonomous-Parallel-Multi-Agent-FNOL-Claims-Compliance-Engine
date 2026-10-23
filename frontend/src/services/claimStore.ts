import { ClaimResult } from '../types';

// Full intake responses are only available right after submission, so the console keeps the
// most recent ones in localStorage. Anything else is loaded from the API.
const KEY = 'fnol:claims';
const LAST_KEY = 'fnol:lastClaimId';
const MAX_CLAIMS = 20;

function readAll(): Record<string, ClaimResult> {
  try {
    return JSON.parse(window.localStorage.getItem(KEY) || '{}');
  } catch {
    return {};
  }
}

export const claimStore = {
  save(claim: ClaimResult) {
    try {
      const all = readAll();
      all[claim.claimId] = claim;
      const ids = Object.keys(all);
      for (const id of ids.slice(0, Math.max(0, ids.length - MAX_CLAIMS))) delete all[id];
      window.localStorage.setItem(KEY, JSON.stringify(all));
      window.localStorage.setItem(LAST_KEY, claim.claimId);
    } catch {
      // Storage unavailable (private mode, quota): the dashboard falls back to the API.
    }
  },

  get(claimId: string): ClaimResult | undefined {
    return readAll()[claimId];
  },

  updateStatus(claimId: string, status: string) {
    const claim = this.get(claimId);
    if (claim) this.save({ ...claim, status });
  },

  lastClaimId(): string | null {
    try {
      return window.localStorage.getItem(LAST_KEY);
    } catch {
      return null;
    }
  },
};
