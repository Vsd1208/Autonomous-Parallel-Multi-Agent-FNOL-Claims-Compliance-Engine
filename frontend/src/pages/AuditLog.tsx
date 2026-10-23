import React, { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { apiClient } from '../services/apiClient';
import { AuditTrail } from '../types';

/** Audit trail for one claim, straight from the server's hash chain. */
export default function AuditLog() {
  const { claimId = '' } = useParams<{ claimId: string }>();
  const navigate = useNavigate();
  const [trail, setTrail] = useState<AuditTrail | null>(null);
  const [error, setError] = useState('');
  const [downloading, setDownloading] = useState(false);

  useEffect(() => {
    setTrail(null);
    setError('');
    apiClient
      .getAuditTrail(claimId)
      .then(setTrail)
      .catch((err) => setError(err.message || 'Unable to load audit trail'));
  }, [claimId]);

  const handleExportPDF = async () => {
    setDownloading(true);
    setError('');
    try {
      await apiClient.downloadAuditPdf(claimId);
    } catch (err: any) {
      setError(err.message || 'Failed to download PDF');
    } finally {
      setDownloading(false);
    }
  };

  return (
    <main className="dashboard-page">
      <header className="topbar">
        <div>
          <p className="eyebrow">AUDIT TRAIL</p>
          <h1>Hash-Chained Event Log</h1>
        </div>
        <div className="claim-meta">
          <span>Claim {claimId}</span>
          <button className="nav-btn" onClick={() => navigate(`/claim/${claimId}`)}>Back to Claim</button>
          <button className="nav-btn secondary" onClick={() => navigate('/queue')}>Adjuster Queue</button>
        </div>
      </header>

      {error && <div className="error-box">{error}</div>}

      {trail && (
        <div className="badge-row">
          <div className={`chain-badge ${trail.chainValid ? 'valid' : 'broken'}`}>
            {trail.chainValid
              ? 'Chain valid — every SHA-256 link recomputes'
              : 'Chain BROKEN — stored hashes do not recompute (tampering or pre-chain records)'}
          </div>
        </div>
      )}

      <section className="explanation-panel" style={{ marginBottom: '24px' }}>
        <h3>How This Audit Trail Works</h3>
        <ul>
          <li>Each agent's sanitized input and its output are hashed with SHA-256; raw PII is never hashed.</li>
          <li>Each event's chain hash is SHA-256(previous chain hash + input hash + output hash).</li>
          <li>Changing, removing or reordering an event breaks every later link, which the server detects.</li>
          <li>Adjuster decisions and failed intakes are recorded in the same chain.</li>
        </ul>
      </section>

      <section className="why-panel">
        <div className="panel-header">
          <h2>Event Timeline</h2>
          <span>{trail ? `${trail.records.length} events recorded` : 'Loading…'}</span>
        </div>
        <div style={{ marginTop: '20px' }}>
          {trail?.records.map((event) => (
            <div className="audit-event" key={event.eventId ?? `${event.seq}-${event.agentName}`}>
              <div>
                <div className="audit-event-title">
                  #{event.seq ?? '—'} {event.agentName}
                  {event.actor && <span className="muted"> · {event.actor}</span>}
                </div>
                <div className="muted">{event.timestamp}</div>
                <div className="hash-line"><strong>Input:</strong> {event.inputHash?.substring(0, 24)}…</div>
                <div className="hash-line"><strong>Output:</strong> {event.outputHash?.substring(0, 24)}…</div>
                <div className="hash-line"><strong>Chain:</strong> {event.chainHash ? `${event.chainHash.substring(0, 24)}…` : 'none'}</div>
              </div>
              <span className="audit-status">{event.status}</span>
            </div>
          ))}
        </div>
      </section>

      <div style={{ textAlign: 'center', margin: '32px 0' }}>
        <button className="submit-btn" style={{ maxWidth: 360 }} onClick={handleExportPDF} disabled={downloading || !trail}>
          {downloading ? 'Downloading PDF…' : 'Download Audit Report (PDF)'}
        </button>
      </div>
    </main>
  );
}
