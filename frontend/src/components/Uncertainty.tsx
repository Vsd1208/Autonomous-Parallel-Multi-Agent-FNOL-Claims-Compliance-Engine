import React from 'react';
import { ListView, MessageBar, Panel } from './ui';
import { Abstention, AppliedRule } from '../types';
import { abstentionReasons, ruleLabel } from '../utils/uncertainty';

/** Says plainly when the AI has no answer worth relying on, and that this is not a finding against the claim. */
export function AbstentionNotice({ abstention }: { abstention?: Abstention | null }) {
  const reasons = abstentionReasons(abstention);
  if (reasons.length === 0) return null;
  return (
    <MessageBar tone="warning" title="The AI abstained: please decide this from the evidence">
      <ul className="flag-list">
        {reasons.map((r) => <li key={r}>{r}</li>)}
      </ul>
      This says nothing against the claim. Treat any AI figures on this claim as unreliable.
    </MessageBar>
  );
}

/** Which version of which rule set each date, and how far it was checked. None has had a lawyer's review. */
export function AppliedRulesPanel({ rules }: { rules?: AppliedRule[] | null }) {
  if (!rules || rules.length === 0) return null;
  return (
    <Panel title="What these dates rest on">
      <ListView head={['Rule', 'Version', 'Kind', 'How far checked', 'In force from']}>
        {rules.map((r) => (
          <tr key={`${r.ruleId}-${r.ruleVersion}`}>
            <td>{r.sourceUrl ? <a href={r.sourceUrl} target="_blank" rel="noreferrer">{r.citation}</a> : r.citation}</td>
            <td>{r.ruleVersion}</td>
            <td>{ruleLabel(r.category)}</td>
            <td>{ruleLabel(r.verificationStatus)}</td>
            <td>{r.effectiveFrom ?? 'Not researched'}</td>
          </tr>
        ))}
      </ListView>
      <p className="hint">No rule here has been reviewed by a lawyer. Confirm the deadline before relying on it.</p>
    </Panel>
  );
}
