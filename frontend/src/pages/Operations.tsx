import React, { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { PageTitle } from '../components/Shell';
import { Button, ListView, MessageBar, Panel, StatusChip, dateTime, humanize } from '../components/ui';
import { apiClient } from '../services/apiClient';
import { EventInboxView } from '../types';

const STATUSES = ['DEAD_LETTER', 'RETRY_PENDING', 'PROCESSING', 'PENDING'];

/** The event inbox, for a supervisor: what is waiting, what failed, and a way to send a failed event round again. The server enforces who may. */
export default function Operations() {
  const [status, setStatus] = useState('DEAD_LETTER');
  const [view, setView] = useState<EventInboxView | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState('');

  const load = useCallback(() => {
    apiClient.getEventInbox(status).then((v) => { setView(v); setError(''); }).catch((err) => setError(err.message || 'Could not load the event inbox'));
  }, [status]);

  useEffect(load, [load]);

  const replay = async (id: string) => {
    setBusy(id);
    try {
      await apiClient.replayEvent(id);
      load();
    } catch (err: any) {
      setError(err.message || 'Could not replay the event');
    } finally {
      setBusy('');
    }
  };

  return (
    <div className="page">
      <PageTitle title="Operations" subtitle="Events from the claims system waiting for, or failing, analysis" />
      {error && <MessageBar tone="error" title="Event inbox">{error}</MessageBar>}
      <Panel title="Queue">
        <div className="toolbar">
          {STATUSES.map((s) => (
            <Button key={s} variant={s === status ? 'primary' : 'secondary'} onClick={() => setStatus(s)}>
              {humanize(s)} ({view?.depth?.[s] ?? '–'})
            </Button>
          ))}
        </div>
      </Panel>
      <Panel title={humanize(status)}>
        <ListView head={['Claim', 'Event', 'Attempts', 'Last error', 'Received', '']} empty="Nothing here.">
          {(view?.items ?? []).map((e) => (
            <tr key={e.id}>
              <td><Link to={`/claim/${encodeURIComponent(e.aggregateId)}`}>{e.aggregateId}</Link></td>
              <td>{e.eventType}<div><StatusChip value={e.status} /></div></td>
              <td className="num">{e.attemptCount}</td>
              <td>{e.lastError ?? '—'}</td>
              <td>{dateTime(e.receivedAt)}</td>
              <td>{e.status === 'DEAD_LETTER' && <Button disabled={busy === e.id} onClick={() => replay(e.id)}>{busy === e.id ? 'Sending…' : 'Replay'}</Button>}</td>
            </tr>
          ))}
        </ListView>
      </Panel>
      <p className="hint">A dead-lettered claim has already been flagged for an adjuster. Replaying runs the analysis again; it decides nothing.</p>
    </div>
  );
}
