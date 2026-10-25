import { FormData } from './types';

export type Scenario = { name: string; evidenceUri: string; data: Omit<FormData, 'incidentDate'> };

const insured = {
  claimantDob: '01/02/1980',
  claimantSsn: '123-45-6789',
  claimantPhone: '',
  claimantEmail: '',
  channel: 'WEB' as const,
};

/**
 * Mirrors demo-scenarios/*.json. The mock AI estimate is $4,200 for vehicle photos, so "Clean STP" claims
 * less than that (reserved as claimed, less the deductible) and "Claim Above Estimate" claims more (adjuster decides). The mock:// photo keywords drive MockVisionProvider
 * (GEMINI_VISION_ENABLED=false); with Gemini enabled, upload a real photo instead.
 */
export const demoScenarios: Scenario[] = [
  {
    name: 'Clean STP',
    evidenceUri: 'mock://photo/normal-front-bumper.jpg',
    data: { ...insured, policyNumber: 'POL-AUTO-112233', state: 'CA', claimantName: 'Avery Johnson',
      description: 'Vehicle involved in a minor collision. Front bumper damaged.', claimedAmount: '3000' },
  },
  {
    name: 'Claim Above Estimate',
    evidenceUri: 'mock://photo/normal-front-bumper.jpg',
    data: { ...insured, policyNumber: 'POL-AUTO-112233', state: 'CA', claimantName: 'Avery Johnson',
      description: 'Vehicle involved in a minor collision. Front bumper damaged.', claimedAmount: '9000' },
  },
  {
    name: 'Low Confidence',
    evidenceUri: 'mock://photo/blurry-damage.jpg',
    data: { ...insured, policyNumber: 'POL-AUTO-112233', state: 'CA', claimantName: 'Avery Johnson',
      description: 'Vehicle involved in a collision. Front bumper damaged.', claimedAmount: '3500' },
  },
  {
    name: 'Compliance Block',
    evidenceUri: 'mock://photo/normal-damage.jpg',
    data: { ...insured, policyNumber: 'POL-AUTO-112233', state: 'CA', claimantName: 'Avery Johnson',
      description: 'Vehicle collision. Patient medical record number MRN-123456 is included in the report.',
      claimedAmount: '3500' },
  },
  {
    name: 'Timeout',
    evidenceUri: 'mock://photo/slow-damage.jpg',
    data: { ...insured, policyNumber: 'POL-AUTO-112233', state: 'CA', claimantName: 'Avery Johnson',
      description: 'Vehicle involved in a collision. Front bumper damaged.', claimedAmount: '3500' },
  },
  {
    name: 'High Claim Frequency',
    evidenceUri: 'mock://photo/normal-rear-bumper.jpg',
    data: { ...insured, policyNumber: 'POL-AUTO-445566', state: 'CA', claimantName: 'Jordan Rivera',
      description: 'Scraped a pole while parking. Rear bumper dented.', claimedAmount: '2500' },
  },
  {
    name: 'Home Policy',
    evidenceUri: 'mock://photo/water-damage.jpg',
    data: { ...insured, policyNumber: 'POL-HOME-334455', state: 'TX', claimantName: 'Riley Smith',
      description: 'Kitchen pipe burst and caused water damage to the floor and cabinets.', claimedAmount: '3000' },
  },
];

/** A recent incident date that is unique per load, so re-running a scenario is not a duplicate. */
export function recentDate(): string {
  const daysAgo = 31 + Math.floor(Math.random() * 120);
  return new Date(Date.now() - daysAgo * 86_400_000).toISOString().slice(0, 10);
}
