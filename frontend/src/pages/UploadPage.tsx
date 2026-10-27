import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Button, MessageBar, Panel, dateTime } from '../components/ui';
import { angleLabel } from '../photoAngles';
import { apiClient } from '../services/apiClient';
import { PublicUpload } from '../types';
import { readPhoto } from '../utils/photos';

type Photo = { uri: string; name: string };

/**
 * Claimant-facing page behind the one-time link from the adjuster: shows which photos are needed and
 * uploads them to the existing claim. Shows no personal data and needs no login (the link is the key).
 */
export default function UploadPage() {
  const { token = '' } = useParams<{ token: string }>();
  const [info, setInfo] = useState<PublicUpload | null>(null);
  const [error, setError] = useState('');
  const [photos, setPhotos] = useState<Record<string, Photo>>({});
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(0);

  useEffect(() => {
    apiClient.getPublicUpload(token).then(setInfo).catch((err) => setError(err.message || 'This upload link is not valid.'));
  }, [token]);

  const onPhoto = (angle: string) => async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    try {
      const uri = await readPhoto(file);
      setPhotos((p) => ({ ...p, [angle]: { uri, name: file.name } }));
      setError('');
    } catch (err: any) {
      setError(err.message);
    }
  };

  const submit = async () => {
    setBusy(true);
    setError('');
    try {
      const angles = Object.keys(photos);
      const res = await apiClient.submitPublicUpload(token, {
        evidenceUris: angles.map((a) => photos[a].uri), evidenceAngles: angles,
      });
      setDone(res.photosReceived);
    } catch (err: any) {
      setError(err.message || 'The photos could not be sent');
    } finally {
      setBusy(false);
    }
  };

  const missing = info ? info.angles.filter((a) => !photos[a]) : [];
  return (
    <div className="claimant-page">
      <header className="claimant-header">
        <span className="brand-mark" aria-hidden="true">F</span>
        <span>Claim photo upload</span>
      </header>
      <main className="claimant-main">
        {error && <MessageBar tone="error" title={error} />}
        {!info && !error && <p className="muted">Loading…</p>}
        {info && done > 0 && (
          <MessageBar tone="success" title={`Thank you: ${done} photo${done > 1 ? 's' : ''} added to claim ${info.claimId}`}>
            Your claim handler has been notified. You can close this page.
          </MessageBar>
        )}
        {info && !done && info.status !== 'OPEN' && (
          <MessageBar tone="warning" title={`This link is ${info.status.toLowerCase()}`}>
            Upload links work once and expire after 7 days. Please contact your claim handler for a new link.
          </MessageBar>
        )}
        {info && !done && info.status === 'OPEN' && (
          <>
            <h1 className="claimant-title">Photos for claim {info.claimId}</h1>
            <p className="muted">Link valid until {dateTime(info.expiresAt)}. Photos are resized on your device before upload.</p>
            {info.message && <MessageBar tone="info" title="Message from your claim handler">{info.message}</MessageBar>}
            <Panel title={`Requested photos (${info.angles.length - missing.length} of ${info.angles.length})`}>
              <div className="angle-grid">
                {info.angles.map((a) => {
                  const p = photos[a];
                  return (
                    <div key={a} className={`angle-slot${p ? ' filled' : ''}`}>
                      <div className="angle-head"><strong>{angleLabel(a)}</strong></div>
                      {p ? <img src={p.uri} alt={`${angleLabel(a)} photo`} /> : <div className="angle-empty">No photo</div>}
                      <div className="angle-actions">
                        <label className="btn btn-secondary file-btn">
                          {p ? 'Replace' : 'Take or choose photo'}
                          <input type="file" accept="image/jpeg,image/png,image/webp,image/gif" capture="environment"
                            aria-label={`${angleLabel(a)} photo`} onChange={onPhoto(a)} />
                        </label>
                      </div>
                    </div>
                  );
                })}
              </div>
              <div className="option-row">
                <Button variant="primary" disabled={busy || Object.keys(photos).length === 0} onClick={submit}>
                  {busy ? 'Sending…' : `Send ${Object.keys(photos).length} photo${Object.keys(photos).length === 1 ? '' : 's'}`}
                </Button>
                {missing.length > 0 && Object.keys(photos).length > 0 && (
                  <span className="muted">Still missing: {missing.map(angleLabel).join(', ')}. The link works once.</span>
                )}
              </div>
            </Panel>
          </>
        )}
      </main>
    </div>
  );
}
