export interface DamageAssessment {
  damageType: string;
  severity: string;
  estimatedDamage: number;
  confidence: number;
  photoQuality?: string;
  damageTypeProbabilities?: Record<string, number>;
  rawConfidence?: number | null;
  /** What the photos show, as the AI saw it: VEHICLE, BUILDING, OTHER_PROPERTY or NONE. */
  photoSubject?: string | null;
  /** What left the system for the external model. */
  privacy?: { photosSent: number; photosWithheld: number; facesBlurred: number; platesBlurred: number; redacted: boolean } | null;
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
  /** Independently trained vision model's view of the same photos; null when it was not available. */
  secondOpinion?: SecondOpinion | null;
}

export interface SecondOpinion {
  model: string;
  damageProbabilities: Record<string, number>;
  subjectProbabilities: Record<string, number>;
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
  acknowledgeCitation?: string | null;
  decisionCitation?: string | null;
  /** How the decision deadline was set, or why there is none (clock not started, no fixed period). */
  decisionNote?: string | null;
  rulesVersion?: string | null;
  /** Last day to start a recovery action against an at-fault party. */
  recoverBy?: string | null;
  recoveryCitation?: string | null;
}

export interface Subrogation {
  score: number;
  recommendedAction: string;
  factors: Record<string, number>;
  complianceNotes?: string[];
  /** The other party's details from the description: otherVehiclePlate, otherInsurer, policeReportNumber. */
  thirdParty?: Record<string, string>;
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
  fraudAnomaly?: FraudAnomaly | null;
}

/** Advisory fraud anomaly score: routes to an adjuster and suggests an SIU referral; never denies. */
export interface FraudAnomaly {
  score: number;
  threshold: number;
  anomalous: boolean;
  topContributors: { feature: string; label: string; value: string; points: number }[];
  basis: string | null;
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
  fraudAnomaly?: FraudAnomaly | null;
}

/** A one-time photo upload link sent to the claimant (GET /fnol/{id}/upload-requests). */
export interface UploadRequest {
  requestId: string;
  claimId: string;
  requestedAngles: string;
  message: string | null;
  requestedBy: string;
  status: 'OPEN' | 'USED' | 'CANCELLED';
  effectiveStatus: 'OPEN' | 'USED' | 'CANCELLED' | 'EXPIRED';
  createdAt: string;
  expiresAt: string;
  usedAt: string | null;
  photosReceived: number | null;
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
  /** The damaged area / cause of loss the adjuster confirmed (feeds the AI feedback loop). */
  confirmedDamageType?: string;
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

/** GET /fnol/{id}/claimcenter: the claim as ClaimCenter holds it. */
export interface CcExposure {
  exposureId: string;
  exposureType: string;
  coverageType: string;
  reserve: number | null;
  costType: string;
  status: string;
}

export interface CcActivity {
  activityId: string;
  subject: string;
  description: string | null;
  priority: string;
  dueDate: string | null;
  assignedTo: string | null;
  status: string;
  completedBy: string | null;
}

export interface CcNote {
  noteId: string;
  topic: string;
  subject: string;
  body: string;
  createdAt: string;
}

export interface CcClaim {
  claimId: string;
  status: string;
  policyNumber: string;
  reserve: number | null;
  attachments: { name: string; contentType: string; body?: unknown }[];
  reviewActions: { at: string; action: string | null; status: string; adjusterId: string | null }[];
  exposures: CcExposure[];
  activities: CcActivity[];
  claimNotes: CcNote[];
}

/** GET /ai/metrics: AI governance figures. */
export interface AiMetrics {
  claims: { filed: number; withAiAnalysis: number; straightThroughEligible: number; sentToAdjuster: number };
  flags: Record<string, number>;
  leakage: { claimsAskingAboveEstimate: number; amountNotReserved: number };
  agreement: {
    decisions: number; damageTypeConfirmed: number; damageTypeAgreed: number;
    straightThroughEligibleDecided: number; straightThroughEligibleApproved: number;
    reserveChanged: number; meanReserveChange: number; byDecision: Record<string, number>;
  };
  privacy: { photosSent: number; photosWithheld: number; facesBlurred: number; platesBlurred: number; claimsRedacted: number };
  drift: { week: string; claims: number; meanMargin: number | null; meanConfidence: number | null;
    hedgedShare: number | null; secondOpinionLowShare: number | null }[];
  deadlines: { claimsWithRule: number; acknowledgedOnTime: number; acknowledgedLate: number; notAcknowledged: number;
    claimsWithoutRule: number; openDecisionsOverdue: number; openDecisionsDueWithin3Days: number;
    openWithoutDecisionClock: number };
  fairness: Record<'state' | 'channel' | 'lineOfBusiness', FairnessRow[]> & { method: string };
}

export interface FairnessRow {
  group: string; claims: number; straightThroughRate: number | null; decisions: number; approvalRate: number | null;
  reserveChangedRate: number | null; reserveToClaimed: number | null;
  straightThroughDisparity: number | null; straightThroughStatus: 'OK' | 'REVIEW' | 'TOO_FEW';
  approvalDisparity: number | null; approvalStatus: 'OK' | 'REVIEW' | 'TOO_FEW';
}
