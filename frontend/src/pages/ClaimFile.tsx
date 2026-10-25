import React, { useEffect, useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';
import { InfoBar, PageTitle, Sidebar, Workspace } from '../components/Shell';
import {
  Button, DetailView, ListView, MessageBar, Panel, StatusChip, Toolbar, channelLabel, dateTime, humanize, money,
} from '../components/ui';
import { EvidenceGallery, PhotoSetCheck } from '../components/Evidence';
import { angleLabel, anglesFor } from '../photoAngles';
import { AssignInspection, InspectionHistory, InspectionReportForm, InspectionResult } from '../components/Inspection';
import { apiClient } from '../services/apiClient';
import { claimStore } from '../services/claimStore';
import { AdjusterDecision, AuditTrail, ClaimDocument, DashboardClaim, Inspection } from '../types';

const REVIEWABLE = new Set(['HUMAN_REVIEW', 'PENDING_ADVISORY_REVIEW', 'NEEDS_MORE_INFO', 'INVESTIGATION_REQUIRED']);

const SECTIONS = [
  ['summary', 'Summary'],
  ['advisory', 'AI Advisory'],
  ['loss', 'Loss Details'],
  ['compliance', 'Compliance'],
  ['financials', 'Financials'],
  ['documents', 'Documents'],
  ['inspection', 'Inspection'],
  ['decision', 'Decision'],
  ['history', 'History'],
] as const;
type Section = (typeof SECTIONS)[number][0];

const DECISIONS: [AdjusterDecision, string, 'primary' | 'secondary' | 'danger'][] = [
  ['APPROVED_BY_ADJUSTER', 'Approve', 'primary'],
  ['REJECTED_BY_ADJUSTER', 'Deny', 'danger'],
  ['NEEDS_MORE_INFO', 'Request Info', 'secondary'],
  ['INVESTIGATION_REQUIRED', 'Investigate', 'secondary'],
  ['CLOSED', 'Close', 'secondary'],
];

/** Loads a claim (stored intake result merged with the saved row) and shows it as a claim file. */
export default function ClaimFile() {
  const navigate = useNavigate();
  const { claimId = '', section = 'summary' } = useParams<{ claimId: string; section: string }>();
  const [claim, setClaim] = useState<DashboardClaim | null>(null);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const [docs, setDocs] = useState<ClaimDocument[]>([]);
  const [inspections, setInspections] = useState<Inspection[]>([]);

  useEffect(() => {
    let cancelled = false;
    apiClient.getInspections(claimId).then((i) => !cancelled && setInspections(i)).catch(() => !cancelled && setInspections([]));
    return () => {
      cancelled = true;
    };
  }, [claimId, reload]);

  useEffect(() => {
    let cancelled = false;
    setDocs([]);
    apiClient.getDocuments(claimId).then((d) => !cancelled && setDocs(d)).catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [claimId]);

  useEffect(() => {
    let cancelled = false;
    setError('');
    (async () => {
      const stored = claimStore.get(claimId);
      try {
        const record = await apiClient.getClaimStatus(claimId);
        const explanation = await apiClient.getExplanation(claimId).catch(() => null);
        if (cancelled) return;
        setClaim({
          ...(stored ?? {}),
          claimId: record.claimId,
          status: record.status,
          explanation: explanation ?? stored?.explanation ?? null,
          inputFlags: record.inputFlags ? record.inputFlags.split(',') : stored?.inputFlags ?? [],
          gateFlags: record.gateFlags ? record.gateFlags.split(',') : stored?.gateFlags ?? [],
          failureReason: record.failureReason,
          ccSyncStatus: record.ccSyncStatus,
          record,
        });
      } catch (err: any) {
        if (cancelled) return;
        if (stored) setClaim({ ...stored, inputFlags: stored.inputFlags ?? [], gateFlags: stored.gateFlags ?? [] });
        else setError(err.message || 'Unable to load claim');
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [claimId, reload]);

  if (!SECTIONS.some(([key]) => key === section)) {
    return <Navigate to={`/claim/${claimId}`} replace />;
  }
  if (error) {
    return (
      <div className="workspace-main solo">
        <MessageBar tone="error" title={`Claim ${claimId} could not be opened`}>{error}</MessageBar>
      </div>
    );
  }
  if (!claim || claim.claimId.toUpperCase() !== claimId.toUpperCase()) {
    return <div className="workspace-main solo"><p className="muted">Loading claim {claimId}…</p></div>;
  }

  const r = claim.record;
  const flagCount = (claim.inputFlags?.length ?? 0) + (claim.gateFlags?.length ?? 0);
  const sidebar = (
    <Sidebar
      actions={REVIEWABLE.has(claim.status) ? (
        <Button variant="primary" onClick={() => navigate(`/claim/${claim.claimId}/decision`)}>Record Decision</Button>
      ) : undefined}
      title={`Claim ${claim.claimId}`}
      items={SECTIONS.map(([key, label]) => ({
        to: key === 'summary' ? `/claim/${claim.claimId}` : `/claim/${claim.claimId}/${key}`,
        label,
        end: true,
        badge: key === 'summary' && flagCount ? flagCount
          : key === 'documents' && docs.length ? docs.length
          : key === 'inspection' && claim.status === 'INSPECTION_ASSIGNED' ? '!'
          : key === 'inspection' && inspections.length ? inspections.length
          : key === 'decision' && REVIEWABLE.has(claim.status) ? '!' : undefined,
      }))}
    />
  );

  return (
    <>
      <InfoBar
        items={[
          ['Claim', <strong key="c">{claim.claimId}</strong>],
          ['Policy', r?.policyNumber ?? claim.branchA?.coverage.policyNumber ?? '—'],
          ['Loss date', r?.incidentDate ?? '—'],
          ['State', r?.state ?? claim.branchB?.deadline.state ?? '—'],
          ['Status', <StatusChip key="s" value={claim.status} />],
          ['Severity', <StatusChip key="v" value={claim.explanation?.severityGate ?? r?.severityGate} />],
          ['ClaimCenter', <StatusChip key="cc" value={claim.ccSyncStatus} />],
        ]}
      />
      <Workspace sidebar={sidebar}>
        <SectionView section={section as Section} claim={claim} docs={docs} inspections={inspections}
          onChanged={() => setReload((n) => n + 1)} />
      </Workspace>
    </>
  );
}

function SectionView({ section, claim, docs, inspections, onChanged }: {
  section: Section; claim: DashboardClaim; docs: ClaimDocument[]; inspections: Inspection[]; onChanged: () => void;
}) {
  switch (section) {
    case 'summary': return <Summary claim={claim} docs={docs} onChanged={onChanged} />;
    case 'advisory': return <Advisory claim={claim} />;
    case 'loss': return <LossDetails claim={claim} docs={docs} />;
    case 'compliance': return <Compliance claim={claim} />;
    case 'financials': return <Financials claim={claim} />;
    case 'documents': return <Documents docs={docs} />;
    case 'inspection': return <InspectionSection claim={claim} inspections={inspections} onChanged={onChanged} />;
    case 'decision': return <Decision claim={claim} inspections={inspections} onChanged={onChanged} />;
    case 'history': return <History claimId={claim.claimId} />;
  }
}

function NoBranchDetails() {
  return (
    <MessageBar tone="info" title="Analysis details are not available here">
      The detailed AI results are kept by the browser that submitted the claim. The saved claim, the WHY panel and the
      audit history come from the server.
    </MessageBar>
  );
}

const FLAG_TEXT: Record<string, string> = {
  MISSING_EVIDENCE: 'No photos or documents were submitted.',
  CLAIMANT_NAME_MISMATCH: 'The claimant is not the named insured.',
  INCIDENT_OUTSIDE_POLICY_PERIOD: 'The loss date is outside the policy term.',
  INCIDENT_NEAR_POLICY_START: 'The loss happened within 30 days of the policy start.',
  INCOMPLETE_PHOTO_SET: 'Not every side of the vehicle (or the damage close-up) was photographed.',
  HIGH_CLAIM_FREQUENCY: 'The policy has a high recent claim frequency.',
  SEVERITY_ESTIMATE_MISMATCH: 'The damage severity does not match the estimated amount.',
  RESERVE_EXCEEDS_COVERAGE_LIMIT: 'The loss after the deductible is above the coverage limit; the reserve is capped.',
  CLAIMED_EXCEEDS_AI_ESTIMATE: 'The claimant asks for more than the photos support. Not approved automatically.',
  DAMAGE_TYPE_LINE_MISMATCH: "The damage does not fit the policy's line of business.",
  CONFIDENCE_PHOTO_QUALITY_MISMATCH: 'High AI confidence was reported for a poor photo.',
  DUPLICATE_SUBMISSION: 'A duplicate FNOL was submitted for this loss; verify it.',
};

/** The decision is saved here but did not reach ClaimCenter; lets the adjuster push it again. */
function SyncFailed({ claimId, onChanged }: { claimId: string; onChanged: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const retry = async () => {
    setBusy(true);
    setError('');
    try {
      const result = await apiClient.retryClaimCenterSync(claimId);
      if (result.ccSyncStatus !== 'SYNCED') setError('ClaimCenter still did not accept the update. Try again later.');
      onChanged();
    } catch (err: any) {
      setError(err.message || 'Retry failed');
    } finally {
      setBusy(false);
    }
  };
  return (
    <MessageBar tone="error" title="Decision not sent to ClaimCenter">
      Your decision is saved and audited here, but ClaimCenter did not accept the update.{' '}
      <Button onClick={retry} disabled={busy}>{busy ? 'Retrying…' : 'Retry sync'}</Button>
      {error && <div className="muted" style={{ marginTop: 6 }}>{error}</div>}
    </MessageBar>
  );
}

function Summary({ claim, docs, onChanged }: { claim: DashboardClaim; docs: ClaimDocument[]; onChanged: () => void }) {
  const navigate = useNavigate();
  const r = claim.record;
  const e = claim.explanation;
  const flags = [...(claim.inputFlags ?? []), ...(claim.gateFlags ?? [])];
  const escalate = e?.recommendedAction?.includes('ESCALATE');
  return (
    <>
      <PageTitle title="Summary" />
      {claim.failureReason && (
        <MessageBar tone="error" title="Intake failed and was saved for human attention">{claim.failureReason}</MessageBar>
      )}
      {claim.ccSyncStatus === 'FAILED' && <SyncFailed claimId={claim.claimId} onChanged={onChanged} />}
      {flags.length > 0 && (
        <MessageBar tone="warning" title={`${flags.length} review flag${flags.length > 1 ? 's' : ''}`}>
          <ul className="flag-list">
            {flags.map((f) => <li key={f}><strong>{humanize(f)}:</strong> {FLAG_TEXT[f] ?? ''}</li>)}
          </ul>
        </MessageBar>
      )}
      {e && (
        <MessageBar tone={escalate ? 'warning' : 'success'} title={`AI advisory: ${humanize(e.recommendedAction)}`}>
          {e.summary}.{' '}
          {REVIEWABLE.has(claim.status) && (
            <a href="#" onClick={(ev) => { ev.preventDefault(); navigate(`/claim/${claim.claimId}/decision`); }}>Record a decision</a>
          )}
        </MessageBar>
      )}
      <div className="grid-2">
        <Panel title="Basic Information">
          <DetailView rows={[
            ['Claim number', claim.claimId],
            ['Policy', r?.policyNumber],
            ['Loss date', r?.incidentDate],
            ['Loss state', r?.state],
            ['How reported', r?.channel ? channelLabel(r.channel) : null],
            ['Created', dateTime(r?.createdAt)],
          ]} />
        </Panel>
        <Panel title="Status">
          <DetailView rows={[
            ['Claim status', <StatusChip value={claim.status} />],
            ['Severity', <StatusChip value={e?.severityGate ?? r?.severityGate} />],
            ['AI decision', e ? humanize(e.decision) : null],
            ['Adjuster decision', r?.adjusterDecision ? <StatusChip value={r.adjusterDecision} /> : 'Pending'],
            ['Decided by', r?.adjusterId],
            ['ClaimCenter sync', <StatusChip value={claim.ccSyncStatus} />],
          ]} />
        </Panel>
      </div>
      <Panel title={`Evidence (${docs.length})`}>
        <EvidenceGallery docs={docs} />
      </Panel>
      <Panel title="Key Figures">
        <DetailView columns={2} rows={[
          ['Amount claimed', money(claim.branchA?.reserve.claimedAmount ?? r?.claimedAmount)],
          ['AI estimate (from photos)', money(claim.branchA?.damageAssessment.estimatedDamage ?? r?.estimatedDamage)],
          ['Deductible', money(claim.branchA?.reserve.deductible ?? r?.deductible)],
          ['Recommended reserve', money(claim.branchA?.reserve.recommendedReserve ?? r?.reserve)],
          ['Adjuster reserve', r?.adjusterReserve != null ? money(r.adjusterReserve) : null],
          ['AI confidence', e?.factors.find((f) => f.factor === 'AI Confidence')?.value],
          ['Subrogation score', claim.branchB ? `${claim.branchB.subrogation.score} / 100` : null],
          ['Acknowledge by', claim.branchB?.deadline.acknowledgeBy ?? claim.branchB?.deadline.deadline],
        ]} />
      </Panel>
      {(claim.notes ?? []).length > 0 && (
        <Panel title="Notes">
          <ul className="notes">{claim.notes!.map((n, i) => <li key={i}>{n}</li>)}</ul>
        </Panel>
      )}
    </>
  );
}

function Advisory({ claim }: { claim: DashboardClaim }) {
  const e = claim.explanation;
  if (!e) {
    return (<><PageTitle title="AI Advisory" /><MessageBar tone="info" title="No AI advisory for this claim">
      Failed intakes are not analysed; see the Summary for the reason.</MessageBar></>);
  }
  return (
    <>
      <PageTitle title="AI Advisory" subtitle="Why the AI recommends this route. The adjuster makes the decision." />
      <Panel title="Recommendation">
        <DetailView rows={[
          ['Decision', humanize(e.decision)],
          ['Recommended action', humanize(e.recommendedAction)],
          ['Severity gate', <StatusChip value={e.severityGate} />],
          ['Summary', e.summary],
        ]} />
      </Panel>
      <Panel title="Decision Factors" flush>
        <ListView head={['Factor', 'Value', 'Result', 'Rule']}>
          {e.factors.map((f) => (
            <tr key={f.factor}>
              <td><strong>{f.factor}</strong></td>
              <td>{f.value}</td>
              <td><StatusChip value={f.status} /></td>
              <td className="wrap">{f.detail}</td>
            </tr>
          ))}
        </ListView>
      </Panel>
    </>
  );
}

function LossDetails({ claim, docs }: { claim: DashboardClaim; docs: ClaimDocument[] }) {
  const a = claim.branchA;
  const required = anglesFor(claim.record?.policyNumber ?? claim.branchA?.coverage.policyNumber ?? '').filter((x) => x.required);
  const photos = (
    <Panel title={`Damage Photos (${docs.length})`}>
      <PhotoSetCheck docs={docs} required={required} />
      <EvidenceGallery docs={docs} empty="No photo was submitted with this claim." />
    </Panel>
  );
  if (!a) return (<><PageTitle title="Loss Details" />{photos}<NoBranchDetails /></>);
  const d = a.damageAssessment;
  const probs = Object.entries(d.damageTypeProbabilities ?? {}).sort((x, y) => y[1] - x[1]);
  return (
    <>
      <PageTitle title="Loss Details" subtitle="Branch A: what the AI saw in the photo, and coverage verification" />
      <div className="grid-2">
        {photos}
        <Panel title="Damage Assessment">
          <DetailView rows={[
            ['Damage type', humanize(d.damageType)],
            ['Severity', humanize(d.severity)],
            ['Estimated damage', money(d.estimatedDamage)],
            ['AI confidence', d.rawConfidence != null
              ? `${Number(d.confidence).toFixed(2)} (model said ${Number(d.rawConfidence).toFixed(2)})`
              : Number(d.confidence).toFixed(2)],
            ['Photo quality', d.photoQuality ? humanize(d.photoQuality) : null],
          ]} />
        </Panel>
        <Panel title="Coverage">
          <DetailView rows={[
            ['Covered', <StatusChip value={a.coverage.covered ? 'PASS' : 'FAIL'} />],
            ['Coverage', a.coverage.coverageType],
            ['Limit', money(a.coverage.coverageLimit)],
            ['Deductible', money(a.coverage.deductible)],
          ]} />
          {(a.coverage.reasoning ?? []).length > 0 && (
            <ul className="notes compact">{a.coverage.reasoning!.map((x) => <li key={x}>{x}</li>)}</ul>
          )}
        </Panel>
      </div>
      {probs.length > 0 && (
        <Panel title="Damage Type Probabilities" flush>
          <ListView head={['Damage type', 'Probability', '']}>
            {probs.map(([type, p]) => (
              <tr key={type}>
                <td>{humanize(type)}</td>
                <td className="num">{(Number(p) * 100).toFixed(0)}%</td>
                <td className="bar-cell"><span className="bar" style={{ width: `${Math.round(Number(p) * 100)}%` }} /></td>
              </tr>
            ))}
          </ListView>
        </Panel>
      )}
    </>
  );
}

function Compliance({ claim }: { claim: DashboardClaim }) {
  const b = claim.branchB;
  if (!b) return (<><PageTitle title="Compliance" /><NoBranchDetails /></>);
  return (
    <>
      <PageTitle title="Compliance" subtitle="Branch B: PII redaction, statutory deadlines, subrogation" />
      <div className="grid-2">
        <Panel title="Personal Information">
          <DetailView rows={[
            ['PII detected', b.pii.piiDetected ? b.pii.detectedTypes.join(', ') : 'None'],
            ['Redaction confidence', b.pii.redactionConfidence != null ? Number(b.pii.redactionConfidence).toFixed(2) : null],
          ]} />
          <div className="redacted"><div className="field-label">Redacted description</div><p>{b.pii.redactedDescription}</p></div>
        </Panel>
        <Panel title="Statutory Deadlines">
          <DetailView rows={[
            ['State', b.deadline.state],
            ['Acknowledge by', b.deadline.acknowledgeBy ?? b.deadline.deadline],
            ['Decide by', b.deadline.decisionBy],
            ['Urgency', <StatusChip value={b.deadline.urgencyFlag} />],
            ['Rule source', b.deadline.ruleSource],
          ]} />
          <p className="hint">{b.deadline.disclaimer}</p>
        </Panel>
      </div>
      <Panel title="Subrogation">
        <DetailView columns={2} rows={[
          ['Score', `${b.subrogation.score} / 100`],
          ['Recommended action', <StatusChip value={b.subrogation.recommendedAction} />],
          ...Object.entries(b.subrogation.factors).map(([k, v]) => [humanize(k.replace(/([A-Z])/g, '_$1')), `${v} pts`] as [string, string]),
        ]} />
        {(b.subrogation.complianceNotes ?? []).length > 0 && (
          <ul className="notes compact">{b.subrogation.complianceNotes!.map((n) => <li key={n}>{n}</li>)}</ul>
        )}
      </Panel>
    </>
  );
}

/** How the recommended reserve was reached: lower of claimed and AI estimate, less the deductible. */
function SettlementPanel({ claim }: { claim: DashboardClaim }) {
  const r = claim.record;
  const res = claim.branchA?.reserve;
  const claimed = res?.claimedAmount ?? r?.claimedAmount ?? null;
  const estimate = claim.branchA?.damageAssessment.estimatedDamage ?? r?.estimatedDamage;
  const deductible = res?.deductible ?? r?.deductible ?? null;
  const basis = res?.settlementBasis ?? (claimed != null && estimate != null ? Math.min(claimed, estimate) : estimate);
  const over = claimed != null && estimate != null && claimed > estimate;
  return (
    <Panel title="Reserve Calculation">
      <DetailView columns={2} rows={[
        ['Amount claimed', claimed != null ? money(claimed) : 'Not stated'],
        ['AI estimate (from photos)', money(estimate)],
        ['Basis (lower of the two)', money(basis)],
        ['Less deductible', deductible != null ? money(deductible) : null],
        ['Recommended reserve (amount owed)', <strong key="pay">{money(res?.recommendedReserve ?? r?.reserve)}</strong>],
        ['Rule', over ? 'Claimed more than the photos support: adjuster decides' : 'Within the AI estimate: claimed amount used'],
      ]} />
    </Panel>
  );
}

function Financials({ claim }: { claim: DashboardClaim }) {
  const r = claim.record;
  const reserve = claim.branchA?.reserve;
  return (
    <>
      <PageTitle title="Financials" subtitle="Reserves recommended at FNOL. Payments are made later in ClaimCenter, never by the AI." />
      <SettlementPanel claim={claim} />
      <Panel title="Reserve Lines" flush>
        <ListView head={['Line', 'Cost type', 'Amount claimed', 'AI estimate', 'AI recommended', 'Adjuster', 'Confidence']}>
          <tr>
            <td>{claim.branchA?.coverage.coverageType ?? 'Claim'}</td>
            <td>Claim cost (net of deductible)</td>
            <td className="num">{money(reserve?.claimedAmount ?? r?.claimedAmount)}</td>
            <td className="num">{money(claim.branchA?.damageAssessment.estimatedDamage ?? r?.estimatedDamage)}</td>
            <td className="num">{money(reserve?.recommendedReserve ?? r?.reserve)}</td>
            <td className="num">{r?.adjusterReserve != null ? money(r.adjusterReserve) : <span className="muted">—</span>}</td>
            <td className="num">{reserve?.confidence != null ? Number(reserve.confidence).toFixed(2) : '—'}</td>
          </tr>
        </ListView>
      </Panel>
      {(reserve?.confidenceBasis || r?.overrideReason) && (
        <Panel title="Basis">
          <DetailView rows={[
            ['Reserve confidence basis', reserve?.confidenceBasis],
            ['Adjuster override reason', r?.overrideReason],
          ]} />
        </Panel>
      )}
    </>
  );
}

function Documents({ docs }: { docs: ClaimDocument[] }) {
  return (
    <>
      <PageTitle title="Documents" subtitle="Evidence submitted with the claim. Each file's SHA-256 is recorded in the audit history." />
      <Panel title={`Photos (${docs.filter((d) => d.viewable).length})`}>
        <EvidenceGallery docs={docs} />
      </Panel>
      <Panel title="All Documents" flush>
        <ListView head={['View', 'Name', 'Source', 'Type', 'Size', 'Received', 'SHA-256']} empty="No documents">
          {docs.map((d) => (
            <tr key={d.documentId}>
              <td>{angleLabel(d.angle)}</td>
              <td>{d.name}</td>
              <td>{d.source === 'UPLOAD' ? 'Uploaded' : d.source === 'MOCK' ? 'Demo reference' : 'Linked URL'}</td>
              <td>{d.contentType ?? '—'}</td>
              <td className="num">{d.sizeBytes != null ? `${(d.sizeBytes / 1024).toFixed(1)} KB` : '—'}</td>
              <td>{dateTime(d.uploadedAt)}</td>
              <td className="mono" title={d.sha256 ?? ''}>{d.sha256 ? `${d.sha256.slice(0, 16)}…` : '—'}</td>
            </tr>
          ))}
        </ListView>
      </Panel>
    </>
  );
}

/** Inspection: assign an inspector while the claim awaits a decision; the open assignment's report form; history. */
function InspectionSection({ claim, inspections, onChanged }: {
  claim: DashboardClaim; inspections: Inspection[]; onChanged: () => void;
}) {
  const open = inspections.find((i) => i.status === 'ASSIGNED');
  const canAssign = REVIEWABLE.has(claim.status) && !claim.claimId.startsWith('REJ-');
  return (
    <>
      <PageTitle title="Inspection" subtitle="An inspector confirms which damage this accident caused and what it costs to repair." />
      {open && <InspectionReportForm claimId={claim.claimId} inspection={open} onChanged={onChanged} />}
      {!open && canAssign && <AssignInspection claim={claim} onChanged={onChanged} />}
      {!open && !canAssign && (
        <MessageBar tone="info" title="An inspector can be assigned only while the claim awaits a decision" />
      )}
      <InspectionHistory inspections={inspections} />
    </>
  );
}

function Decision({ claim, inspections, onChanged }: {
  claim: DashboardClaim; inspections: Inspection[]; onChanged: () => void;
}) {
  const navigate = useNavigate();
  const latest = [...inspections].reverse().find((i) => i.status === 'COMPLETED');
  const open = inspections.find((i) => i.status === 'ASSIGNED');
  const claimed = claim.branchA?.reserve.claimedAmount ?? claim.record?.claimedAmount ?? null;
  const estimate = claim.branchA?.damageAssessment.estimatedDamage ?? claim.record?.estimatedDamage ?? null;
  const deductible = claim.branchA?.reserve.deductible ?? claim.record?.deductible ?? null;
  const aiPayment = claim.branchA?.reserve.recommendedReserve ?? claim.record?.reserve ?? null;
  // The inspector's figure, once reported, replaces the photo-based one as the recommendation.
  const recommended = latest?.recommendedPayment ?? aiPayment;
  const recommendedBy = latest?.recommendedPayment != null ? `inspection by ${latest.inspectorName}` : 'AI (photos)';

  const [adjusterId, setAdjusterId] = useState('ADJ-001');
  const [notes, setNotes] = useState('');
  const [finalAmount, setFinalAmount] = useState(recommended != null ? String(recommended) : '');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');

  useEffect(() => {
    setFinalAmount(recommended != null ? String(recommended) : '');
    setReason('');
  }, [recommended]);

  const reviewable = REVIEWABLE.has(claim.status);
  const final = finalAmount.trim() === '' ? null : Number(finalAmount);
  const invalidAmount = final != null && !(final >= 0);
  const changed = final != null && recommended != null && final !== Number(recommended);
  const reasonMissing = changed && !reason.trim();
  const claimedOverEstimate = (claim.gateFlags ?? []).includes('CLAIMED_EXCEEDS_AI_ESTIMATE')
    || (claimed != null && estimate != null && claimed > estimate);
  const blocked = busy || !reviewable || !adjusterId.trim() || invalidAmount || reasonMissing;

  /**
   * The final amount is sent as the adjuster's reserve whenever it differs from the AI's figure. Equal to the
   * inspected recommendation, the inspection is the recorded reason; otherwise the adjuster's own reason.
   */
  const submit = async (decision: AdjusterDecision, quickNote?: string) => {
    setBusy(true);
    setError('');
    setSaved('');
    try {
      const differsFromAi = final != null && aiPayment != null && final !== Number(aiPayment);
      const override = differsFromAi ? {
        adjustedReserve: final!,
        overrideReason: reason.trim()
          || `Inspection by ${latest?.inspectorName}: accident-related damage ${money(latest?.accidentRelatedAmount)}`,
      } : {};
      const text = notes.trim() || quickNote || '';
      const res = await apiClient.submitAdjusterReview(claim.claimId, { decision, adjusterId, notes: text, ...override });
      claimStore.updateStatus(claim.claimId, res.status);
      setSaved(`${humanize(res.adjusterDecision)} saved at ${new Date(res.reviewedAt).toLocaleString()}`);
      onChanged();
    } catch (err: any) {
      setError(err.message || 'The decision could not be saved');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Toolbar>
        {DECISIONS.map(([d, label, variant]) => (
          <Button key={d} variant={variant} disabled={blocked} onClick={() => submit(d)}>
            {d === 'APPROVED_BY_ADJUSTER' && final != null ? `Approve (reserve ${money(final)})` : label}
          </Button>
        ))}
      </Toolbar>
      <PageTitle title="Decision" subtitle="Human Review Point 3: the adjuster has final authority, including the reserve sent to ClaimCenter." />
      {!reviewable && (
        <MessageBar tone="info" title={`This claim is ${humanize(claim.status).toLowerCase()}`}>
          {open ? 'It is waiting for the inspector. ' : ''}A decision can be recorded only while the claim awaits one.
        </MessageBar>
      )}
      {error && <MessageBar tone="error" title="Not saved">{error}</MessageBar>}
      {saved && <MessageBar tone="success" title={saved} />}
      {open && (
        <MessageBar tone="info" title={`Waiting for the inspection by ${open.inspectorName} (due ${open.dueBy})`}>
          <a href="#" onClick={(ev) => { ev.preventDefault(); navigate(`/claim/${claim.claimId}/inspection`); }}>Open the inspection</a>
        </MessageBar>
      )}
      {reviewable && claimedOverEstimate && !latest && (
        <MessageBar tone="warning" title={`Claimant asks ${money(claimed)}; the photos support ${money(estimate)}`}>
          Not approved automatically. Verify the extra amount before reserving it:
          <div className="option-row">
            <Button disabled={busy || !adjusterId.trim()} onClick={() => submit('NEEDS_MORE_INFO',
              `Requested more photos / a repair quote: claimed ${money(claimed)}, photos support ${money(estimate)}.`)}>
              Request more photos</Button>
            <Button disabled={busy} onClick={() => navigate(`/claim/${claim.claimId}/inspection`)}>Assign inspector</Button>
            <Button disabled={busy || !adjusterId.trim()} onClick={() => submit('INVESTIGATION_REQUIRED',
              `Sent to investigation: claimed ${money(claimed)}, photos support ${money(estimate)}.`)}>
              Send to investigation</Button>
          </div>
        </MessageBar>
      )}
      {latest && (
        <Panel title="Inspection Result">
          <InspectionResult inspection={latest} />
        </Panel>
      )}
      <Panel title="Reserve Recommendation">
        <DetailView columns={2} rows={[
          ['Amount claimed', claimed != null ? money(claimed) : 'Not stated'],
          ['AI estimate (photos)', money(estimate)],
          ['Inspected damage', latest ? money(latest.accidentRelatedAmount) : '—'],
          ['Deductible', money(deductible)],
          ['Recommended reserve', <span key="r"><strong>{money(recommended)}</strong> <span className="muted">({recommendedBy})</span></span>],
        ]} />
        <div className="form-grid">
          <label className="field">
            <span className="field-label">Final reserve ($)<span className="required"> *</span></span>
            <input type="number" min="0" step="1" value={finalAmount} disabled={!reviewable}
              onChange={(e) => setFinalAmount(e.target.value)} />
          </label>
          <label className="field wide">
            <span className="field-label">Reason for a different reserve{changed && <span className="required"> *</span>}</span>
            <input value={reason} onChange={(e) => setReason(e.target.value)} disabled={!reviewable || !changed}
              placeholder={changed ? `Why ${money(final)} instead of ${money(recommended)} (e.g. body shop invoice)` : 'Only needed if you change the amount'} />
          </label>
        </div>
        {changed && (
          <MessageBar tone={reasonMissing ? 'warning' : 'info'}
            title={`You are changing the reserve from ${money(recommended)} to ${money(final)}`}>
            {reasonMissing ? 'Give a reason; it is recorded in the audit history.' : 'The reason is recorded in the audit history.'}
            {claimed != null && final != null && final > claimed && ' This is more than the claimant asked for.'}
          </MessageBar>
        )}
      </Panel>
      <Panel title="Adjuster Review">
        <div className="form-grid">
          <label className="field">
            <span className="field-label">Adjuster ID<span className="required"> *</span></span>
            <input value={adjusterId} onChange={(e) => setAdjusterId(e.target.value)} disabled={!reviewable} />
          </label>
          <label className="field wide">
            <span className="field-label">Decision notes</span>
            <textarea rows={4} value={notes} onChange={(e) => setNotes(e.target.value)} disabled={!reviewable}
              placeholder="Rationale, documents requested, or investigation instructions" />
          </label>
        </div>
      </Panel>
      {claim.record?.adjusterDecision && (
        <Panel title="Last Decision">
          <DetailView rows={[
            ['Decision', <StatusChip value={claim.record.adjusterDecision} />],
            ['Adjuster', claim.record.adjusterId],
            ['Final reserve', claim.record.adjusterReserve != null
              ? `${money(claim.record.adjusterReserve)} (AI recommended ${money(claim.record.reserve)})` : money(claim.record.reserve)],
            ['Reason', claim.record.overrideReason],
            ['Notes', claim.record.adjusterNotes],
            ['Reviewed', dateTime(claim.record.reviewedAt)],
          ]} />
        </Panel>
      )}
    </>
  );
}

function History({ claimId }: { claimId: string }) {
  const [trail, setTrail] = useState<AuditTrail | null>(null);
  const [error, setError] = useState('');
  const [downloading, setDownloading] = useState(false);

  useEffect(() => {
    setTrail(null);
    apiClient.getAuditTrail(claimId).then(setTrail).catch((err) => setError(err.message || 'Unable to load history'));
  }, [claimId]);

  const pdf = async () => {
    setDownloading(true);
    try {
      await apiClient.downloadAuditPdf(claimId);
    } catch (err: any) {
      setError(err.message || 'PDF export failed');
    } finally {
      setDownloading(false);
    }
  };

  return (
    <>
      <Toolbar>
        <Button variant="primary" disabled={!trail || downloading} onClick={pdf}>
          {downloading ? 'Exporting…' : 'Export Audit PDF'}
        </Button>
      </Toolbar>
      <PageTitle title="History" subtitle="Every AI step, failure and human decision, SHA-256 hash-chained." />
      {error && <MessageBar tone="error" title="History unavailable">{error}</MessageBar>}
      {trail && (
        <MessageBar tone={trail.chainValid ? 'success' : 'error'}
          title={trail.chainValid ? 'Audit chain verified' : 'Audit chain broken'}>
          {trail.chainValid
            ? 'Every link recomputes from the stored hashes.'
            : 'Stored hashes do not recompute: a record was changed, removed or written before chaining.'}
        </MessageBar>
      )}
      <Panel title={`Events (${trail ? trail.records.length : '…'})`} flush>
        <ListView head={['#', 'Event', 'Actor', 'Result', 'When', 'Input hash', 'Chain hash']} empty={trail ? 'No events' : 'Loading…'}>
          {trail?.records.map((ev) => (
            <tr key={ev.eventId ?? `${ev.seq}-${ev.agentName}`}>
              <td className="num">{ev.seq ?? '—'}</td>
              <td>{ev.agentName.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/^Pii /, 'PII ')}</td>
              <td>{ev.actor ?? '—'}</td>
              <td><StatusChip value={ev.status} /></td>
              <td>{dateTime(ev.timestamp)}</td>
              <td className="mono" title={ev.inputHash}>{ev.inputHash?.slice(0, 12)}…</td>
              <td className="mono" title={ev.chainHash ?? ''}>{ev.chainHash ? `${ev.chainHash.slice(0, 12)}…` : '—'}</td>
            </tr>
          ))}
        </ListView>
      </Panel>
    </>
  );
}
