import React, { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import ClaimStatus from '../ClaimStatus';
import { PortalClaim, PortalError, portalApi } from '../portalApi';
import { Alert, Card, Field } from '../ui';

/** Claim status for the policyholder: claim number and policy number together. */
export default function Track() {
  const prefill = (useLocation().state ?? {}) as { claimId?: string; policyNumber?: string };
  const [claimId, setClaimId] = useState(prefill.claimId ?? '');
  const [policyNumber, setPolicyNumber] = useState(prefill.policyNumber ?? '');
  const [claim, setClaim] = useState<PortalClaim | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const look = async (e?: React.FormEvent) => {
    e?.preventDefault();
    if (!claimId.trim() || !policyNumber.trim()) {
      setError('Please enter both your claim number and your policy number.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      setClaim(await portalApi.track(claimId, policyNumber));
    } catch (err: any) {
      setClaim(null);
      setError(err instanceof PortalError && err.status === 404
        ? 'We could not find a claim with that claim number and policy number. Please check both and try again.'
        : 'Your claim could not be loaded just now. Please try again in a few minutes.');
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    if (prefill.claimId && prefill.policyNumber) look();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="p-narrow">
      <h1 className="p-title">Track a claim</h1>
      <Card>
        <form className="p-grid" onSubmit={look}>
          <Field label="Claim number" required hint="From your confirmation, e.g. CLM-2026-000033">
            <input value={claimId} onChange={(e) => setClaimId(e.target.value)} autoComplete="off" />
          </Field>
          <Field label="Policy number" required>
            <input value={policyNumber} onChange={(e) => setPolicyNumber(e.target.value)} autoComplete="off" />
          </Field>
          <div className="p-actions wide">
            <button type="submit" className="p-btn p-btn-primary" disabled={busy}>{busy ? 'Looking…' : 'Show my claim'}</button>
          </div>
        </form>
      </Card>
      {error && <Alert tone="warning" title={error} />}
      {claim && <ClaimStatus claim={claim} />}
    </div>
  );
}
