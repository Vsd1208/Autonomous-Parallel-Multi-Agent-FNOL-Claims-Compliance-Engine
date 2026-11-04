import {
  AdjusterReviewRequest,
  AdjusterReviewResponse,
  AiMetrics,
  AuditTrail,
  CcClaim,
  ClaimDocument,
  ClaimRecord,
  Inspection,
  Inspector,
  UploadRequest,
  ClaimResult,
  ExplanationObject,
  FormData,
  QueuePage,
  ReserveApprovalView,
  ClaimDraft,
} from '../types';

import { Session, session } from './session';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

/** The signed-in person's credentials for the protected endpoints. A session that has ended sends them to sign in again. */
function authHeaders(): Record<string, string> {
  const token = session.get()?.token;
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function signOutIfExpired(status: number) {
  if (status === 401 && session.get()) {
    session.clear();
    window.location.reload();
  }
}

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
  if (adjuster) Object.entries(authHeaders()).forEach(([k, v]) => headers.set(k, v));

  const response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers });
  if (!response.ok) {
    if (adjuster) signOutIfExpired(response.status);
    const body = await response.json().catch(() => ({}));
    throw new ApiError(body.message || `HTTP ${response.status}`, response.status, body.error, body.claimId);
  }
  return response.json() as Promise<T>;
}

export const apiClient = {
  login: (username: string, password: string) =>
    request<Session>('/api/v1/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),

  getReserveApprovals: (claimId: string) =>
    request<ReserveApprovalView[]>(`/api/v1/fnol/${encodeURIComponent(claimId)}/reserve-approvals`, {}, true),

  decideReserveApproval: (claimId: string, approvalId: string, approve: boolean, note: string) =>
    request<{ status: string }>(`/api/v1/fnol/${encodeURIComponent(claimId)}/reserve-approvals/${encodeURIComponent(approvalId)}/decide`,
      { method: 'POST', body: JSON.stringify({ approve, note }) }, true),

  listDrafts: (claimId: string) =>
    request<ClaimDraft[]>(`/api/v1/fnol/${encodeURIComponent(claimId)}/drafts`, {}, true),
  createDraft: (claimId: string, kind: 'ADVISORY_NOTE' | 'ACKNOWLEDGEMENT_LETTER') =>
    request<ClaimDraft>(`/api/v1/fnol/${encodeURIComponent(claimId)}/drafts`, { method: 'POST', body: JSON.stringify({ kind }) }, true),
  acceptDraft: (claimId: string, draftId: string, text: string) =>
    request<ClaimDraft>(`/api/v1/fnol/${encodeURIComponent(claimId)}/drafts/${encodeURIComponent(draftId)}/accept`,
      { method: 'POST', body: JSON.stringify({ text }) }, true),
  discardDraft: (claimId: string, draftId: string) =>
    request<ClaimDraft>(`/api/v1/fnol/${encodeURIComponent(claimId)}/drafts/${encodeURIComponent(draftId)}/discard`, { method: 'POST', body: '{}' }, true),
  logout: () => request<{ status: string }>('/api/v1/auth/logout', { method: 'POST' }, true),

  /**
   * Reports a loss. With claims-system events on, it is filed in the claims system (status SUBMITTED) and the AI
   * analysis follows in the background; otherwise the direct intake answers with the analysed claim's status.
   */
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
  }) => request<{ claimId: string; status: string }>('/api/v1/fnol/report',
    { method: 'POST', body: JSON.stringify(payload) }, true),

  getClaimStatus: (claimId: string) =>
    request<ClaimRecord>(`/api/v1/fnol/status/${encodeURIComponent(claimId)}`, {}, true),

  getExplanation: (claimId: string) =>
    request<ExplanationObject>(`/api/v1/fnol/${encodeURIComponent(claimId)}/explanation`, {}, true),

  getAuditTrail: (claimId: string) =>
    request<AuditTrail>(`/api/v1/fnol/${encodeURIComponent(claimId)}/audit`, {}, true),

  /** The intake's full AI analysis as saved on the server (404 for claims filed before it was saved). */
  getAnalysis: (claimId: string) =>
    request<ClaimResult>(`/api/v1/fnol/${encodeURIComponent(claimId)}/analysis`, {}, true),

  /** AI governance: routing, flags, leakage, adjuster agreement, privacy and drift. */
  getAiMetrics: () => request<AiMetrics>('/api/v1/ai/metrics', {}, true),

  /** The claim as ClaimCenter holds it: exposures with reserves, activities, notes, documents. */
  getClaimCenterView: (claimId: string) =>
    request<CcClaim>(`/api/v1/fnol/${encodeURIComponent(claimId)}/claimcenter`, {}, true),

  getDocuments: (claimId: string) =>
    request<ClaimDocument[]>(`/api/v1/fnol/${encodeURIComponent(claimId)}/documents`, {}, true),

  /**
   * Fetches a stored evidence image with the adjuster token and returns an object URL for <img>.
   * The caller must URL.revokeObjectURL it when done.
   */
  getDocumentImageUrl: async (claimId: string, documentId: string) => {
    const response = await fetch(
      `${API_BASE_URL}/api/v1/fnol/${encodeURIComponent(claimId)}/documents/${encodeURIComponent(documentId)}/content`,
      { headers: authHeaders() }
    );
    if (!response.ok) {
      throw new ApiError(`Image unavailable: HTTP ${response.status}`, response.status);
    }
    return URL.createObjectURL(await response.blob());
  },

  getQueue: (statuses: string[], page = 0, size = 20, segment = '') =>
    request<QueuePage>(
      `/api/v1/fnol/queue?status=${encodeURIComponent(statuses.join(','))}&page=${page}&size=${size}${segment ? `&segment=${encodeURIComponent(segment)}` : ''}`,
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

  /** Asks the claimant for more photos; returns the one-time link (shown only now). */
  createUploadRequest: (claimId: string, body: { requestedBy: string; angles: string[]; message?: string }) =>
    request<{ requestId: string; uploadUrl: string; expiresAt: string; angles: string[] }>(
      `/api/v1/fnol/${encodeURIComponent(claimId)}/upload-requests`, { method: 'POST', body: JSON.stringify(body) }, true),

  getUploadRequests: (claimId: string) =>
    request<UploadRequest[]>(`/api/v1/fnol/${encodeURIComponent(claimId)}/upload-requests`, {}, true),

  cancelUploadRequest: (claimId: string, requestId: string, adjusterId: string) =>
    request<UploadRequest>(
      `/api/v1/fnol/${encodeURIComponent(claimId)}/upload-requests/${encodeURIComponent(requestId)}/cancel`,
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
      headers: authHeaders(),
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
