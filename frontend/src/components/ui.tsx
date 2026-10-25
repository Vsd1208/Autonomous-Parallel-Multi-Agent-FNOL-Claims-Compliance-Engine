import React from 'react';

/** Small building blocks in the style of a claims-system workspace: panels, detail views, list views. */

export function Panel({ title, actions, children, flush }: {
  title: string;
  actions?: React.ReactNode;
  children: React.ReactNode;
  flush?: boolean;
}) {
  return (
    <section className="panel">
      <header className="panel-title">
        <h2>{title}</h2>
        {actions && <div className="panel-actions">{actions}</div>}
      </header>
      <div className={flush ? 'panel-body flush' : 'panel-body'}>{children}</div>
    </section>
  );
}

/** Label/value pairs laid out in two aligned columns. */
export function DetailView({ rows, columns = 1 }: { rows: [string, React.ReactNode][]; columns?: 1 | 2 }) {
  return (
    <dl className={`detail-view cols-${columns}`}>
      {rows.map(([label, value]) => (
        <div className="detail-row" key={label}>
          <dt>{label}</dt>
          <dd>{value === null || value === undefined || value === '' ? <span className="muted">—</span> : value}</dd>
        </div>
      ))}
    </dl>
  );
}

export function ListView({ head, children, empty }: { head: string[]; children: React.ReactNode; empty?: string }) {
  const hasRows = React.Children.count(children) > 0;
  return (
    <div className="list-scroll">
      <table className="list-view">
        <thead>
          <tr>{head.map((h) => <th key={h}>{h}</th>)}</tr>
        </thead>
        <tbody>
          {hasRows ? children : (
            <tr><td colSpan={head.length} className="list-empty">{empty ?? 'No data'}</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

export type Tone = 'info' | 'success' | 'warning' | 'error';

export function MessageBar({ tone, title, children }: { tone: Tone; title?: string; children?: React.ReactNode }) {
  const icon = { info: 'i', success: '✓', warning: '!', error: '✕' }[tone];
  return (
    <div className={`message-bar ${tone}`} role={tone === 'error' ? 'alert' : 'status'}>
      <span className="message-icon" aria-hidden="true">{icon}</span>
      <div>
        {title && <strong>{title}</strong>}
        {children && <div className="message-text">{children}</div>}
      </div>
    </div>
  );
}

export function Toolbar({ children }: { children: React.ReactNode }) {
  return <div className="toolbar">{children}</div>;
}

export function Button({ variant = 'secondary', ...props }:
  React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'danger' }) {
  return <button type="button" {...props} className={`btn btn-${variant} ${props.className ?? ''}`} />;
}

const STATUS_TONE: Record<string, Tone | 'neutral'> = {
  STRAIGHT_THROUGH_PROCESSING: 'success',
  APPROVED_BY_ADJUSTER: 'success',
  PENDING_ADVISORY_REVIEW: 'info',
  HUMAN_REVIEW: 'warning',
  NEEDS_MORE_INFO: 'warning',
  INVESTIGATION_REQUIRED: 'warning',
  INSPECTION_ASSIGNED: 'info',
  ASSIGNED: 'info',
  COMPLETED: 'success',
  CANCELLED: 'neutral',
  BLOCKED_COMPLIANCE: 'error',
  REJECTED_BY_ADJUSTER: 'error',
  CLOSED: 'neutral',
  PASS: 'success',
  SUCCESS: 'success',
  MONITOR: 'success',
  INVESTIGATE: 'warning',
  DUPLICATE_FNOL: 'warning',
  COMPLIANCE_BLOCK: 'error',
  PROCESSING_TIMEOUT: 'error',
  EXTERNAL_OR_PROCESSING_ERROR: 'error',
  FAIL: 'error',
  OK: 'success',
  WARNING: 'warning',
  CRITICAL: 'error',
  SYNCED: 'success',
  FAILED: 'error',
  NOT_FILED: 'neutral',
  LOW: 'success',
  MEDIUM: 'warning',
  HIGH: 'error',
};

export function StatusChip({ value }: { value?: string | null }) {
  if (!value) return <span className="muted">—</span>;
  return <span className={`chip ${STATUS_TONE[value] ?? 'neutral'}`}>{humanize(value)}</span>;
}

const CHANNELS: Record<string, string> = {
  WEB: 'Web portal', MOBILE: 'Mobile app', PHONE: 'Phone (agent)', API: 'API / partner',
};

export function channelLabel(value?: string | null): string {
  return value ? CHANNELS[value] ?? value : '—';
}

export function humanize(value: string): string {
  return value.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase());
}

export function money(n?: number | null): string {
  if (n === null || n === undefined || Number.isNaN(Number(n))) return '—';
  return Number(n).toLocaleString('en-US', { style: 'currency', currency: 'USD' });
}

export function dateTime(value?: string | null): string {
  return value ? new Date(value).toLocaleString() : '—';
}
