import React, { useState } from 'react';
import { Button, MessageBar, Panel } from '../components/ui';
import { apiClient } from '../services/apiClient';
import { Session, session } from '../services/session';

/** Sign-in: each person has their own account, and what they do is recorded under their name. */
export default function Login({ onSignedIn }: { onSignedIn: (s: Session) => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const s = await apiClient.login(username.trim(), password);
      session.save(s);
      onSignedIn(s);
    } catch (err: any) {
      setError(err.message || 'Could not sign in');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="app">
      <main className="workspace-main solo" style={{ maxWidth: 420, margin: '10vh auto 0' }}>
        <Panel title="FNOL Claims Workspace: sign in">
          {error && <MessageBar tone="error" title={error} />}
          <form onSubmit={submit}>
            <label className="field">
              <span className="field-label">User ID</span>
              <input name="username" autoComplete="username" autoFocus value={username}
                onChange={(e) => setUsername(e.target.value)} />
            </label>
            <label className="field" style={{ marginTop: 10 }}>
              <span className="field-label">Password</span>
              <input name="password" type="password" autoComplete="current-password" value={password}
                onChange={(e) => setPassword(e.target.value)} />
            </label>
            <div style={{ marginTop: 14 }}>
              <Button variant="primary" type="submit" disabled={busy || !username.trim() || !password}>
                {busy ? 'Signing in…' : 'Sign in'}
              </Button>
            </div>
          </form>
        </Panel>
      </main>
    </div>
  );
}
