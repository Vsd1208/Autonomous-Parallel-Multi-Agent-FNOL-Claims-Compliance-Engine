import React, { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { PageTitle, Sidebar, Workspace } from '../components/Shell';
import { Button, ListView, MessageBar, Panel, StatusChip, Toolbar, dateTime, humanize, money } from '../components/ui';
import { apiClient } from '../services/apiClient';
import { QueuePage } from '../types';

const VIEWS: Record<string, { label: string; statuses: string[]; hint: string }> = {
  decision: {
    label: 'Needs Decision',
    statuses: ['HUMAN_REVIEW', 'NEEDS_MORE_INFO', 'INVESTIGATION_REQUIRED'],
    hint: 'Claims the AI escalated to a human, oldest first.',
  },
  advisory: {
    label: 'Advisory Confirmation',
    statuses: ['PENDING_ADVISORY_REVIEW'],
    hint: 'STP-eligible claims waiting for an adjuster to confirm the AI advisory.',
  },
  blocked: {
    label: 'Blocked (Compliance)',
    statuses: ['BLOCKED_COMPLIANCE'],
    hint: 'Intakes stopped by the PII/compliance guardrail.',
  },
  closed: {
    label: 'Decided',
    statuses: ['APPROVED_BY_ADJUSTER', 'REJECTED_BY_ADJUSTER', 'CLOSED', 'STRAIGHT_THROUGH_PROCESSING'],
    hint: 'Claims with a final decision or filed straight through.',
  },
};

/** Desktop: the adjuster's work queues. */
export default function Desktop() {
  const navigate = useNavigate();
  const { view = 'decision' } = useParams<{ view: string }>();
  const current = VIEWS[view] ?? VIEWS.decision;
  const [page, setPage] = useState(0);
  const [data, setData] = useState<QueuePage | null>(null);
  const [error, setError] = useState('');

  useEffect(() => setPage(0), [view]);

  useEffect(() => {
    setData(null);
    setError('');
    apiClient
      .getQueue(current.statuses, page, 20)
      .then(setData)
      .catch((err) => setError(err.message || 'Unable to load the queue'));
  }, [current, page]);

  const sidebar = (
    <Sidebar
      actions={<Button variant="primary" onClick={() => navigate('/new')}>New Claim</Button>}
      title="My Queues"
      items={Object.entries(VIEWS).map(([key, v]) => ({
        to: key === 'decision' ? '/' : `/queue/${key}`,
        label: v.label,
        end: true,
        badge: key === view && data ? data.totalElements : undefined,
      }))}
    />
  );

  return (
    <Workspace sidebar={sidebar}>
      <PageTitle title={`Desktop: ${current.label}`} subtitle={current.hint} />
      {error && <MessageBar tone="error" title="The queue could not be loaded">{error}</MessageBar>}
      <Panel
        title={`Claims (${data ? data.totalElements : '…'})`}
        flush
        actions={
          data && data.totalPages > 1 ? (
            <Toolbar>
              <Button disabled={page === 0} onClick={() => setPage(page - 1)}>‹ Prev</Button>
              <span className="muted">Page {page + 1} of {data.totalPages}</span>
              <Button disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next ›</Button>
            </Toolbar>
          ) : undefined
        }
      >
        <ListView
          head={['Claim #', 'Status', 'Policy', 'Severity', 'Reserve', 'Flags / reason', 'Received']}
          empty={data ? 'No claims in this queue.' : 'Loading…'}
        >
          {data?.items.map((item) => {
            const flags = [item.inputFlags, item.gateFlags].filter(Boolean).join(',');
            return (
              <tr key={item.claimId} className="clickable" onClick={() => navigate(`/claim/${item.claimId}`)}>
                <td><a href={`/claim/${item.claimId}`} onClick={(e) => e.preventDefault()}>{item.claimId}</a></td>
                <td><StatusChip value={item.status} /></td>
                <td>{item.policyNumber}</td>
                <td><StatusChip value={item.severityGate} /></td>
                <td className="num">{money(item.reserve)}</td>
                <td className="wrap">
                  {item.failureReason ?? (flags ? flags.split(',').map(humanize).join(', ') : <span className="muted">—</span>)}
                </td>
                <td>{dateTime(item.createdAt)}</td>
              </tr>
            );
          })}
        </ListView>
      </Panel>
    </Workspace>
  );
}
