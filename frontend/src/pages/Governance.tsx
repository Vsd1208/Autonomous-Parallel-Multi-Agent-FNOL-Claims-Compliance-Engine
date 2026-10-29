import React, { useEffect, useState } from 'react';
import { PageTitle } from '../components/Shell';
import { ListView, MessageBar, Panel, humanize, money } from '../components/ui';
import { apiClient } from '../services/apiClient';
import { AiMetrics } from '../types';

const pct = (part: number, whole: number) => (whole > 0 ? `${Math.round((part / whole) * 100)}%` : '—');
const share = (v: number | null) => (v == null ? '—' : `${Math.round(v * 100)}%`);
const claims = (n: number) => `${n} claim${n === 1 ? '' : 's'}`;
const num = (v: number | null) => (v == null ? '—' : Number(v).toFixed(2));

/** A disparity ratio with its status, in words as well as colour. */
function Disparity({ ratio, status }: { ratio: number | null; status: 'OK' | 'REVIEW' | 'TOO_FEW' }) {
  if (status === 'TOO_FEW') return <span className="muted">Too few to compare</span>;
  return (
    <span className={status === 'REVIEW' ? 'chip warning' : 'chip success'}>
      {status === 'REVIEW' ? 'Review' : 'OK'} {ratio != null ? Number(ratio).toFixed(2) : ''}
    </span>
  );
}

/** One headline figure with what it means. */
function Stat({ label, value, note }: { label: string; value: React.ReactNode; note?: string }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {note && <div className="stat-note">{note}</div>}
    </div>
  );
}

/**
 * AI governance for claims managers and model-risk reviewers: how the AI routed claims and why, money the reserve
 * rule kept from being over-paid, how often adjusters agree with the AI, what left the system for the external
 * model, and whether the AI's answers drift over time.
 */
export default function Governance() {
  const [m, setM] = useState<AiMetrics | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    apiClient.getAiMetrics().then(setM).catch((err) => setError(err.message || 'Metrics unavailable'));
  }, []);

  const flags = m ? Object.entries(m.flags).sort((a, b) => b[1] - a[1]) : [];
  const maxFlag = flags.length ? flags[0][1] : 1;
  const a = m?.agreement;
  return (
    <main className="workspace-main solo">
      <PageTitle title="AI Governance"
        subtitle="How the AI is performing against adjusters, what it costs and saves, and what leaves the system." />
      {error && <MessageBar tone="error" title="Metrics unavailable">{error}</MessageBar>}
      {!m && !error && <p className="muted">Loading…</p>}
      {m && a && (
        <>
          <div className="stat-row">
            <Stat label="Claims analysed by the AI" value={m.claims.withAiAnalysis}
              note={`${m.claims.filed} filed in total`} />
            <Stat label="Straight-through eligible" value={pct(m.claims.straightThroughEligible, m.claims.withAiAnalysis)}
              note={`${claims(m.claims.straightThroughEligible)}; the rest went to an adjuster`} />
            <Stat label="Asked above what the photos support" value={money(m.leakage.amountNotReserved)}
              note={`${claims(m.leakage.claimsAskingAboveEstimate)}; never reserved automatically`} />
            <Stat label="Adjusters agree with the AI's damage" value={pct(a.damageTypeAgreed, a.damageTypeConfirmed)}
              note={`${a.damageTypeAgreed} of ${a.damageTypeConfirmed} confirmed decisions`} />
          </div>

          <div className="grid-2">
            <Panel title="Why claims went to an adjuster" flush>
              <ListView head={['Flag', 'Claims', '']} empty="No flags raised">
                {flags.map(([flag, n]) => (
                  <tr key={flag}>
                    <td>{humanize(flag)}</td>
                    <td className="num">{n}</td>
                    <td className="bar-cell">
                      <span className="bar" title={`${humanize(flag)}: ${n} claims`}
                        style={{ width: `${Math.round((n / maxFlag) * 100)}%` }} />
                    </td>
                  </tr>
                ))}
              </ListView>
            </Panel>

            <Panel title="Adjusters and the AI">
              <dl className="detail-view cols-1">
                <div className="detail-row"><dt>Decisions recorded</dt><dd>{a.decisions}</dd></div>
                <div className="detail-row"><dt>Straight-through eligible, approved by the adjuster</dt>
                  <dd>{a.straightThroughEligibleApproved} of {a.straightThroughEligibleDecided}</dd></div>
                <div className="detail-row"><dt>Reserve changed by the adjuster</dt>
                  <dd>{a.reserveChanged} of {a.decisions} (mean change {money(a.meanReserveChange)})</dd></div>
                {Object.entries(a.byDecision).map(([d, n]) => (
                  <div className="detail-row" key={d}><dt>{humanize(d)}</dt><dd>{n}</dd></div>
                ))}
              </dl>
              <p className="hint">Each decision is stored next to what the AI said (GET /api/v1/ai/feedback): new labelled
                data for retraining the trust score.</p>
            </Panel>
          </div>

          <Panel title="Regulatory deadlines">
            <dl className="detail-view cols-2">
              <div className="detail-row"><dt>Written acknowledgement on time</dt>
                <dd>{m.deadlines.acknowledgedOnTime} of {m.deadlines.claimsWithRule}
                  {m.deadlines.acknowledgedLate ? ` (${m.deadlines.acknowledgedLate} late)` : ''}
                  {m.deadlines.notAcknowledged ? `, ${m.deadlines.notAcknowledged} not acknowledged` : ''}</dd></div>
              <div className="detail-row"><dt>Claims in states without a cited rule</dt><dd>{m.deadlines.claimsWithoutRule}</dd></div>
              <div className="detail-row"><dt>Open claims past their decision deadline</dt>
                <dd className={m.deadlines.openDecisionsOverdue ? 'text-critical' : undefined}>{m.deadlines.openDecisionsOverdue}</dd></div>
              <div className="detail-row"><dt>Open claims due within 3 days</dt><dd>{m.deadlines.openDecisionsDueWithin3Days}</dd></div>
              <div className="detail-row"><dt>Open claims waiting for proof of loss (clock not started)</dt>
                <dd>{m.deadlines.openWithoutDecisionClock}</dd></div>
            </dl>
            <p className="hint">Deadlines come from the cited rule set (claims received from now on); claims filed before it have none.</p>
          </Panel>

          <Panel title="Fairness testing: outcomes by group" flush>
            {(['state', 'channel', 'lineOfBusiness'] as const).map((dim) => (
              <div key={dim} className="fair-block">
                <div className="fair-head">By {dim === 'lineOfBusiness' ? 'line of business' : dim}</div>
                <ListView head={['Group', 'Claims', 'Straight-through', 'Disparity', 'Decisions', 'Approved', 'Disparity',
                  'Reserve changed', 'Reserve / claimed']} empty="No claims">
                  {m.fairness[dim].map((g) => (
                    <tr key={g.group}>
                      <td>{dim === 'channel' ? humanize(g.group) : g.group}</td>
                      <td className="num">{g.claims}</td>
                      <td className="num">{share(g.straightThroughRate)}</td>
                      <td><Disparity ratio={g.straightThroughDisparity} status={g.straightThroughStatus} /></td>
                      <td className="num">{g.decisions}</td>
                      <td className="num">{share(g.approvalRate)}</td>
                      <td><Disparity ratio={g.approvalDisparity} status={g.approvalStatus} /></td>
                      <td className="num">{share(g.reserveChangedRate)}</td>
                      <td className="num">{share(g.reserveToClaimed)}</td>
                    </tr>
                  ))}
                </ListView>
              </div>
            ))}
            <p className="hint fair-method">{m.fairness.method}</p>
          </Panel>

          <Panel title="Privacy: what left the system">
            <dl className="detail-view cols-2">
              <div className="detail-row"><dt>Photos sent to the external model</dt><dd>{m.privacy.photosSent}</dd></div>
              <div className="detail-row"><dt>Photos withheld (odometer / VIN)</dt><dd>{m.privacy.photosWithheld}</dd></div>
              <div className="detail-row"><dt>Faces blurred before sending</dt><dd>{m.privacy.facesBlurred}</dd></div>
              <div className="detail-row"><dt>Licence plates blurred before sending</dt><dd>{m.privacy.platesBlurred}</dd></div>
            </dl>
            <p className="hint">Originals stay in the insurer's store. If blurring is unavailable, no photo is sent and the
              claim goes to an adjuster.</p>
          </Panel>

          <Panel title="Drift: the AI's answers week by week" flush>
            <ListView head={['Week', 'Claims', 'Mean answer gap', 'Mean confidence', 'Hedged answers', 'Second opinion disagrees']}
              empty="No AI answers yet">
              {m.drift.map((w) => (
                <tr key={w.week}>
                  <td>{w.week}</td>
                  <td className="num">{w.claims}</td>
                  <td className="num">{num(w.meanMargin)}</td>
                  <td className="num">{num(w.meanConfidence)}</td>
                  <td className="num">{share(w.hedgedShare)}</td>
                  <td className="num">{share(w.secondOpinionLowShare)}</td>
                </tr>
              ))}
            </ListView>
          </Panel>
          <p className="hint">A falling answer gap or a rising share of hedged answers or second-opinion disagreement means
            the AI is less sure than before: re-run the evaluation (tools/vision-eval).</p>
        </>
      )}
    </main>
  );
}
