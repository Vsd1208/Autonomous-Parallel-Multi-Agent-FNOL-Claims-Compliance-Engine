export interface DamageAssessment {
  damageType: string;
  severity: string;
  estimatedDamage: number;
  confidence: number;
  photoQuality?: string;
  damageTypeProbabilities?: Record<string, number>;
  rawConfidence?: number | null;
}

export interface Coverage {
  policyNumber?: string;
  covered: boolean;
  coverageType: string | null;
  coverageLimit: number;
  deductible: number;
  reasoning?: string[];
}

export interface Reserve {
  estimatedDamage: number;
  factor: number;
  recommendedReserve: number;
  confidence?: number | null;
  confidenceBasis?: string | null;
  /** What the claimant asked for. */
  claimedAmount?: number | null;
  /** min(claimed, AI estimate): the most the AI will reserve before the deductible. */
  settlementBasis?: number | null;
  deductible?: number | null;
  coverageLimit?: number | null;
}

export interface BranchAResult {
  damageAssessment: DamageAssessment;
  coverage: Coverage;
  reserve: Reserve;
}

export interface PiiInfo {
  piiDetected: boolean;
  redactedDescription: string;
  detectedTypes: string[];
  redactionConfidence?: number;
}

export interface Deadline {
  state: string;
  deadline: string;
  ruleSource: string;
  disclaimer: string;
  acknowledgeBy?: string;
  decisionBy?: string;
  urgencyFlag?: 'OK' | 'WARNING' | 'CRITICAL' | string;
}

export interface Subrogation {
  score: number;
  recommendedAction: string;
  factors: Record<string, number>;
  complianceNotes?: string[];
}

export interface AuditEvent {
  eventId?: string;
  agentName: string;
  status: string;
  timestamp: string;
  inputHash?: string;
  outputHash?: string;
  chainHash?: string | null;
  seq?: number | null;
  actor?: string | null;
}

export interface AuditTrail {
  claimId: string;
  chainValid: boolean;
  records: AuditEvent[];
}

export interface BranchBResult {
  pii: PiiInfo;
  deadline: Deadline;
  subrogation: Subrogation;
  audit: AuditEvent[];
}

export interface PolicyContext {
  totalPriorClaims: number;
  claimsLast6Months: number;
  claimsLast12Months: number;
  lastClaimDate: string | null;
  lastClaimType: string | null;
  lastClaimAmount: number | null;
  openClaimsCount: number;
  claimFrequencyRisk: string;
}

export interface ExplanationFactor {
  factor: string;
  value: string;
  status: 'PASS' | 'FAIL' | string;
  detail: string;
}

export interface ExplanationObject {
  decision: string;
  summary: string;
  factors: ExplanationFactor[];
  recommendedAction: string;
  severityGate: string;
}

/** Full intake response (POST /fnol/intake). */
export interface ClaimResult {
  claimId: string;
  status: string;
  branchA: BranchAResult;
  branchB: BranchBResult;
  policyContext?: PolicyContext;
  explanation: ExplanationObject;
  severityGate?: string;
  eligibleForAdvisoryPrefill?: boolean;
  requiresHumanValidation?: boolean;
  notes: string[];
  inputFlags?: string[];
  ccSyncStatus?: string;
  gateFlags?: string[];
}

/** Saved claim row (GET /fnol/status/{claimId}). */
export interface ClaimRecord {
  claimId: string;
  policyNumber: string;
  status: string;
  incidentDate: string;
  state: string;
  estimatedDamage: number;
  reserve: number;
  claimedAmount: number | null;
  deductible: number | null;
  adjusterDecision: string | null;
  adjusterId: string | null;
  adjusterNotes: string | null;
  reviewedAt: string | null;
  createdAt: string;
  channel: string | null;
  failureReason: string | null;
  severityGate: string | null;
  inputFlags: string | null;
  ccSyncStatus: string | null;
  gateFlags: string | null;
  adjusterReserve: number | null;
  overrideReason: string | null;
}

/**
 * What the dashboard shows. A claim opened straight from intake has the branch details;
 * a claim loaded from the server (queue, deep link) has the saved row and WHY panel only.
 */
export interface DashboardClaim {
  claimId: string;
  status: string;
  explanation?: ExplanationObject | null;
  branchA?: BranchAResult;
  branchB?: BranchBResult;
  notes?: string[];
  inputFlags?: string[];
  gateFlags?: string[];
  failureReason?: string | null;
  ccSyncStatus?: string | null;
  record?: ClaimRecord;
}

/** GET /inspectors */
export interface Inspector {
  inspectorId: string;
  name: string;
  specialty: string;
  region: string;
}

/** A damage inspection (GET /fnol/{id}/inspections). */
export interface Inspection {
  inspectionId: string;
  claimId: string;
  inspectorId: string;
  inspectorName: string;
  inspectionType: 'FIELD' | 'DESK' | 'SHOP';
  reason: string;
  status: 'ASSIGNED' | 'COMPLETED' | 'CANCELLED';
  assignedBy: string;
  assignedAt: string;
  dueBy: string;
  completedAt: string | null;
  accidentRelatedAmount: number | null;
  priorDamageFound: boolean | null;
  findings: string | null;
  recommendation: string | null;
  recommendedPayment: number | null;
}

/** Evidence on a claim (GET /fnol/{id}/documents). Only viewable documents have stored image bytes. */
export interface ClaimDocument {
  documentId: string;
  /** FRONT, REAR, LEFT, RIGHT, CLOSE_UP, ODOMETER_VIN, OVERVIEW or OTHER */
  angle?: string | null;
  claimId: string;
  name: string;
  contentType: string | null;
  sizeBytes: number | null;
  sha256: string | null;
  source: 'UPLOAD' | 'URL' | 'MOCK' | string;
  sourceUri: string | null;
  uploadedAt: string;
  viewable: boolean;
}

export interface QueueItem {
  claimId: string;
  status: string;
  policyNumber: string;
  reserve: number;
  severityGate: string | null;
  inputFlags: string | null;
  gateFlags: string | null;
  failureReason: string | null;
  createdAt: string;
}

export interface QueuePage {
  items: QueueItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type AdjusterDecision =
  | 'APPROVED_BY_ADJUSTER'
  | 'REJECTED_BY_ADJUSTER'
  | 'NEEDS_MORE_INFO'
  | 'INVESTIGATION_REQUIRED'
  | 'CLOSED';

export interface AdjusterReviewRequest {
  decision: AdjusterDecision;
  adjusterId: string;
  notes: string;
  adjustedReserve?: number;
  overrideReason?: string;
}

export interface AdjusterReviewResponse {
  claimId: string;
  status: string;
  adjusterDecision: string;
  adjusterId: string;
  adjusterNotes: string;
  reviewedAt: string;
  adjustedReserve: number | null;
  overrideReason: string | null;
}

export interface FormData {
  policyNumber: string;
  incidentDate: string;
  state: string;
  description: string;
  claimantName: string;
  claimantDob: string;
  claimantSsn: string;
  claimantPhone: string;
  claimantEmail: string;
  channel: 'WEB' | 'MOBILE' | 'PHONE' | 'API';
  /** Amount the claimant asks for, as typed (whole dollars). */
  claimedAmount: string;
}
