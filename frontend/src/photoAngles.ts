/** The standard photo set insurers ask for, so the adjuster sees the whole vehicle (or room), not one close-up. */
export type PhotoAngle = { key: string; label: string; hint: string; required: boolean };

export const AUTO_ANGLES: PhotoAngle[] = [
  { key: 'FRONT', label: 'Front', hint: 'Whole front of the vehicle, straight on', required: true },
  { key: 'REAR', label: 'Rear', hint: 'Whole rear, number plate visible', required: true },
  { key: 'LEFT', label: 'Left side', hint: 'Driver side, front to back', required: true },
  { key: 'RIGHT', label: 'Right side', hint: 'Passenger side, front to back', required: true },
  { key: 'CLOSE_UP', label: 'Damage close-up', hint: 'About 1 m from the damage', required: true },
  { key: 'ODOMETER_VIN', label: 'Odometer / VIN', hint: 'Dashboard or VIN plate (optional)', required: false },
];

export const HOME_ANGLES: PhotoAngle[] = [
  { key: 'OVERVIEW', label: 'Overview', hint: 'The whole affected room, or that side of the house', required: true },
  { key: 'CLOSE_UP', label: 'Damage close-up', hint: 'The damage itself', required: true },
  { key: 'OTHER', label: 'Source of damage', hint: 'e.g. the burst pipe (optional)', required: false },
];

/** Home policies are recognised by their number; everything else uses the vehicle set. */
export function anglesFor(policyNumber: string): PhotoAngle[] {
  return policyNumber.trim().toUpperCase().startsWith('POL-HOME') ? HOME_ANGLES : AUTO_ANGLES;
}

const ORDER = [...AUTO_ANGLES, ...HOME_ANGLES].map((a) => a.key);
const LABELS: Record<string, string> = Object.fromEntries([...AUTO_ANGLES, ...HOME_ANGLES].map((a) => [a.key, a.label]));

export function angleLabel(key?: string | null): string {
  return (key && LABELS[key]) || 'Other';
}

/** Standard order (front, rear, sides, close-up, ...), unknown angles last. */
export function angleRank(key?: string | null): number {
  const i = key ? ORDER.indexOf(key) : -1;
  return i < 0 ? ORDER.length : i;
}
