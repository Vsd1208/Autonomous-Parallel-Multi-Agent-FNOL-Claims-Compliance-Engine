import React, { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { apiClient } from '../services/apiClient';
import { QueuePage } from '../types';

const STATUS_FILTERS: Record<string, string[]> = {
  'Needs decision': ['HUMAN_REVIEW', 'NEEDS_MORE_INFO'],
  'Advisory confirmation': ['PENDING_ADVISORY_REVIEW'],
  'Blocked (compliance)': ['BLOCKED_COMPLIANCE'],
};

/** Adjuster work queue, oldest first. */
export default function ClaimQueue() {
  const navigate = useNavigate();
  const [filter, setFilter] = useState('Needs decision');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<QueuePage | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    setError('');
    apiClient
      .getQueue(STATUS_FILTERS[filter], page, 20)
      .then(setData)
      .catch((err) => setError(err.message || 'Unable to load queue'));
  }, [filter, page]);

  return (
    <main className="dashboard-page">
      <header className="topbar">
        <div>
          <p className="eyebrow">ADJUSTER QUEUE</p>
          <h1>Claims Awaiting a Human</h1>
        </div>
        <div className="claim-meta">
          <button className="nav-btn secondary" onClick={() => navigate('/')}>Back to Intake</button>
        </div>
      </header>

      <div className="button-grid" style={{ marginBottom: 16 }}>
        {Object.keys(STATUS_FILTERS).map((name) => (
          <button
            key={name}
            className={`quick-load-btn ${filter === name ? 'active' : ''}`}
            onClick={() => {
              setFilter(name);
              setPage(0);
            }}
          >
            {name}
          </button>
        ))}
      </div>

      {error && <div className="error-box">{error}</div>}

      <section className="why-panel">
        <div className="panel-header">
          <h2>{filter}</h2>
          <span>{data ? `${data.totalElements} claim(s)` : 'Loading…'}</span>
        </div>
        <div className="table-scroll">
          <table className="queue-table">
            <thead>
              <tr>
                <th>Claim</th>
                <th>Status</th>
                <th>Policy</th>
                <th>Reserve</th>
                <th>Severity</th>
                <th>Flags / failure</th>
                <th>Received</th>
              </tr>
            </thead>
            <tbody>
              {data?.items.map((item) => (
                <tr key={item.claimId}>
                  <td><Link to={`/claim/${item.claimId}`}>{item.claimId}</Link></td>
                  <td>{item.status.replace(/_/g, ' ')}</td>
                  <td>{item.policyNumber}</td>
                  <td>${Number(item.reserve).toFixed(2)}</td>
                  <td>{item.severityGate ?? '—'}</td>
                  <td>{item.failureReason ?? item.inputFlags?.replace(/,/g, ', ') ?? '—'}</td>
                  <td>{new Date(item.createdAt).toLocaleString()}</td>
                </tr>
              ))}
              {data && data.items.length === 0 && (
                <tr>
                  <td colSpan={7} className="muted">No claims in this queue.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        {data && data.totalPages > 1 && (
          <div className="decision-actions">
            <button className="nav-btn secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
            <span>Page {page + 1} of {data.totalPages}</span>
            <button className="nav-btn secondary" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
          </div>
        )}
      </section>
    </main>
  );
}
