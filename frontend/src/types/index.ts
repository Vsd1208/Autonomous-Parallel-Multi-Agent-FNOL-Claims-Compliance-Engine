export interface DamageAssessment {
  damageType: string;
  severity: string;
  estimatedDamage: number;
  confidence: number;
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
  factors: {
    thirdPartyMention: number;
    policeReportSignal: number;
    damageSeverity: number;
  };
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

export interface ClaimResult {
  claimId: string;
  status: string;
  branchA: BranchAResult;
  branchB: BranchBResult;
  explanation: string[];
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