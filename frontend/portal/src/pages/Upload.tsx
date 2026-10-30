import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { angleLabel } from '../../../src/photoAngles';
import { readPhoto } from '../../../src/utils/photos';
import { PublicUpload, portalApi } from '../portalApi';
import { Alert, Card, longDate } from '../ui';

type Photo = { uri: string; name: string };

/**
 * The page behind the one-time link an adjuster sends: shows which photos are needed and adds them to the claim.
 * No login and no personal data: the link is the key, works once and expires after 7 days.
 */
export default function Upload() {
  const { token = '' } = useParams<{ token: string }>();
  const [info, setInfo] = useState<PublicUpload | null>(null);
  const [error, setError] = useState('');
  const [photos, setPhotos] = useState<Record<string, Photo>>({});
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(0);

  useEffect(() => {
    portalApi.getUpload(token).then(setInfo).catch((err) => setError(err.message || 'This upload link is not valid.'));
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

  const send = async () => {
    setBusy(true);
    setError('');
    try {
      const angles = Object.keys(photos);
      const res = await portalApi.sendUpload(token, { evidenceUris: angles.map((a) => photos[a].uri), evidenceAngles: angles });
      setDone(res.photosReceived);
    } catch (err: any) {
      setError(err.message || 'The photos could not be sent');
    } finally {
      setBusy(false);
    }
  };

  const count = Object.keys(photos).length;
  const missing = info ? info.angles.filter((a) => !photos[a]) : [];
  return (
    <div className="p-narrow">
      <h1 className="p-title">Add photos to your claim</h1>
      {error && <Alert tone="error" title={error} />}
      {!info && !error && <p className="p-muted">Loading…</p>}
      {info && done > 0 && (
        <Alert tone="success" title={`Thank you: ${done} photo${done > 1 ? 's' : ''} added to claim ${info.claimId}`}>
          Your adjuster has been told and your claim continues. You can close this page, or track the claim with your
          claim number and policy number.
        </Alert>
      )}
      {info && !done && info.status !== 'OPEN' && (
        <Alert tone="warning" title={`This link has ${info.status === 'USED' ? 'already been used' : info.status === 'EXPIRED' ? 'expired' : 'been cancelled'}`}>
          Upload links work once and expire after 7 days. Please contact your adjuster for a new link.
        </Alert>
      )}
      {info && !done && info.status === 'OPEN' && (
        <Card title={`Photos for claim ${info.claimId} (${info.angles.length - missing.length} of ${info.angles.length})`}>
          <p className="p-muted">This link works until {longDate(info.expiresAt)}. Photos are made smaller on your
            device before they are sent.</p>
          {info.message && <Alert tone="info" title="Message from your adjuster">{info.message}</Alert>}
          <div className="p-photo-grid">
            {info.angles.map((a) => {
              const p = photos[a];
              return (
                <div key={a} className={`p-photo${p ? ' filled' : ''}`}>
                  <div className="p-photo-head"><strong>{angleLabel(a)}</strong></div>
                  {p ? <img src={p.uri} alt={`${angleLabel(a)} photo`} /> : <div className="p-photo-empty" aria-hidden="true">+</div>}
                  <div className="p-photo-actions">
                    <label className="p-btn p-btn-secondary p-file">
                      {p ? 'Replace' : 'Take or choose photo'}
                      <input type="file" accept="image/jpeg,image/png,image/webp,image/gif" capture="environment"
                        aria-label={`${angleLabel(a)} photo`} onChange={onPhoto(a)} />
                    </label>
                  </div>
                </div>
              );
            })}
          </div>
          <div className="p-actions">
            <button type="button" className="p-btn p-btn-primary" disabled={busy || count === 0} onClick={send}>
              {busy ? 'Sending…' : `Send ${count} photo${count === 1 ? '' : 's'}`}
            </button>
          </div>
          {missing.length > 0 && count > 0 && (
            <p className="p-muted">Still missing: {missing.map(angleLabel).join(', ')}. The link works once, so add
              every photo you can before sending.</p>
          )}
        </Card>
      )}
    </div>
  );
}
