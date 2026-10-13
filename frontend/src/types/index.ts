export interface DamageAssessment {
  damageType: string;
  severity: string;
  estimatedDamage: number;
  confidence: number;
  photoQuality?: string;
  damageTypeProbabilities?: Record<string, number>;
}

export interface Coverage {
  covered: boolean;
  coverageType: string;
  coverageLimit: number;
  deductible: number;
}

export interface Reserve {
  estimatedDamage: number;
  factor: number;
  recommendedReserve: number;
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
}

export interface Subrogation {
  score: number;
  recommendedAction: string;
  factors: Record<string, number>;
}

export interface AuditEvent {
  agentName: string;
  status: string;
  timestamp: string;
  inputHash?: string;
  outputHash?: string;
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

export interface ClaimResult {
  claimId: string;
  status: string;
  branchA: BranchAResult;
  branchB: BranchBResult;
  policyContext?: PolicyContext;
  explanation: ExplanationObject;
  severityGate?: string;
  eligibleForAdvisoryPrefill?: boolean;
  notes: string[];
}

export interface AdjusterReviewRequest {
  decision: 'APPROVED_BY_ADJUSTER' | 'REJECTED_BY_ADJUSTER' | 'NEEDS_MORE_INFO' | 'CLOSED';
  adjusterId: string;
  notes: string;
}

export interface AdjusterReviewResponse {
  claimId: string;
  status: string;
  adjusterDecision: string;
  adjusterId: string;
  adjusterNotes: string;
  reviewedAt: string;
}

export interface FormData {
  policyNumber: string;
  incidentDate: string;
  state: string;
  description: string;
  claimantName: string;
  claimantDob: string;
  claimantSsn: string;
}
