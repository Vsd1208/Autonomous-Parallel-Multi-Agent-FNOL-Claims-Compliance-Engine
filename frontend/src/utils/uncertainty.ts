import { Abstention, DamageAssessment } from '../types';

/** Plain words for each reason the AI declined to give an answer worth relying on. None of them counts against the claim. */
export const ABSTENTION_TEXT: Record<string, string> = {
  INSUFFICIENT_QUALITY: 'The photos are too poor to read reliably.',
  MODEL_DISAGREEMENT: 'Two independently trained models disagree about the damage.',
  LOW_CONFIDENCE: 'The AI could not settle on an answer it should be trusted with.',
  UNSUPPORTED_DAMAGE: 'This damage is not something the AI was built to assess, or it saw none.',
  OUT_OF_DISTRIBUTION: 'The photos do not show what this policy insures.',
  INFERENCE_FAILURE: 'The main AI model did not answer, so any reading here is a fallback.',
  MISSING_MODEL_VERSION: 'The answer does not say which model produced it, so it cannot be checked later.',
  MODEL_VERSION_CHANGED: 'A different model answered from the one this system was measured on, so its calibration does not apply.',
};

export function abstentionReasons(abstention?: Abstention | null): string[] {
  return abstention?.abstained ? abstention.reasons.map((r) => ABSTENTION_TEXT[r] ?? r) : [];
}

const NO_ANSWER_REASONS = new Set(['INFERENCE_FAILURE']);

/**
 * What a confidence number may honestly be called. Never a percentage or a probability of being right unless it was calibrated, and a
 * failed call's placeholder is not shown as a number at all.
 */
export function confidenceText(d: DamageAssessment, abstention?: Abstention | null): string {
  if (d.confidence == null) return 'Not reported';
  if (abstention?.reasons?.some((r) => NO_ANSWER_REASONS.has(r))) return 'None: the main AI model did not answer';
  const value = Number(d.confidence).toFixed(2);
  if (d.rawConfidence != null) {
    return `${value} trust score, calibrated on a small labelled sample (the model itself said ${Number(d.rawConfidence).toFixed(2)})`;
  }
  return `${value} as stated by the model: not calibrated, and not a probability of being right`;
}

const LABEL: Record<string, string> = {
  STATUTORY: 'Statute or regulation',
  INSURER_POLICY: "Insurer's own rule",
  INTERNAL_TARGET: 'Internal target',
  EXPERIMENTAL: 'Experimental',
  PRIMARY_TEXT_READ: 'Rule text read by this project',
  SECONDARY_SOURCE: 'Secondary source only',
  UNVERIFIED: 'Not verified',
};

export const ruleLabel = (value?: string | null): string => (value ? LABEL[value] ?? value : 'Not stated');
