import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiClient } from '../services/apiClient';
import { AdjusterReviewRequest, ClaimResult } from '../types';

type Props = {
  claim: ClaimResult;
};

function ScoreBadge({
  label,
  value,
  pass,
}: {
  label: string;
  value: string;
  pass: boolean;
}) {
  return (
    <div className={`score-badge ${pass ? 'pass' : 'fail'}`}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

export default function ClaimDashboard({ claim }: Props) {
  const navigate = useNavigate();
  const [currentClaim, setCurrentClaim] = useState(claim);
  const [adjusterId, setAdjusterId] = useState('ADJ-001');
  const [reviewNotes, setReviewNotes] = useState('');
  const [reviewError, setReviewError] = useState('');
  const [savingDecision, setSavingDecision] = useState(false);
  const [reviewSaved, setReviewSaved] = useState('');
  claim = currentClaim;
  const factors = claim.explanation?.factors ?? [];
  const notes = claim.notes ?? [];
  const recommendedAction = claim.explanation?.recommendedAction ?? 'ESCALATE_TO_HUMAN';
  const needsHumanReview = recommendedAction.includes('ESCALATE');
  const flowStages = [
    ['1', 'FNOL Intake', 'Claim report received'],
    ['2', 'Context Enrichment', 'History and policy context'],
    ['3', 'Dual-Branch AI', 'Operational and compliance in parallel'],
    ['4', 'Safety Gate', 'Reconcile and validate'],
    ['5', needsHumanReview ? 'Human Review' : 'STP', needsHumanReview ? 'Adjuster final decision' : 'Proceed to next steps'],
  ];

  const submitReview = async (decision: AdjusterReviewRequest['decision']) => {
    setSavingDecision(true);
    setReviewError('');
    setReviewSaved('');

    try {
      const response = await apiClient.submitAdjusterReview(claim.claimId, {
        decision,
        adjusterId,
        notes: reviewNotes,
      });
      const nextClaim = {
        ...claim,
        status: response.status,
      };
      window.localStorage.setItem('fnol:lastClaim', JSON.stringify(nextClaim));
      setCurrentClaim(nextClaim);
      setReviewSaved(`${response.adjusterDecision.replace(/_/g, ' ')} saved at ${new Date(response.reviewedAt).toLocaleString()}`);
    } catch (err: any) {
      setReviewError(err.message || 'Unable to save adjuster decision');
    } finally {
      setSavingDecision(false);
    }
  };

  return (
    <main className="dashboard-page">
      <header className="topbar">
        <div>
          <p className="eyebrow">FNOL CLAIM REVIEW</p>
          <h1>Adjuster Intelligence Console</h1>
        </div>

        <div className="claim-meta">
          <span className="status-pill">{claim.status}</span>
          <span>Claim {claim.claimId}</span>
          <button
            onClick={() => navigate(`/audit/${claim.claimId}`)}
            style={{
              background: '#0055cc',
              color: 'white',
              border: 'none',
              borderRadius: '8px',
              padding: '8px 16px',
              cursor: 'pointer',
              fontWeight: '600',
            }}
          >
            View Audit Trail
          </button>
          <button
            onClick={() => navigate('/')}
            style={{
              background: '#6c757d',
              color: 'white',
              border: 'none',
              borderRadius: '8px',
              padding: '8px 16px',
              cursor: 'pointer',
              fontWeight: '600',
            }}
          >
            Back to Intake
          </button>
        </div>
      </header>

      <section className="flow-strip" aria-label="End-to-end FNOL processing flow">
        {flowStages.map(([step, title, detail]) => (
          <div className="flow-step" key={step}>
            <span>{step}</span>
            <strong>{title}</strong>
            <small>{detail}</small>
          </div>
        ))}
      </section>

      <section className="advisory-banner">
        <div className="advisory-icon">{needsHumanReview ? '!' : 'OK'}</div>
        <div>
          <strong>Adjuster Advisory</strong>
          <p>
            {claim.explanation.summary}. Recommended action:{' '}
            {recommendedAction.replace(/_/g, ' ')}.
          </p>
        </div>
      </section>

      <div className="badge-row">
        <div className="info-badge governance">
          Human-in-the-Loop Decision Support Only — Final Authority Retained by Handler
        </div>
        <div className="info-badge rules">
          ✓ Coverage verified by Guidewire PolicyCenter rules engine
        </div>
      </div>

      <section className="why-panel">
        <div className="panel-header">
          <h2>WHY this decision</h2>
          <span>Decision support explanation</span>
        </div>

        <div className="score-grid">
          {factors.map((factor) => (
            <ScoreBadge
              key={factor.factor}
              label={factor.factor}
              value={factor.value}
              pass={factor.status === 'PASS'}
            />
          ))}
        </div>
      </section>

      <section className="branch-grid">
        <div className="branch-panel branch-a">
          <h3>Branch A: Operational Damage & Reserve</h3>

          <div className="metric-grid">
            <div className="metric-box">
              <span>Damage Type</span>
              <strong>{claim.branchA.damageAssessment.damageType}</strong>
            </div>
            <div className="metric-box">
              <span>Severity</span>
              <strong>{claim.branchA.damageAssessment.severity}</strong>
            </div>
            <div className="metric-box">
              <span>Estimated Damage</span>
              <strong>${claim.branchA.damageAssessment.estimatedDamage.toFixed(2)}</strong>
            </div>
            <div className="metric-box">
              <span>Recommended Reserve</span>
              <strong>${claim.branchA.reserve.recommendedReserve.toFixed(2)}</strong>
            </div>
            <div className="metric-box">
              <span>Coverage Type</span>
              <strong>{claim.branchA.coverage.coverageType}</strong>
            </div>
            <div className="metric-box">
              <span>Coverage Limit</span>
              <strong>${claim.branchA.coverage.coverageLimit.toFixed(2)}</strong>
            </div>
          </div>
        </div>

        <div className="branch-panel branch-b">
          <h3>Branch B: Redacted PII, Statutory Deadlines, Subrogation</h3>

          <div className="metric-grid">
            <div className="metric-box">
              <span>PII Redaction</span>
              <strong>{claim.branchB.pii.piiDetected ? 'Detected & Redacted' : 'None Detected'}</strong>
            </div>
            <div className="metric-box">
              <span>Deadline</span>
              <strong>{claim.branchB.deadline.deadline}</strong>
            </div>
            <div className="metric-box">
              <span>Subrogation Score</span>
              <strong>{claim.branchB.subrogation.score}/100</strong>
            </div>
            <div className="metric-box">
              <span>Recommended Action</span>
              <strong>{claim.branchB.subrogation.recommendedAction}</strong>
            </div>
            <div className="metric-box">
              <span>State</span>
              <strong>{claim.branchB.deadline.state}</strong>
            </div>
            <div className="metric-box">
              <span>Rule Source</span>
              <strong>{claim.branchB.deadline.ruleSource}</strong>
            </div>
          </div>

          <div className="pii-box">
            <p className="label">Redacted Description</p>
            <p>{claim.branchB.pii.redactedDescription}</p>
          </div>

          {claim.branchB.deadline.disclaimer && (
            <div className="pii-box" style={{ marginTop: '12px', background: '#fff8e6' }}>
              <p className="label">Statutory Disclaimer</p>
              <p>{claim.branchB.deadline.disclaimer}</p>
            </div>
          )}
        </div>
      </section>

      <section className="explanation-panel">
        <h3>Decision Rationale</h3>
        <div className="rationale-summary">
          <strong>{claim.explanation.decision.replace(/_/g, ' ')}</strong>
          <p>{claim.explanation.summary}</p>
        </div>
        <ul>
          {factors.map((factor) => (
            <li key={factor.factor}>
              <strong>{factor.factor}:</strong> {factor.status} ({factor.value}) - {factor.detail}
            </li>
          ))}
          {notes.map((item, index) => (
            <li key={`note-${index}`}>{item}</li>
          ))}
        </ul>
      </section>

      <section className="review-console">
        <div className="panel-header">
          <h2>Adjuster Final Decision</h2>
          <span>{claim.status.replace(/_/g, ' ')}</span>
        </div>

        <div className="review-grid">
          <div className="form-field">
            <label>Adjuster ID</label>
            <input
              value={adjusterId}
              onChange={(event) => setAdjusterId(event.target.value)}
              placeholder="ADJ-001"
            />
          </div>
          <div className="form-field full-width">
            <label>Decision Notes</label>
            <textarea
              value={reviewNotes}
              onChange={(event) => setReviewNotes(event.target.value)}
              rows={4}
              placeholder="Record final review rationale, documents requested, or override reason..."
            />
          </div>
        </div>

        {reviewError && <div className="error-box">{reviewError}</div>}
        {reviewSaved && <div className="success-box">{reviewSaved}</div>}

        <div className="decision-actions">
          <button
            type="button"
            className="decision-btn approve"
            disabled={savingDecision || !adjusterId.trim()}
            onClick={() => submitReview('APPROVED_BY_ADJUSTER')}
          >
            Approve
          </button>
          <button
            type="button"
            className="decision-btn reject"
            disabled={savingDecision || !adjusterId.trim()}
            onClick={() => submitReview('REJECTED_BY_ADJUSTER')}
          >
            Reject
          </button>
          <button
            type="button"
            className="decision-btn info"
            disabled={savingDecision || !adjusterId.trim()}
            onClick={() => submitReview('NEEDS_MORE_INFO')}
          >
            Need More Info
          </button>
          <button
            type="button"
            className="decision-btn close"
            disabled={savingDecision || !adjusterId.trim()}
            onClick={() => submitReview('CLOSED')}
          >
            Close
          </button>
        </div>
      </section>
    </main>
  );
}
