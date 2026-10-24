import React, { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { apiClient } from '../services/apiClient';
import { claimStore } from '../services/claimStore';
import { AdjusterDecision, ClaimResult, DashboardClaim } from '../types';

const REVIEWABLE = new Set(['HUMAN_REVIEW', 'PENDING_ADVISORY_REVIEW', 'NEEDS_MORE_INFO', 'INVESTIGATION_REQUIRED']);

function ScoreBadge({ label, value, pass }: { label: string; value: string; pass: boolean }) {
  return (
    <div className={`score-badge ${pass ? 'pass' : 'fail'}`}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

const money = (n: number | undefined | null) => (n == null ? '—' : `$${Number(n).toFixed(2)}`);

export function fromIntake(claim: ClaimResult): DashboardClaim {
  return { ...claim, inputFlags: claim.inputFlags ?? [] };
}

/** Route /claim/:claimId — uses the stored intake result when present, otherwise loads from the API. */
export function ClaimRoute() {
  const { claimId = '' } = useParams<{ claimId: string }>();
  const [claim, setClaim] = useState<DashboardClaim | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setClaim(null);
    setError('');
    (async () => {
      try {
        const record = await apiClient.getClaimStatus(claimId);
        const explanation = await apiClient.getExplanation(claimId).catch(() => null);
        const stored = claimStore.get(claimId);
        const loaded: DashboardClaim = {
          ...(stored ? fromIntake(stored) : {}),
          claimId: record.claimId,
          status: record.status,
          explanation: explanation ?? stored?.explanation ?? null,
          inputFlags: record.inputFlags ? record.inputFlags.split(',') : stored?.inputFlags ?? [],
          gateFlags: record.gateFlags ? record.gateFlags.split(',') : stored?.gateFlags ?? [],
          failureReason: record.failureReason,
          ccSyncStatus: record.ccSyncStatus,
          record,
        };
        if (!cancelled) setClaim(loaded);
      } catch (err: any) {
        const stored = claimStore.get(claimId);
        if (cancelled) return;
        if (stored) setClaim(fromIntake(stored));
        else setError(err.message || 'Unable to load claim');
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [claimId]);

  if (error) {
    return (
      <main className="dashboard-page">
        <div className="error-box">Claim {claimId}: {error}</div>
      </main>
    );
  }
  if (!claim) {
    return (
      <main className="dashboard-page">
        <p>Loading claim {claimId}…</p>
      </main>
    );
  }
  return <ClaimDashboard claim={claim} />;
}

export default function ClaimDashboard({ claim: initial }: { claim: DashboardClaim }) {
  const navigate = useNavigate();
  const [claim, setClaim] = useState(initial);
  const [adjusterId, setAdjusterId] = useState('ADJ-001');
  const [reviewNotes, setReviewNotes] = useState('');
  const [reviewError, setReviewError] = useState('');
  const [savingDecision, setSavingDecision] = useState(false);
  const [reviewSaved, setReviewSaved] = useState('');
  const [adjustedReserve, setAdjustedReserve] = useState('');
  const [overrideReason, setOverrideReason] = useState('');

  useEffect(() => setClaim(initial), [initial]);

  const explanation = claim.explanation;
  const factors = explanation?.factors ?? [];
  const notes = claim.notes ?? [];
  const inputFlags = claim.inputFlags ?? [];
  const gateFlags = claim.gateFlags ?? [];
  const recommendedAction = explanation?.recommendedAction ?? 'ESCALATE_TO_HUMAN';
  const reviewable = REVIEWABLE.has(claim.status);
  const deadline = claim.branchB?.deadline;
  const lastStage =
    claim.status === 'STRAIGHT_THROUGH_PROCESSING'
      ? ['STP', 'Filed without a human step']
      : claim.status === 'PENDING_ADVISORY_REVIEW'
        ? ['Advisory Review', 'Adjuster confirms the STP advisory']
        : ['Human Review', 'Adjuster final decision'];
  const flowStages = [
    ['1', 'FNOL Intake', inputFlags.length ? `${inputFlags.length} intake flag(s)` : 'Intake review passed'],
    ['2', 'Context Enrichment', 'History and policy context'],
    ['3', 'Dual-Branch AI', 'Operational and legal in parallel'],
    ['4', 'Safety Gate', 'Reconcile, explain, pre-file'],
    ['5', ...lastStage],
  ];

  const submitReview = async (decision: AdjusterDecision) => {
    setSavingDecision(true);
    setReviewError('');
    setReviewSaved('');
    try {
      const override = adjustedReserve.trim()
        ? { adjustedReserve: Number(adjustedReserve), overrideReason: overrideReason.trim() }
        : {};
      const response = await apiClient.submitAdjusterReview(claim.claimId, {
        decision,
        adjusterId,
        notes: reviewNotes,
        ...override,
      });
      claimStore.updateStatus(claim.claimId, response.status);
      setClaim({ ...claim, status: response.status });
      setReviewSaved(
        `${response.adjusterDecision.replace(/_/g, ' ')} saved at ${new Date(response.reviewedAt).toLocaleString()}`
      );
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
          <button className="nav-btn" onClick={() => navigate(`/audit/${claim.claimId}`)}>View Audit Trail</button>
          <button className="nav-btn secondary" onClick={() => navigate('/queue')}>Adjuster Queue</button>
          <button className="nav-btn secondary" onClick={() => navigate('/')}>Back to Intake</button>
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

      {claim.failureReason && (
        <div className="warning-box">
          <strong>Intake failed and was saved for human attention:</strong> {claim.failureReason}
        </div>
      )}

      {inputFlags.length > 0 && (
        <section className="flag-row" aria-label="Intake review flags">
          <strong>Intake review flags:</strong>
          {inputFlags.map((flag) => (
            <span key={flag} className="flag-chip">{flag.replace(/_/g, ' ')}</span>
          ))}
        </section>
      )}

      {gateFlags.length > 0 && (
        <section className="flag-row" aria-label="Safety gate flags">
          <strong>Safety gate flags:</strong>
          {gateFlags.map((flag) => (
            <span key={flag} className="flag-chip">{flag.replace(/_/g, ' ')}</span>
          ))}
        </section>
      )}

      {explanation && (
        <section className="advisory-banner">
          <div className="advisory-icon">{recommendedAction.includes('ESCALATE') ? '!' : 'OK'}</div>
          <div>
            <strong>Adjuster Advisory</strong>
            <p>
              {explanation.summary}. Recommended action: {recommendedAction.replace(/_/g, ' ')}.
            </p>
          </div>
        </section>
      )}

      <div className="badge-row">
        <div className="info-badge governance">
          Human-in-the-Loop Decision Support Only — Final Authority Retained by Handler
        </div>
        {claim.ccSyncStatus && <div className="info-badge rules">ClaimCenter sync: {claim.ccSyncStatus}</div>}
      </div>

      {factors.length > 0 && (
        <section className="why-panel">
          <div className="panel-header">
            <h2>WHY this decision</h2>
            <span>Severity {explanation?.severityGate}</span>
          </div>
          <div className="score-grid">
            {factors.map((factor) => (
              <ScoreBadge key={factor.factor} label={factor.factor} value={factor.value} pass={factor.status === 'PASS'} />
            ))}
          </div>
        </section>
      )}

      {claim.branchA && claim.branchB ? (
        <section className="branch-grid">
          <div className="branch-panel branch-a">
            <h3>Branch A: Operational Damage & Reserve</h3>
            <div className="metric-grid">
              <div className="metric-box"><span>Damage Type</span><strong>{claim.branchA.damageAssessment.damageType}</strong></div>
              <div className="metric-box"><span>Severity</span><strong>{claim.branchA.damageAssessment.severity}</strong></div>
              <div className="metric-box"><span>Estimated Damage</span><strong>{money(claim.branchA.damageAssessment.estimatedDamage)}</strong></div>
              <div className="metric-box"><span>Recommended Reserve</span><strong>{money(claim.branchA.reserve.recommendedReserve)}</strong></div>
              <div className="metric-box"><span>Coverage Type</span><strong>{claim.branchA.coverage.coverageType ?? 'NONE'}</strong></div>
              <div className="metric-box"><span>Coverage Limit</span><strong>{money(claim.branchA.coverage.coverageLimit)}</strong></div>
              <div className="metric-box">
                <span>Reserve Confidence</span>
                <strong>{claim.branchA.reserve.confidence != null ? Number(claim.branchA.reserve.confidence).toFixed(2) : '—'}</strong>
              </div>
            </div>
            {claim.branchA.reserve.confidenceBasis && <p className="muted">{claim.branchA.reserve.confidenceBasis}</p>}
          </div>

          <div className="branch-panel branch-b">
            <h3>Branch B: Redacted PII, Statutory Deadlines, Subrogation</h3>
            <div className="metric-grid">
              <div className="metric-box">
                <span>PII Redaction</span>
                <strong>{claim.branchB.pii.piiDetected ? 'Detected & Redacted' : 'None Detected'}</strong>
              </div>
              <div className="metric-box"><span>Acknowledge By</span><strong>{deadline?.acknowledgeBy ?? deadline?.deadline}</strong></div>
              <div className="metric-box"><span>Decide By</span><strong>{deadline?.decisionBy ?? '—'}</strong></div>
              <div className="metric-box">
                <span>Deadline Urgency</span>
                <strong className={`urgency ${(deadline?.urgencyFlag ?? 'OK').toLowerCase()}`}>{deadline?.urgencyFlag ?? '—'}</strong>
              </div>
              <div className="metric-box"><span>Subrogation Score</span><strong>{claim.branchB.subrogation.score}/100</strong></div>
              <div className="metric-box"><span>Subrogation Action</span><strong>{claim.branchB.subrogation.recommendedAction}</strong></div>
            </div>
            <div className="pii-box">
              <p className="label">Redacted Description</p>
              <p>{claim.branchB.pii.redactedDescription}</p>
            </div>
            {(claim.branchB.subrogation.complianceNotes ?? []).length > 0 && (
              <div className="pii-box" style={{ marginTop: '12px' }}>
                <p className="label">Subrogation Compliance Notes</p>
                <ul>
                  {claim.branchB.subrogation.complianceNotes!.map((note) => (
                    <li key={note}>{note}</li>
                  ))}
                </ul>
              </div>
            )}
            {deadline?.disclaimer && (
              <div className="pii-box" style={{ marginTop: '12px', background: '#fff8e6' }}>
                <p className="label">Statutory Disclaimer</p>
                <p>{deadline.disclaimer}</p>
              </div>
            )}
          </div>
        </section>
      ) : (
        claim.record && (
          <section className="branch-grid">
            <div className="branch-panel branch-a">
              <h3>Saved Claim</h3>
              <div className="metric-grid">
                <div className="metric-box"><span>Policy</span><strong>{claim.record.policyNumber}</strong></div>
                <div className="metric-box"><span>Incident</span><strong>{claim.record.incidentDate} ({claim.record.state})</strong></div>
                <div className="metric-box"><span>Estimated Damage</span><strong>{money(claim.record.estimatedDamage)}</strong></div>
                <div className="metric-box"><span>Reserve</span><strong>{money(claim.record.reserve)}</strong></div>
                <div className="metric-box"><span>Channel</span><strong>{claim.record.channel ?? '—'}</strong></div>
                <div className="metric-box"><span>Adjuster Decision</span><strong>{claim.record.adjusterDecision ?? 'PENDING'}</strong></div>
                {claim.record.adjusterReserve != null && (
                  <div className="metric-box"><span>Adjusted Reserve</span><strong>{money(claim.record.adjusterReserve)}</strong></div>
                )}
              </div>
              <p className="muted">Branch details are only kept by the console that submitted the claim; the WHY panel and audit trail come from the server.</p>
            </div>
          </section>
        )
      )}

      {(explanation || notes.length > 0) && (
        <section className="explanation-panel">
          <h3>Decision Rationale</h3>
          {explanation && (
            <div className="rationale-summary">
              <strong>{explanation.decision.replace(/_/g, ' ')}</strong>
              <p>{explanation.summary}</p>
            </div>
          )}
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
      )}

      <section className="review-console">
        <div className="panel-header">
          <h2>Adjuster Final Decision</h2>
          <span>{claim.status.replace(/_/g, ' ')}</span>
        </div>

        {!reviewable && (
          <div className="success-box">
            This claim is {claim.status.replace(/_/g, ' ').toLowerCase()} and cannot take another decision.
          </div>
        )}

        <div className="review-grid">
          <div className="form-field">
            <label>Adjuster ID</label>
            <input value={adjusterId} onChange={(event) => setAdjusterId(event.target.value)} placeholder="ADJ-001" />
          </div>
          <div className="form-field">
            <label>Adjusted Reserve (optional)</label>
            <input
              type="number"
              min="0"
              step="1"
              value={adjustedReserve}
              onChange={(event) => setAdjustedReserve(event.target.value)}
              placeholder={claim.branchA ? String(claim.branchA.reserve.recommendedReserve) : 'AI reserve'}
            />
          </div>
          <div className="form-field">
            <label>Reason for Changing the AI Finding</label>
            <input
              value={overrideReason}
              onChange={(event) => setOverrideReason(event.target.value)}
              placeholder="Required when the reserve is adjusted"
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
          {(
            [
              ['APPROVED_BY_ADJUSTER', 'approve', 'Approve'],
              ['REJECTED_BY_ADJUSTER', 'reject', 'Reject'],
              ['NEEDS_MORE_INFO', 'info', 'Need More Info'],
              ['INVESTIGATION_REQUIRED', 'info', 'Require Investigation'],
              ['CLOSED', 'close', 'Close'],
            ] as [AdjusterDecision, string, string][]
          ).map(([decision, css, label]) => (
            <button
              key={decision}
              type="button"
              className={`decision-btn ${css}`}
              disabled={
                savingDecision || !adjusterId.trim() || !reviewable || (!!adjustedReserve.trim() && !overrideReason.trim())
              }
              onClick={() => submitReview(decision)}
            >
              {label}
            </button>
          ))}
        </div>
      </section>
    </main>
  );
}
