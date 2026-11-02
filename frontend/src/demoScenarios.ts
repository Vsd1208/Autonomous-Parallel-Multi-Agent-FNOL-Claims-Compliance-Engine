import { FormData } from './types';

/**
 * A prepared claim on a real photo set (sampleSets.ts). The set supplies the photos, and the description and amount
 * unless the scenario overrides them. What the AI concludes comes from the photos, so a scenario only prepares a
 * situation; the scripted outcomes live in demo-scenarios/*.json, which run on mock vision without the workspace.
 */
export type Scenario = {
  name: string;
  photoSet: string;
  data: Omit<FormData, 'incidentDate'>;
  overrides?: Partial<Pick<FormData, 'description' | 'claimedAmount'>>;
};

const insured = {
  claimantDob: '01/02/1980',
  claimantSsn: '123-45-6789',
  claimantPhone: '',
  claimantEmail: '',
  channel: 'WEB' as const,
};

export const demoScenarios: Scenario[] = [
  {
    name: 'Typical Vehicle Claim',
    photoSet: 'fnol-set-camry-frontal',
    data: { ...insured, policyNumber: 'POL-AUTO-112233', state: 'CA', claimantName: 'Avery Johnson',
      description: 'Vehicle involved in a collision. Front end damaged.', claimedAmount: '3000' },
  },
  {
    name: 'Claim Above Estimate',
    photoSet: 'fnol-set-altima-offset-frontal',
    data: { ...insured, policyNumber: 'POL-AUTO-112233', state: 'CA', claimantName: 'Avery Johnson',
      description: 'Vehicle involved in a collision. Front left corner damaged.', claimedAmount: '40000' },
    overrides: { claimedAmount: '40000' },
  },
  {
    name: 'Compliance Block',
    photoSet: 'fnol-set-corolla-side-impact',
    data: { ...insured, policyNumber: 'POL-AUTO-112233', state: 'CA', claimantName: 'Avery Johnson',
      description: 'Vehicle collision. Patient medical record number MRN-123456 is included in the report.',
      claimedAmount: '3500' },
    overrides: { description: 'Vehicle collision. Patient medical record number MRN-123456 is included in the report.' },
  },
  {
    name: 'High Claim Frequency',
    photoSet: 'fnol-set-civic-rear-impact',
    data: { ...insured, policyNumber: 'POL-AUTO-445566', state: 'CA', claimantName: 'Jordan Rivera',
      description: 'Another car hit the rear of my vehicle.', claimedAmount: '2500' },
  },
  {
    name: 'Home Policy',
    photoSet: 'fnol-set-home-burst-pipe',
    data: { ...insured, policyNumber: 'POL-HOME-334455', state: 'TX', claimantName: 'Riley Smith',
      description: 'Kitchen pipe burst and caused water damage to the floor and cabinets.', claimedAmount: '3000' },
  },
];

/** A recent incident date that is unique per load, so re-running a scenario is not a duplicate. */
export function recentDate(): string {
  const daysAgo = 31 + Math.floor(Math.random() * 120);
  return new Date(Date.now() - daysAgo * 86_400_000).toISOString().slice(0, 10);
}
