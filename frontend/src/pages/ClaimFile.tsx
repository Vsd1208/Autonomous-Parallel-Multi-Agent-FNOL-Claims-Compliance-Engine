import React, { useEffect, useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';
import { InfoBar, PageTitle, Sidebar, Workspace } from '../components/Shell';
import {
  Button, DetailView, ListView, MessageBar, Panel, StatusChip, Toolbar, channelLabel, dateTime, humanize, money,
} from '../components/ui';
import { EvidenceGallery, PhotoSetCheck } from '../components/Evidence';
import { PhotoRequestList, RequestPhotos } from '../components/PhotoRequests';
import { angleLabel, anglesFor } from '../photoAngles';
import { AssignInspection, InspectionHistory, InspectionReportForm, InspectionResult } from '../components/Inspection';
import { apiClient } from '../services/apiClient';
import { claimStore } from '../services/claimStore';
import { AdjusterDecision, AuditTrail, CcClaim, ClaimDocument, DashboardClaim, FraudAnomaly, Inspection, SecondOpinion } from '../types';

const REVIEWABLE = new Set(['HUMAN_REVIEW', 'PENDING_ADVISORY_REVIEW', 'NEEDS_MORE_INFO', 'INVESTIGATION_REQUIRED']);

const SECTIONS = [
  ['summary', 'Summary'],
  ['advisory', 'AI Advisory'],
  ['loss', 'Loss Details'],
  ['compliance', 'Compliance'],
  ['financials', 'Financials'],
  ['documents', 'Documents'],
  ['inspection', 'Inspection'],
  ['claimcenter', 'In ClaimCenter'],
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
        // The analysis is saved on the server, so any adjuster's browser can show it; the copy this
        // browser kept at submission is only a fallback for claims filed before that.
        const saved = await apiClient.getAnalysis(claimId).catch(() => null);
        if (cancelled) return;
        const analysis = saved
          ? { branchA: saved.branchA, branchB: saved.branchB, notes: saved.notes, policyContext: saved.policyContext }
          : {};
        setClaim({
          ...(stored ?? {}),
          ...analysis,
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

  const bar = (
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
  );

  return (
    <Workspace sidebar={sidebar} bar={bar}>
      <SectionView section={section as Section} claim={claim} docs={docs} inspections={inspections}
        reload={reload} onChanged={() => setReload((n) => n + 1)} />
    </Workspace>
  );
}

function SectionView({ section, claim, docs, inspections, reload, onChanged }: {
  section: Section; claim: DashboardClaim; docs: ClaimDocument[]; inspections: Inspection[]; reload: number;
  onChanged: () => void;
}) {
  switch (section) {
    case 'summary': return <Summary claim={claim} docs={docs} onChanged={onChanged} />;
    case 'advisory': return <Advisory claim={claim} />;
    case 'loss': return <LossDetails claim={claim} docs={docs} />;
    case 'compliance': return <Compliance claim={claim} />;
    case 'financials': return <Financials claim={claim} />;
    case 'documents': return <Documents claimId={claim.claimId} docs={docs} reload={reload} onChanged={onChanged} />;
    case 'inspection': return <InspectionSection claim={claim} inspections={inspections} onChanged={onChanged} />;
    case 'decision': return <Decision claim={claim} inspections={inspections} docs={docs} reload={reload} onChanged={onChanged} />;
    case 'history': return <History claimId={claim.claimId} />;
    case 'claimcenter': return <InClaimCenter claimId={claim.claimId} reload={reload} />;
  }
}

function NoBranchDetails() {
  return (
    <MessageBar tone="info" title="Analysis details were not saved for this claim">
      This claim was filed before the full AI analysis was stored on the server. The saved claim, the WHY panel,
      the photos and the audit history are still available.
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
  ATTORNEY_REPRESENTED: 'The claimant mentions legal counsel: communicate through the attorney; litigation queue.',
  MODEL_DISAGREEMENT: 'The independently trained second-opinion model disagrees with Gemini about the damage.',
  PHOTO_REUSED: 'A photo matches one already submitted on another claim (possibly resized).',
  PHOTO_SUBJECT_MISMATCH: 'The photos do not show what the policy insures (e.g. a house on an auto policy).',
  POSSIBLE_FLOOD_EXCLUSION: 'The photos show flood, which standard homeowners policies exclude. Confirm the cause of loss.',
  CONFIDENCE_PHOTO_QUALITY_MISMATCH: 'High AI confidence was reported for a poor photo.',
  AMBIGUOUS_DAMAGE_TYPE: 'The AI could not settle on one damage type; check the photos yourself.',
  DUPLICATE_SUBMISSION: 'A duplicate FNOL was submitted for this loss; verify it.',
  FRAUD_ANOMALY: 'Several intake details are unusual together. Consider an SIU referral; this is a statistical signal, not a finding of fraud.',
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
      <DecisionTrail claim={claim} />
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
          ['Acknowledge by', claim.branchB ? isoDate(claim.branchB.deadline.acknowledgeBy ?? claim.branchB.deadline.deadline) : null],
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
      {claim.fraudAnomaly && <FraudAnomalyPanel fraud={claim.fraudAnomaly} />}
    </>
  );
}

/** The anomaly score next to the WHY panel: score, threshold and the details that contributed most. */
function FraudAnomalyPanel({ fraud }: { fraud: FraudAnomaly }) {
  return (
    <Panel title="Fraud Anomaly Score (advisory)" flush>
      <DetailView rows={[
        ['Score', `${fraud.score} points (flag at ${fraud.threshold})`],
        ['Result', <StatusChip value={fraud.anomalous ? 'REVIEW' : 'PASS'} />],
        ['What it means', fraud.anomalous
          ? 'Several intake details are unusual together. Consider a referral to the special investigations unit.'
          : 'Nothing unusual enough to flag.'],
      ]} />
      {fraud.topContributors.length > 0 && (
        <ListView head={['Detail', 'Value', 'Points']}>
          {fraud.topContributors.map((c) => (
            <tr key={c.feature}><td>{c.label}</td><td>{c.value}</td><td>+{c.points}</td></tr>
          ))}
        </ListView>
      )}
      <p className="muted wrap" style={{ padding: '8px 12px' }}>
        It routes the claim to you and never denies it. Calibrated on synthetic data only; its accuracy on real claims
        is unknown. Channel, time of day, location and personal details are not used.
      </p>
    </Panel>
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
            ['Photos show', d.photoSubject ? humanize(d.photoSubject) : null],
            ['Sent to external AI', d.privacy ? `${d.privacy.photosSent} photo${d.privacy.photosSent === 1 ? '' : 's'}`
              + (d.privacy.redacted ? `, ${d.privacy.facesBlurred} face${d.privacy.facesBlurred === 1 ? '' : 's'} and `
                + `${d.privacy.platesBlurred} plate${d.privacy.platesBlurred === 1 ? '' : 's'} blurred` : ', not redacted')
              + (d.privacy.photosWithheld ? `; ${d.privacy.photosWithheld} withheld (VIN)` : '') : null],
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
      <SecondOpinionPanel geminiType={d.damageType} second={a.secondOpinion} />
    </>
  );
}

/**
 * How this claim was decided, in three parts: what machine learning estimated (and what it was allowed to see),
 * what the rules decided from those estimates, with the claim's own numbers, and what the adjuster decided.
 * ML never pays, denies or closes a claim; money and legal steps are rules; a licensed adjuster decides.
 */
function DecisionTrail({ claim }: { claim: DashboardClaim }) {
  const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`;
  const a = claim.branchA;
  const b = claim.branchB;
  const r = claim.record;
  const d = a?.damageAssessment;
  const so = a?.secondOpinion;
  const res = a?.reserve;
  const flags = [...(claim.inputFlags ?? []), ...(claim.gateFlags ?? [])];
  const agreement = d && so ? so.damageProbabilities[d.damageType] : undefined;
  const timing = (claim.notes ?? []).find((n) => n.startsWith('Processing time:'));
  const decided = !!r?.adjusterDecision;
  const reserveRule = res
    ? (res.claimedAmount != null
      ? `lower of claimed ${money(res.claimedAmount)} and AI estimate ${money(res.estimatedDamage)} = ${money(res.settlementBasis)}`
      : `AI estimate ${money(res.estimatedDamage)}`)
      + `, minus deductible ${money(res.deductible ?? a?.coverage.deductible)} = ${money(res.recommendedReserve)}`
      + (res.coverageLimit ? ` (never above the ${money(res.coverageLimit)} limit)` : '')
    : null;

  return (
    <Panel title="How this claim was decided">
      <div className="trail">
        <section className="trail-part">
          <h3><span className="trail-tag ml">ML</span> Machine learning estimated</h3>
          {d ? (
            <ul>
              <li><strong>{humanize(d.damageType)}</strong>, {humanize(d.severity).toLowerCase()}, estimate {money(d.estimatedDamage)}
                {' '}(Gemini; confidence {Number(d.confidence).toFixed(2)}{d.rawConfidence != null ? ', calibrated' : ''})</li>
              <li>{so ? (agreement != null
                ? `Second-opinion model gives that ${(agreement * 100).toFixed(1)}%${agreement < 0.15 ? ': it disagrees' : ''}`
                : 'Second-opinion model has no view on this damage type')
                : 'Second-opinion model not available for this claim'}</li>
              <li>{d.privacy
                ? `${d.privacy.photosSent} photo${d.privacy.photosSent === 1 ? '' : 's'} sent to Gemini`
                  + (d.privacy.redacted ? ` after blurring ${plural(d.privacy.facesBlurred, 'face')} and ${plural(d.privacy.platesBlurred, 'plate')}` : ', not blurred')
                  + (d.privacy.photosWithheld ? `; ${d.privacy.photosWithheld} VIN photo withheld` : '')
                : 'No photo was sent to an external model'}</li>
            </ul>
          ) : <p className="muted">No AI analysis stored for this claim.</p>}
          <p className="trail-note">Estimates only: machine learning never pays, denies or closes a claim.</p>
        </section>

        <section className="trail-part">
          <h3><span className="trail-tag rules">Rules</span> Rules decided what is allowed</h3>
          <ul>
            {a && <li>Coverage: {a.coverage.covered ? `covered under ${a.coverage.coverageType}` : 'not covered'}
              {a.coverage.reasoning?.length ? ` (${a.coverage.reasoning[a.coverage.reasoning.length - 1]})` : ''}</li>}
            {reserveRule && <li>Reserve: {reserveRule}</li>}
            {b && <li>Deadlines ({b.deadline.state}): acknowledge by {isoDate(b.deadline.acknowledgeBy ?? b.deadline.deadline)}
              {b.deadline.decisionBy ? `, decide by ${isoDate(b.deadline.decisionBy)}` : ''}</li>}
            {b && <li>Personal data removed from the description before analysis
              {b.pii.piiDetected ? ` (${b.pii.detectedTypes.length} field type${b.pii.detectedTypes.length === 1 ? '' : 's'} found)` : ''}</li>}
            <li>Route: {flags.length
              ? <>sent to an adjuster because of {flags.map((f) => humanize(f).toLowerCase()).join(', ')}</>
              : claim.status === 'PENDING_ADVISORY_REVIEW' ? 'straight-through eligible; waits for an adjuster to confirm'
                : humanize(claim.status)}</li>
          </ul>
          <p className="trail-note">Fixed, written rules: the same inputs always give the same result, and every step is in the audit history.</p>
        </section>

        <section className="trail-part">
          <h3><span className="trail-tag human">Adjuster</span> A licensed adjuster decides</h3>
          {decided ? (
            <ul>
              <li><strong>{humanize(r!.adjusterDecision!)}</strong> by {r!.adjusterId} on {dateTime(r!.reviewedAt)}</li>
              <li>Final reserve {money(r!.adjusterReserve ?? r!.reserve)}
                {r!.adjusterReserve != null && r!.adjusterReserve !== r!.reserve ? ` (AI recommended ${money(r!.reserve)})` : ''}</li>
              {r!.overrideReason && <li>Reason for the change: {r!.overrideReason}</li>}
              {r!.adjusterNotes && <li>Notes: {r!.adjusterNotes}</li>}
            </ul>
          ) : (
            <p><strong>Waiting for an adjuster.</strong> Until then the claim is a draft in ClaimCenter: nothing is paid,
              denied or closed.</p>
          )}
          <p className="trail-note">The adjuster can change the reserve (with a recorded reason) and confirm or correct the damage.</p>
        </section>
      </div>
      {timing && <p className="hint">{timing}</p>}
    </Panel>
  );
}

/** A date as YYYY-MM-DD, whether stored as text or as a [year, month, day] array (older saved analyses). */
function isoDate(v: unknown): string {
  if (Array.isArray(v) && v.length >= 3) {
    return `${v[0]}-${String(v[1]).padStart(2, '0')}-${String(v[2]).padStart(2, '0')}`;
  }
  return v == null ? '—' : String(v);
}

/** Damage types an adjuster can confirm, by line (home coverages get causes of loss). */
function DAMAGE_CHOICES(coverage?: string): string[] {
  return coverage === 'DWELLING' || coverage === 'PERSONAL_PROPERTY'
    ? ['WATER_DAMAGE', 'FLOOD', 'FIRE_SMOKE', 'WIND_STORM', 'FALLEN_TREE', 'MULTIPLE', 'UNKNOWN']
    : ['FRONT_BUMPER', 'REAR_BUMPER', 'SIDE_PANEL', 'WINDSHIELD', 'ROOF', 'MULTIPLE', 'UNKNOWN'];
}

/** The independently trained model's view of the same photos, next to Gemini's answer. */
function SecondOpinionPanel({ geminiType, second }: { geminiType: string; second?: SecondOpinion | null }) {
  if (!second) {
    return (
      <Panel title="Second Opinion (trained model)">
        <p className="muted">Not available for this claim: the model was off or unreachable, or there was no uploaded photo.</p>
      </Panel>
    );
  }
  const ranked = Object.entries(second.damageProbabilities).sort((x, y) => y[1] - x[1]);
  const agreement = second.damageProbabilities[geminiType];
  const subject = Object.entries(second.subjectProbabilities).sort((x, y) => y[1] - x[1])[0];
  return (
    <Panel title="Second Opinion (trained model)" flush>
      <div className="panel-body">
        <DetailView columns={2} rows={[
          ['Gemini says', humanize(geminiType)],
          ['Second opinion gives that', agreement != null ? `${(agreement * 100).toFixed(1)}%` : 'Not one of its classes'],
          ['Second opinion favours', ranked.length ? `${humanize(ranked[0][0])} (${Math.round(ranked[0][1] * 100)}%)` : '—'],
          ['Photos show', subject ? `${humanize(subject[0])} (${Math.round(subject[1] * 100)}%)` : '—'],
        ]} />
        <p className="hint">{second.model}. Trained separately from Gemini; below 15% for Gemini's answer the claim goes to an adjuster.</p>
      </div>
      <ListView head={['Second opinion', 'Probability', '']}>
        {ranked.map(([type, p]) => (
          <tr key={type}>
            <td>{humanize(type)}</td>
            <td className="num">{Math.round(p * 100)}%</td>
            <td className="bar-cell"><span className="bar" style={{ width: `${Math.round(p * 100)}%` }} /></td>
          </tr>
        ))}
      </ListView>
    </Panel>
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
        <Panel title="Regulatory Deadlines">
          <DetailView rows={[
            ['State', b.deadline.state],
            ['Acknowledge by', b.deadline.acknowledgeBy || b.deadline.deadline
              ? `${isoDate(b.deadline.acknowledgeBy ?? b.deadline.deadline)}${b.deadline.acknowledgeCitation ? ` — ${b.deadline.acknowledgeCitation}` : ''}`
              : 'No cited rule for this state'],
            ['Accept or deny by', b.deadline.decisionBy
              ? `${isoDate(b.deadline.decisionBy)}${b.deadline.decisionCitation ? ` — ${b.deadline.decisionCitation}` : ''}`
              : b.deadline.decisionNote ?? null],
            ['Recover from at-fault party by', b.deadline.recoverBy
              ? `${isoDate(b.deadline.recoverBy)} — ${b.deadline.recoveryCitation ?? ''}` : null],
            ['Urgency', <StatusChip value={b.deadline.urgencyFlag} />],
            ['Rule set', b.deadline.rulesVersion ? `Cited rules, version ${b.deadline.rulesVersion}` : b.deadline.ruleSource],
          ]} />
          <p className="hint">{b.deadline.disclaimer}</p>
        </Panel>
      </div>
      {(claim.inputFlags ?? []).includes('ATTORNEY_REPRESENTED') && (
        <MessageBar tone="warning" title="Claimant represented by an attorney">
          Communicate about this claim through the attorney, not the claimant directly. The claim is routed to the
          litigation and represented-claims queue and is not eligible for straight-through processing.
        </MessageBar>
      )}
      <AcknowledgementLetterPanel claimId={claim.claimId} />
      <Panel title="Subrogation">
        <DetailView columns={2} rows={[
          ['Score', `${b.subrogation.score} / 100`],
          ['Recommended action', <StatusChip value={b.subrogation.recommendedAction} />],
          ...Object.entries(b.subrogation.factors).map(([k, v]) => [humanize(k.replace(/([A-Z])/g, '_$1')), `${v} pts`] as [string, string]),
        ]} />
        {b.subrogation.thirdParty && Object.keys(b.subrogation.thirdParty).length > 0 && (
          <>
            <div className="field-label" style={{ marginTop: 12 }}>The other party (from the description)</div>
            <DetailView columns={2} rows={Object.entries(b.subrogation.thirdParty).map(([k, v]) =>
              [humanize(k.replace(/([A-Z])/g, '_$1')), v] as [string, string])} />
          </>
        )}
        {(b.subrogation.complianceNotes ?? []).length > 0 && (
          <ul className="notes compact">{b.subrogation.complianceNotes!.map((n) => <li key={n}>{n}</li>)}</ul>
        )}
      </Panel>
    </>
  );
}

/** The written acknowledgement filed on the ClaimCenter claim at intake (template, not AI). */
function AcknowledgementLetterPanel({ claimId }: { claimId: string }) {
  const [letter, setLetter] = useState<string | null | undefined>(undefined);
  useEffect(() => {
    apiClient.getClaimCenterView(claimId)
      .then((cc) => {
        const doc = cc.attachments.find((a) => a.name === 'acknowledgement-letter.txt');
        setLetter(typeof doc?.body === 'string' ? doc.body : null);
      })
      .catch(() => setLetter(null));
  }, [claimId]);
  if (letter === undefined) return null;
  return (
    <Panel title="Acknowledgement Letter">
      {letter
        ? <pre className="letter">{letter}</pre>
        : <p className="muted">No acknowledgement letter on file (claim filed before letters were generated, or ClaimCenter
          is unavailable).</p>}
      <p className="hint">Generated from a fixed template at intake and filed on the ClaimCenter claim (prototype: not posted
        or e-mailed).</p>
    </Panel>
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

function Documents({ claimId, docs, reload, onChanged }: {
  claimId: string; docs: ClaimDocument[]; reload: number; onChanged: () => void;
}) {
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
      <PhotoRequestList claimId={claimId} reload={reload} onChanged={onChanged} />
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

function Decision({ claim, inspections, docs, reload, onChanged }: {
  claim: DashboardClaim; inspections: Inspection[]; docs: ClaimDocument[]; reload: number; onChanged: () => void;
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
  const [requestingPhotos, setRequestingPhotos] = useState(false);
  const aiType = claim.branchA?.damageAssessment.damageType ?? '';
  const [confirmedType, setConfirmedType] = useState(aiType);

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
      const res = await apiClient.submitAdjusterReview(claim.claimId,
        { decision, adjusterId, notes: text, ...override, confirmedDamageType: confirmedType || undefined });
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
            <Button disabled={busy} onClick={() => setRequestingPhotos(true)}>Request more photos</Button>
            <Button disabled={busy} onClick={() => navigate(`/claim/${claim.claimId}/inspection`)}>Assign inspector</Button>
            <Button disabled={busy || !adjusterId.trim()} onClick={() => submit('INVESTIGATION_REQUIRED',
              `Sent to investigation: claimed ${money(claimed)}, photos support ${money(estimate)}.`)}>
              Send to investigation</Button>
          </div>
        </MessageBar>
      )}
      {reviewable && !requestingPhotos && (
        <div className="option-row">
          <Button onClick={() => setRequestingPhotos(true)}>Request more photos from the claimant</Button>
        </div>
      )}
      {reviewable && requestingPhotos && (
        <RequestPhotos claimId={claim.claimId} docs={docs} adjusterId={adjusterId}
          policyNumber={claim.record?.policyNumber ?? claim.branchA?.coverage.policyNumber ?? ''} onChanged={onChanged} />
      )}
      <PhotoRequestList claimId={claim.claimId} reload={reload} onChanged={onChanged} />
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
          {aiType && (
            <label className="field">
              <span className="field-label">Damage the photos show (confirm or correct the AI)</span>
              <select value={confirmedType} onChange={(e) => setConfirmedType(e.target.value)} disabled={!reviewable}>
                {DAMAGE_CHOICES(claim.branchA?.coverage.coverageType ?? undefined).map((t) => (
                  <option key={t} value={t}>{humanize(t)}{t === aiType ? ' (AI)' : ''}</option>
                ))}
              </select>
            </label>
          )}
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

/**
 * What the AI filed in ClaimCenter, in ClaimCenter's own terms: the exposure that carries the reserve, the
 * adjuster's activities (one per AI flag, due by the statutory date), the advisory note and the documents.
 */
function InClaimCenter({ claimId, reload }: { claimId: string; reload: number }) {
  const [cc, setCc] = useState<CcClaim | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    setCc(null);
    setError('');
    apiClient.getClaimCenterView(claimId).then(setCc).catch((err) => setError(err.message || 'ClaimCenter unavailable'));
  }, [claimId, reload]);

  const open = cc?.activities.filter((a) => a.status === 'open').length ?? 0;
  return (
    <>
      <PageTitle title="In ClaimCenter"
        subtitle="What the AI filed on the ClaimCenter claim. ClaimCenter stays the system of record; the adjuster works it there." />
      {error && <MessageBar tone="error" title="ClaimCenter view unavailable">{error}</MessageBar>}
      {cc && (
        <Panel title="Claim">
          <DetailView columns={2} rows={[
            ['ClaimCenter status', <StatusChip key="s" value={cc.status} />],
            ['Policy', cc.policyNumber],
            ['Open activities', String(open)],
            ['Total open reserve', money(cc.exposures.filter((e) => e.status === 'open')
              .reduce((sum, e) => sum + Number(e.reserve ?? 0), 0))],
          ]} />
        </Panel>
      )}
      <Panel title={`Exposures (${cc ? cc.exposures.length : '…'})`} flush>
        <ListView head={['Exposure', 'Type', 'Coverage', 'Cost type', 'Reserve', 'Status']}
          empty={cc ? 'No exposures (filed before exposures were created)' : 'Loading…'}>
          {cc?.exposures.map((e) => (
            <tr key={e.exposureId}>
              <td className="mono">{e.exposureId}</td>
              <td>{humanize(e.exposureType.replace(/([a-z])([A-Z])/g, '$1_$2'))}</td>
              <td>{humanize(e.coverageType)}</td>
              <td>{e.costType}</td>
              <td className="num">{money(e.reserve)}</td>
              <td><StatusChip value={e.status.toUpperCase()} /></td>
            </tr>
          ))}
        </ListView>
      </Panel>
      <Panel title={`Activities (${cc ? cc.activities.length : '…'})`} flush>
        <ListView head={['Subject', 'Priority', 'Due', 'Assigned to', 'Status']}
          empty={cc ? 'No activities' : 'Loading…'}>
          {cc?.activities.map((a) => (
            <tr key={a.activityId}>
              <td className="wrap"><strong>{a.subject}</strong>{a.description && <div className="muted">{a.description}</div>}</td>
              <td>{humanize(a.priority)}</td>
              <td>{a.dueDate ?? '—'}</td>
              <td>{a.assignedTo ?? '—'}</td>
              <td>{a.status === 'complete' ? `Complete${a.completedBy ? ` (${a.completedBy})` : ''}` : 'Open'}</td>
            </tr>
          ))}
        </ListView>
      </Panel>
      <Panel title={`Notes (${cc ? cc.claimNotes.length : '…'})`}>
        {cc && !cc.claimNotes.length && <p className="muted">No notes</p>}
        {cc?.claimNotes.map((n) => (
          <div key={n.noteId} className="cc-note">
            <div className="field-label">{n.topic} · {dateTime(n.createdAt)}</div>
            <strong>{n.subject}</strong>
            <p>{n.body}</p>
          </div>
        ))}
      </Panel>
      <Panel title={`Documents (${cc ? cc.attachments.length : '…'})`} flush>
        <ListView head={['Name', 'Type']} empty={cc ? 'No documents' : 'Loading…'}>
          {cc?.attachments.map((d, i) => (
            <tr key={i}><td>{d.name}</td><td>{d.contentType}</td></tr>
          ))}
        </ListView>
      </Panel>
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
