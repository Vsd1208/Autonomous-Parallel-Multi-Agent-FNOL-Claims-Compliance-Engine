import {
  AdjusterReviewRequest,
  AdjusterReviewResponse,
  AuditTrail,
  ClaimDocument,
  ClaimRecord,
  Inspection,
  Inspector,
  ClaimResult,
  ExplanationObject,
  FormData,
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
    channel: FormData['channel'];
    claimedAmount?: number;
    evidenceAngles?: string[];
  }) => request<ClaimResult>('/api/v1/fnol/intake', { method: 'POST', body: JSON.stringify(payload) }),

  getClaimStatus: (claimId: string) =>
    request<ClaimRecord>(`/api/v1/fnol/status/${encodeURIComponent(claimId)}`),

  getExplanation: (claimId: string) =>
    request<ExplanationObject>(`/api/v1/fnol/${encodeURIComponent(claimId)}/explanation`, {}, true),

  getAuditTrail: (claimId: string) =>
    request<AuditTrail>(`/api/v1/fnol/${encodeURIComponent(claimId)}/audit`, {}, true),

  getDocuments: (claimId: string) =>
    request<ClaimDocument[]>(`/api/v1/fnol/${encodeURIComponent(claimId)}/documents`, {}, true),

  /**
   * Fetches a stored evidence image with the adjuster token and returns an object URL for <img>.
   * The caller must URL.revokeObjectURL it when done.
   */
  getDocumentImageUrl: async (claimId: string, documentId: string) => {
    const response = await fetch(
      `${API_BASE_URL}/api/v1/fnol/${encodeURIComponent(claimId)}/documents/${encodeURIComponent(documentId)}/content`,
      { headers: { 'X-Adjuster-Token': ADJUSTER_TOKEN } }
    );
    if (!response.ok) {
      throw new ApiError(`Image unavailable: HTTP ${response.status}`, response.status);
    }
    return URL.createObjectURL(await response.blob());
  },

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

  getInspectors: () => request<Inspector[]>('/api/v1/inspectors', {}, true),

  getInspections: (claimId: string) =>
    request<Inspection[]>(`/api/v1/fnol/${encodeURIComponent(claimId)}/inspections`, {}, true),

  assignInspection: (claimId: string, body: { inspectorId: string; inspectionType: string; reason: string; assignedBy: string }) =>
    request<Inspection>(`/api/v1/fnol/${encodeURIComponent(claimId)}/inspections`,
      { method: 'POST', body: JSON.stringify(body) }, true),

  reportInspection: (claimId: string, inspectionId: string, body: {
    inspectorId: string; accidentRelatedAmount: number; priorDamageFound: boolean; findings: string; recommendation: string;
  }) =>
    request<Inspection>(
      `/api/v1/fnol/${encodeURIComponent(claimId)}/inspections/${encodeURIComponent(inspectionId)}/report`,
      { method: 'POST', body: JSON.stringify(body) }, true),

  cancelInspection: (claimId: string, inspectionId: string, adjusterId: string) =>
    request<Inspection>(
      `/api/v1/fnol/${encodeURIComponent(claimId)}/inspections/${encodeURIComponent(inspectionId)}/cancel`,
      { method: 'POST', body: JSON.stringify({ adjusterId }) }, true),

  /** Pushes the recorded decision to ClaimCenter again after a failed sync. */
  retryClaimCenterSync: (claimId: string) =>
    request<{ claimId: string; ccSyncStatus: string }>(
      `/api/v1/fnol/${encodeURIComponent(claimId)}/claimcenter-sync`,
      { method: 'POST' },
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
