import React, { useEffect, useState } from 'react';
import { angleLabel, anglesFor } from '../photoAngles';
import { apiClient } from '../services/apiClient';
import { ClaimDocument, UploadRequest } from '../types';
import { Button, ListView, MessageBar, Panel, StatusChip, dateTime } from './ui';

/**
 * Adjuster: ask the claimant for more photos. Pre-selects the standard views that are missing. The link is
 * shown once, to be sent to the claimant (this prototype does not send e-mail or SMS).
 */
export function RequestPhotos({ claimId, policyNumber, docs, adjusterId, onChanged }: {
  claimId: string; policyNumber: string; docs: ClaimDocument[]; adjusterId: string; onChanged: () => void;
}) {
  const views = anglesFor(policyNumber);
  const have = new Set(docs.map((d) => d.angle));
  const missingRequired = views.filter((v) => v.required && !have.has(v.key)).map((v) => v.key);
  const [selected, setSelected] = useState<string[]>(missingRequired.length ? missingRequired : ['CLOSE_UP']);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [link, setLink] = useState<{ url: string; expiresAt: string } | null>(null);
  const [copied, setCopied] = useState(false);

  const toggle = (key: string) =>
    setSelected((s) => (s.includes(key) ? s.filter((k) => k !== key) : [...s, key]));

  const create = async () => {
    setBusy(true);
    setError('');
    try {
      const res = await apiClient.createUploadRequest(claimId, { requestedBy: adjusterId, angles: selected, message });
      setLink({ url: res.uploadUrl, expiresAt: res.expiresAt });
      onChanged();
    } catch (err: any) {
      setError(err.message || 'The request could not be created');
    } finally {
      setBusy(false);
    }
  };

  const copy = async () => {
    if (!link) return;
    try {
      await navigator.clipboard.writeText(link.url);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };

  if (link) {
    return (
      <Panel title="Photo Request Sent">
        <MessageBar tone="success" title="Send this link to the claimant">
          It works once and expires {dateTime(link.expiresAt)}. The claim waits in "Needs more info" and returns to
          your queue when the photos arrive. (Prototype: copy the link into an e-mail or SMS.)
        </MessageBar>
        <div className="link-row">
          <input readOnly value={link.url} aria-label="Upload link" onFocus={(e) => e.target.select()} />
          <Button onClick={copy}>{copied ? 'Copied' : 'Copy link'}</Button>
        </div>
      </Panel>
    );
  }

  return (
    <Panel title="Request More Photos">
      {error && <MessageBar tone="error" title="Not sent">{error}</MessageBar>}
      <p className="hint">Choose the photos the claimant should add. Missing standard views are pre-selected.</p>
      <div className="check-row">
        {views.map((v) => (
          <label key={v.key} className="check">
            <input type="checkbox" checked={selected.includes(v.key)} onChange={() => toggle(v.key)} />
            {v.label}{!have.has(v.key) && v.required ? ' (missing)' : ''}
          </label>
        ))}
      </div>
      <label className="field wide">
        <span className="field-label">Message to the claimant</span>
        <textarea rows={2} value={message} maxLength={500} onChange={(e) => setMessage(e.target.value)}
          placeholder="e.g. Please photograph the rear of the car from about 2 m, and a close-up of the dent." />
      </label>
      <div className="option-row">
        <Button variant="primary" disabled={busy || selected.length === 0 || !adjusterId.trim()} onClick={create}>
          {busy ? 'Creating…' : 'Create upload link'}
        </Button>
      </div>
    </Panel>
  );
}

/** The claim's photo requests, newest first, with their state. */
export function PhotoRequestList({ claimId, reload, onChanged }: { claimId: string; reload: number; onChanged: () => void }) {
  const [items, setItems] = useState<UploadRequest[]>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    apiClient.getUploadRequests(claimId).then(setItems).catch((err) => setError(err.message || 'Could not load requests'));
  }, [claimId, reload]);

  const cancel = async (id: string) => {
    try {
      await apiClient.cancelUploadRequest(claimId, id, 'ADJ-001');
      onChanged();
    } catch (err: any) {
      setError(err.message || 'Could not cancel');
    }
  };

  if (!items.length && !error) return null;
  return (
    <Panel title={`Photo Requests (${items.length})`} flush>
      {error && <MessageBar tone="error" title={error} />}
      <ListView head={['Requested', 'By', 'Views', 'Status', 'Photos received', '']}>
        {[...items].reverse().map((r) => (
          <tr key={r.requestId}>
            <td>{dateTime(r.createdAt)}</td>
            <td>{r.requestedBy}</td>
            <td>{r.requestedAngles.split(',').map(angleLabel).join(', ')}</td>
            <td>
              <StatusChip value={r.effectiveStatus} />
              {r.effectiveStatus === 'OPEN' && <span className="muted"> until {dateTime(r.expiresAt)}</span>}
            </td>
            <td className="num">{r.photosReceived ?? '—'}</td>
            <td>{r.effectiveStatus === 'OPEN' && <Button onClick={() => cancel(r.requestId)}>Cancel</Button>}</td>
          </tr>
        ))}
      </ListView>
    </Panel>
  );
}
