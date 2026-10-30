import React, { useState } from 'react';
import { angleLabel } from '../../src/photoAngles';
import { PortalClaim } from './portalApi';
import { Alert, Card, longDate } from './ui';

const DECIDED = ['ACCEPTED', 'DECIDED', 'CLOSED'];

/** Progress, dates, what is needed and the acknowledgement letter, in the policyholder's terms. */
export default function ClaimStatus({ claim }: { claim: PortalClaim }) {
  const [showLetter, setShowLetter] = useState(false);
  const decided = DECIDED.includes(claim.stage);
  const steps: { label: string; detail: string; state: 'done' | 'current' | 'todo' }[] = [
    { label: 'Reported', detail: longDate(claim.reportedOn), state: 'done' },
    {
      label: 'Acknowledged',
      detail: claim.acknowledgedOn ? longDate(claim.acknowledgedOn)
        : claim.acknowledgeBy ? `by ${longDate(claim.acknowledgeBy)}` : '',
      state: claim.acknowledgedOn ? 'done' : 'current',
    },
    {
      label: claim.stage === 'ACTION_NEEDED' ? 'Waiting for you' : 'Adjuster review',
      detail: '',
      state: decided ? 'done' : claim.acknowledgedOn ? 'current' : 'todo',
    },
    {
      label: 'Decision',
      detail: decided ? '' : claim.decisionBy ? `by ${longDate(claim.decisionBy)}` : '',
      state: decided ? 'done' : 'todo',
    },
  ];
  const tone = claim.stage === 'ACTION_NEEDED' ? 'warning' : claim.stage === 'ACCEPTED' ? 'success' : 'info';

  return (
    <>
      <Card className="p-status">
        <div className="p-status-head">
          <div>
            <div className="p-eyebrow">Claim number</div>
            <div className="p-claim-no">{claim.claimId}</div>
          </div>
          <div className="p-status-meta">
            <span>{claim.lineOfBusiness === 'HOME' ? 'Home' : 'Vehicle'} claim</span>
            <span>Loss on {longDate(claim.incidentDate)}</span>
          </div>
        </div>
        <ol className="p-progress" aria-label="Claim progress">
          {steps.map((s) => (
            <li key={s.label} className={s.state} aria-current={s.state === 'current' ? 'step' : undefined}>
              <span className="p-dot" aria-hidden="true">{s.state === 'done' ? '✓' : ''}</span>
              <span className="p-step-label">{s.label}</span>
              {s.detail && <span className="p-step-detail">{s.detail}</span>}
            </li>
          ))}
        </ol>
        <Alert tone={tone} title={claim.stageTitle}>{claim.stageText}</Alert>
        {claim.photosRequested.length > 0 && (
          <p className="p-muted">Photos requested: {claim.photosRequested.map(angleLabel).join(', ')}. The upload link
            works once and expires after 7 days.</p>
        )}
        {!decided && claim.decisionBy && (
          <p className="p-muted">Your state requires a decision by {longDate(claim.decisionBy)}, counted from when we have
            everything we need. If we need more time, we will tell you why in writing.</p>
        )}
      </Card>

      {claim.letter && (
        <Card title="Your acknowledgement letter">
          <button type="button" className="p-btn p-btn-link" aria-expanded={showLetter}
            onClick={() => setShowLetter(!showLetter)}>
            {showLetter ? 'Hide letter' : 'Read the letter'}
          </button>
          {showLetter && <pre className="p-letter">{claim.letter}</pre>}
        </Card>
      )}
    </>
  );
}
