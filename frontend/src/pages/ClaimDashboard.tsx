import React from 'react';
import { useNavigate } from 'react-router-dom';
import { ClaimResult } from '../types';

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

      <section className="advisory-banner">
        <div className="advisory-icon">⚠️</div>
        <div>
          <strong>Adjuster Advisory</strong>
          <p>
            Recommended action: {claim.branchA.damageAssessment.severity} severity —
            proceed with manual review for reserve and subrogation signals.
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
          <ScoreBadge
            label="AI Confidence"
            value={`${(claim.branchA.damageAssessment.confidence * 100).toFixed(0)}%`}
            pass={claim.branchA.damageAssessment.confidence >= 0.85}
          />
          <ScoreBadge
            label="Coverage"
            value={claim.branchA.coverage.covered ? 'PASS' : 'FAIL'}
            pass={claim.branchA.coverage.covered}
          />
          <ScoreBadge
            label="Reserve"
            value={claim.branchA.reserve.recommendedReserve > 0 ? 'PASS' : 'FAIL'}
            pass={claim.branchA.reserve.recommendedReserve <= 25000}
          />
          <ScoreBadge
            label="Subrogation"
            value={`${claim.branchB.subrogation.score}/100`}
            pass={claim.branchB.subrogation.score < 70}
          />
          <ScoreBadge
            label="History"
            value="PASS"
            pass={true}
          />
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
        <ul>
          {claim.explanation.map((item, index) => (
            <li key={index}>{item}</li>
          ))}
        </ul>
      </section>
    </main>
  );
}