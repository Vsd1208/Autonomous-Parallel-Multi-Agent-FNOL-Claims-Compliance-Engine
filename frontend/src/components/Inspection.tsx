import React, { useEffect, useState } from 'react';
import { apiClient } from '../services/apiClient';
import { DashboardClaim, Inspection, Inspector } from '../types';
import { Button, DetailView, ListView, MessageBar, Panel, StatusChip, dateTime, humanize, money } from './ui';

const TYPES: [string, string][] = [
  ['FIELD', 'Field inspection (on site)'],
  ['DESK', 'Desk review (photos and documents)'],
  ['SHOP', 'Body shop estimate'],
];

const RECOMMENDATIONS: [string, string][] = [
  ['APPROVE', 'Approve as claimed'],
  ['REDUCE', 'Reserve at the inspected amount'],
  ['DENY', 'Deny (not from this accident)'],
  ['INVESTIGATE', 'Refer to investigation'],
];

/** Assign form: who inspects, how, and why. Moves the claim to INSPECTION_ASSIGNED. */
export function AssignInspection({ claim, onChanged }: { claim: DashboardClaim; onChanged: () => void }) {
  const [inspectors, setInspectors] = useState<Inspector[]>([]);
  const [inspectorId, setInspectorId] = useState('');
  const [type, setType] = useState('FIELD');
  const [reason, setReason] = useState('');
  const [adjusterId, setAdjusterId] = useState('ADJ-001');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    apiClient.getInspectors().then((list) => {
      setInspectors(list);
      setInspectorId((id) => id || list[0]?.inspectorId || '');
    }).catch((err) => setError(err.message || 'Inspectors could not be loaded'));
  }, []);

  useEffect(() => {
    const r = claim.record;
    const claimed = claim.branchA?.reserve.claimedAmount ?? r?.claimedAmount;
    const estimate = claim.branchA?.damageAssessment.estimatedDamage ?? r?.estimatedDamage;
    if (claimed != null && estimate != null && claimed > estimate) {
      setReason(`Claimed ${money(claimed)} but the photos support ${money(estimate)}; confirm the accident-related damage.`);
    }
  }, [claim]);

  const assign = async () => {
    setBusy(true);
    setError('');
    try {
      await apiClient.assignInspection(claim.claimId, { inspectorId, inspectionType: type, reason: reason.trim(), assignedBy: adjusterId.trim() });
      onChanged();
    } catch (err: any) {
      setError(err.message || 'The inspector could not be assigned');
    } finally {
      setBusy(false);
    }
  };

  const ready = inspectorId && reason.trim() && adjusterId.trim();
  return (
    <Panel title="Assign an Inspector">
      {error && <MessageBar tone="error" title="Not assigned">{error}</MessageBar>}
      <div className="form-grid">
        <label className="field">
          <span className="field-label">Inspector<span className="required"> *</span></span>
          <select value={inspectorId} onChange={(e) => setInspectorId(e.target.value)}>
            {inspectors.map((i) => (
              <option key={i.inspectorId} value={i.inspectorId}>{i.name} · {i.specialty} · {i.region}</option>
            ))}
          </select>
        </label>
        <label className="field">
          <span className="field-label">Inspection type<span className="required"> *</span></span>
          <select value={type} onChange={(e) => setType(e.target.value)}>
            {TYPES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
        </label>
        <label className="field">
          <span className="field-label">Assigned by<span className="required"> *</span></span>
          <input value={adjusterId} onChange={(e) => setAdjusterId(e.target.value)} />
        </label>
        <label className="field wide">
          <span className="field-label">What should the inspector confirm?<span className="required"> *</span></span>
          <textarea rows={3} value={reason} onChange={(e) => setReason(e.target.value)}
            placeholder="e.g. which damage is from this accident, repair cost, signs of earlier damage" />
        </label>
      </div>
      <div className="option-row">
        <Button variant="primary" disabled={busy || !ready} onClick={assign}>{busy ? 'Assigning…' : 'Assign inspector'}</Button>
      </div>
    </Panel>
  );
}

/** The inspector's report on an open assignment (prototype: entered in the same workspace). */
export function InspectionReportForm({ claimId, inspection, onChanged }: {
  claimId: string; inspection: Inspection; onChanged: () => void;
}) {
  const [amount, setAmount] = useState('');
  const [prior, setPrior] = useState(false);
  const [findings, setFindings] = useState('');
  const [recommendation, setRecommendation] = useState('REDUCE');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async () => {
    setBusy(true);
    setError('');
    try {
      await apiClient.reportInspection(claimId, inspection.inspectionId, {
        inspectorId: inspection.inspectorId, accidentRelatedAmount: Number(amount), priorDamageFound: prior,
        findings: findings.trim(), recommendation,
      });
      onChanged();
    } catch (err: any) {
      setError(err.message || 'The report could not be saved');
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    setBusy(true);
    setError('');
    try {
      await apiClient.cancelInspection(claimId, inspection.inspectionId, 'ADJ-001');
      onChanged();
    } catch (err: any) {
      setError(err.message || 'The inspection could not be cancelled');
    } finally {
      setBusy(false);
    }
  };

  const ready = amount.trim() && Number(amount) >= 0 && findings.trim();
  return (
    <Panel title={`Inspection Report — ${inspection.inspectorName}`}>
      <MessageBar tone="info" title={`Waiting for ${inspection.inspectorName}, due ${inspection.dueBy}`}>
        {inspection.reason}
      </MessageBar>
      {error && <MessageBar tone="error" title="Not saved">{error}</MessageBar>}
      <div className="form-grid">
        <label className="field">
          <span className="field-label">Repair cost of this accident's damage ($)<span className="required"> *</span></span>
          <input type="number" min="0" step="1" value={amount} onChange={(e) => setAmount(e.target.value)}
            placeholder="Exclude damage from earlier events" />
        </label>
        <label className="field">
          <span className="field-label">Recommendation<span className="required"> *</span></span>
          <select value={recommendation} onChange={(e) => setRecommendation(e.target.value)}>
            {RECOMMENDATIONS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
        </label>
        <label className="field checkbox">
          <input type="checkbox" checked={prior} onChange={(e) => setPrior(e.target.checked)} />
          <span>Earlier (pre-existing) damage found</span>
        </label>
        <label className="field wide">
          <span className="field-label">Findings<span className="required"> *</span></span>
          <textarea rows={4} value={findings} onChange={(e) => setFindings(e.target.value)}
            placeholder="Which parts were damaged in this accident, which damage is older (rust, dirt, earlier repairs), how the cost was reached" />
        </label>
      </div>
      <div className="option-row">
        <Button variant="primary" disabled={busy || !ready} onClick={submit}>{busy ? 'Saving…' : 'Submit report'}</Button>
        <Button disabled={busy} onClick={cancel}>Cancel assignment</Button>
      </div>
    </Panel>
  );
}

/** A completed report, as the adjuster sees it. */
export function InspectionResult({ inspection }: { inspection: Inspection }) {
  return (
    <DetailView columns={2} rows={[
      ['Inspector', inspection.inspectorName],
      ['Type', humanize(inspection.inspectionType)],
      ['Accident-related damage', money(inspection.accidentRelatedAmount)],
      ['Earlier damage found', inspection.priorDamageFound ? 'Yes (excluded)' : 'No'],
      ['Recommendation', inspection.recommendation ? humanize(inspection.recommendation) : null],
      ['Recommended reserve', <strong key="p">{money(inspection.recommendedPayment)}</strong>],
      ['Findings', inspection.findings],
      ['Reported', dateTime(inspection.completedAt)],
    ]} />
  );
}

/** All inspections on the claim, newest decisions first in the table. */
export function InspectionHistory({ inspections }: { inspections: Inspection[] }) {
  return (
    <Panel title={`Inspections (${inspections.length})`} flush>
      <ListView head={['Inspector', 'Type', 'Status', 'Assigned', 'Due', 'Accident damage', 'Reserve']} empty="No inspections">
        {[...inspections].reverse().map((i) => (
          <tr key={i.inspectionId}>
            <td>{i.inspectorName}</td>
            <td>{humanize(i.inspectionType)}</td>
            <td><StatusChip value={i.status} /></td>
            <td>{dateTime(i.assignedAt)} by {i.assignedBy}</td>
            <td>{i.dueBy}</td>
            <td className="num">{money(i.accidentRelatedAmount)}</td>
            <td className="num">{money(i.recommendedPayment)}</td>
          </tr>
        ))}
      </ListView>
    </Panel>
  );
}
