import React from 'react';

export function Alert({ tone, title, children }: {
  tone: 'info' | 'success' | 'warning' | 'error'; title: string; children?: React.ReactNode;
}) {
  return (
    <div className={`p-alert p-alert-${tone}`} role={tone === 'error' || tone === 'warning' ? 'alert' : 'status'}>
      <strong>{title}</strong>
      {children && <div className="p-alert-body">{children}</div>}
    </div>
  );
}

export function Card({ title, children, className }: { title?: string; children: React.ReactNode; className?: string }) {
  return (
    <section className={`p-card${className ? ' ' + className : ''}`}>
      {title && <h2 className="p-card-title">{title}</h2>}
      {children}
    </section>
  );
}

export function Field({ label, hint, required, children, wide }: {
  label: string; hint?: string; required?: boolean; children: React.ReactNode; wide?: boolean;
}) {
  return (
    <label className={`p-field${wide ? ' wide' : ''}`}>
      <span className="p-label">{label}{!required && <span className="p-optional"> (optional)</span>}</span>
      {children}
      {hint && <span className="p-hint">{hint}</span>}
    </label>
  );
}

/** "12 November 2026" from an ISO date; '' when missing. */
export function longDate(iso?: string | null): string {
  if (!iso) return '';
  const d = new Date(iso.length === 10 ? iso + 'T00:00:00' : iso);
  return Number.isNaN(d.getTime()) ? iso
    : d.toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' });
}

export const STATES: [string, string][] = [
  ['CA', 'California'], ['NY', 'New York'], ['TX', 'Texas'], ['FL', 'Florida'], ['IL', 'Illinois'],
];
