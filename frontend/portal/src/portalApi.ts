/**
 * The policyholder portal's API client. It reaches only the portal endpoints (/api/v1/portal) and the one-time
 * upload links (/api/v1/public/upload). It never imports the adjuster workspace's client, so the adjuster token is
 * not in this bundle.
 */
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

export class PortalError extends Error {
  constructor(message: string, public readonly status: number, public readonly code?: string,
              public readonly claimId?: string) {
    super(message);
  }
}

/** What the policyholder sees about a claim (PortalController.PortalClaim). */
export interface PortalClaim {
  claimId: string;
  lineOfBusiness: 'AUTO' | 'HOME';
  incidentDate: string;
  reportedOn: string | null;
  stage: 'IN_REVIEW' | 'ACTION_NEEDED' | 'ACCEPTED' | 'DECIDED' | 'CLOSED' | 'SPECIALIST';
  stageTitle: string;
  stageText: string;
  acknowledgedOn: string | null;
  acknowledgeBy: string | null;
  decisionBy: string | null;
  photosRequested: string[];
  letter: string | null;
}

export interface ClaimReport {
  policyNumber: string;
  incidentDate: string;
  state: string;
  description: string;
  claimantName: string;
  claimantPhone?: string;
  claimantEmail?: string;
  claimedAmount?: number;
  evidenceUris: string[];
  evidenceAngles: string[];
}

export interface PublicUpload {
  claimId: string;
  angles: string[];
  message: string | null;
  expiresAt: string;
  status: 'OPEN' | 'USED' | 'CANCELLED' | 'EXPIRED';
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new PortalError(body.message || `HTTP ${response.status}`, response.status, body.error, body.claimId);
  }
  return response.json() as Promise<T>;
}

export const portalApi = {
  report: (claim: ClaimReport) =>
    request<PortalClaim>('/api/v1/portal/claims', { method: 'POST', body: JSON.stringify({ ...claim, channel: 'WEB' }) }),

  track: (claimId: string, policyNumber: string) =>
    request<PortalClaim>(`/api/v1/portal/claims/${encodeURIComponent(claimId.trim())}`
      + `?policyNumber=${encodeURIComponent(policyNumber.trim())}`),

  /** A used or expired link answers 410 with the same body. */
  getUpload: async (token: string): Promise<PublicUpload> => {
    const response = await fetch(`${API_BASE_URL}/api/v1/public/upload/${encodeURIComponent(token)}`);
    if (response.ok || response.status === 410) return response.json();
    throw new PortalError(response.status === 404 ? 'This upload link is not valid.' : `HTTP ${response.status}`,
      response.status);
  },

  sendUpload: (token: string, body: { evidenceUris: string[]; evidenceAngles: string[] }) =>
    request<{ claimId: string; photosReceived: number }>(`/api/v1/public/upload/${encodeURIComponent(token)}`,
      { method: 'POST', body: JSON.stringify(body) }),
};
