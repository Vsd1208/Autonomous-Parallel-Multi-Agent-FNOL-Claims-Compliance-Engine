import {
  AdjusterReviewRequest,
  AdjusterReviewResponse,
  AuditTrail,
  ClaimRecord,
  ClaimResult,
  ExplanationObject,
  QueuePage,
} from '../types';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
// Shared adjuster secret for the protected endpoints (review, audit, audit PDF, explanation, queue).
const ADJUSTER_TOKEN = import.meta.env.VITE_ADJUSTER_TOKEN || '';

/** Error from the API, carrying the REJ-... claim ID when a failed intake was saved for review. */
export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code?: string,
    public readonly claimId?: string
  ) {
    super(message);
  }
}

async function request<T>(path: string, init: RequestInit = {}, adjuster = false): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body) headers.set('Content-Type', 'application/json');
  if (adjuster) headers.set('X-Adjuster-Token', ADJUSTER_TOKEN);

  const response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new ApiError(body.message || `HTTP ${response.status}`, response.status, body.error, body.claimId);
  }
  return response.json() as Promise<T>;
}

export const apiClient = {
  submitFNOL: (payload: {
    policyNumber: string;
    incidentDate: string;
    state: string;
    description: string;
    claimantName: string;
    claimantDob: string;
    claimantSsn: string;
    claimantPhone?: string;
    claimantEmail?: string;
    evidenceUris: string[];
    channel: 'WEB' | 'MOBILE' | 'PHONE' | 'API';
  }) => request<ClaimResult>('/api/v1/fnol/intake', { method: 'POST', body: JSON.stringify(payload) }),

  getClaimStatus: (claimId: string) =>
    request<ClaimRecord>(`/api/v1/fnol/status/${encodeURIComponent(claimId)}`),

  getExplanation: (claimId: string) =>
    request<ExplanationObject>(`/api/v1/fnol/${encodeURIComponent(claimId)}/explanation`, {}, true),

  getAuditTrail: (claimId: string) =>
    request<AuditTrail>(`/api/v1/fnol/${encodeURIComponent(claimId)}/audit`, {}, true),

  getQueue: (statuses: string[], page = 0, size = 20) =>
    request<QueuePage>(
      `/api/v1/fnol/queue?status=${encodeURIComponent(statuses.join(','))}&page=${page}&size=${size}`,
      {},
      true
    ),

  submitAdjusterReview: (claimId: string, payload: AdjusterReviewRequest) =>
    request<AdjusterReviewResponse>(
      `/api/v1/fnol/${encodeURIComponent(claimId)}/review`,
      { method: 'POST', body: JSON.stringify(payload) },
      true
    ),

  /** Downloads the server-generated PDF audit report. */
  downloadAuditPdf: async (claimId: string) => {
    const response = await fetch(`${API_BASE_URL}/api/v1/fnol/${encodeURIComponent(claimId)}/audit/pdf`, {
      headers: { 'X-Adjuster-Token': ADJUSTER_TOKEN },
    });
    if (!response.ok) {
      throw new ApiError(`PDF export failed: HTTP ${response.status}`, response.status);
    }
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement('a');
    link.href = url;
    link.download = `FNOL_Audit_${claimId}.pdf`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  },

  healthCheck: () => request<{ status: string }>('/api/v1/health'),
};
