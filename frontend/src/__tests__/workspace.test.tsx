// @vitest-environment jsdom
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import fs from 'node:fs';
import path from 'node:path';
import { Session, session } from '../services/session';
import { abstentionReasons, confidenceText, ruleLabel } from '../utils/uncertainty';
import { AbstentionNotice, AppliedRulesPanel } from '../components/Uncertainty';
import { AppHeader } from '../components/Shell';
import { RoleGuard } from '../components/RoleGuard';
import Operations from '../pages/Operations';
import ClaimFile from '../pages/ClaimFile';
import { apiClient } from '../services/apiClient';
import { DamageAssessment } from '../types';

function signIn(role: Session['role']) {
  session.save({ token: 't', username: 'U-1', displayName: 'Test Person', role, expiresAt: '2099-01-01T00:00:00Z' });
}

const assessment = (extra: Partial<DamageAssessment> = {}) =>
  ({ damageType: 'FRONT_BUMPER', severity: 'MODERATE', estimatedDamage: 4200, confidence: 0.91, ...extra }) as DamageAssessment;

beforeEach(() => window.sessionStorage.clear());
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('role-aware navigation', () => {
  const tabs = (role: Session['role']) => {
    signIn(role);
    render(<MemoryRouter><AppHeader onSignedOut={() => undefined} /></MemoryRouter>);
    return screen.getAllByRole('link').map((l) => l.textContent);
  };

  it('an inspector sees neither intake, governance nor operations', () => {
    const links = tabs('INSPECTOR');
    expect(links).toContain('Desktop');
    expect(links).not.toContain('New Claim');
    expect(links).not.toContain('AI Governance');
    expect(links).not.toContain('Operations');
  });

  it('an adjuster sees intake and governance, not operations', () => {
    const links = tabs('ADJUSTER');
    expect(links).toContain('New Claim');
    expect(links).toContain('AI Governance');
    expect(links).not.toContain('Operations');
  });

  it('a supervisor also sees operations', () => {
    expect(tabs('SUPERVISOR')).toContain('Operations');
  });
});

describe('route access', () => {
  const at = (role: Session['role'], area: 'NEW_CLAIM' | 'GOVERNANCE' | 'OPERATIONS') => {
    signIn(role);
    render(
      <MemoryRouter initialEntries={['/guarded']}>
        <Routes>
          <Route path="/" element={<p>Desktop page</p>} />
          <Route path="/guarded" element={<RoleGuard area={area}><p>Guarded page</p></RoleGuard>} />
        </Routes>
      </MemoryRouter>,
    );
  };

  it('typing the address does not show an inspector the page', () => {
    at('INSPECTOR', 'GOVERNANCE');
    expect(screen.queryByText('Guarded page')).toBeNull();
    expect(screen.getByText('Desktop page')).toBeTruthy();
  });

  it('an adjuster cannot open operations; a supervisor can', () => {
    at('ADJUSTER', 'OPERATIONS');
    expect(screen.queryByText('Guarded page')).toBeNull();
    cleanup();
    at('SUPERVISOR', 'OPERATIONS');
    expect(screen.getByText('Guarded page')).toBeTruthy();
  });
});

describe('confidence is not shown as what it is not', () => {
  it('an uncalibrated number says so and is never a percentage', () => {
    const text = confidenceText(assessment());
    expect(text).toContain('0.91');
    expect(text).toContain('not calibrated');
    expect(text).not.toContain('%');
  });

  it('a calibrated score says what it is and keeps the model\'s own figure apart', () => {
    const text = confidenceText(assessment({ confidence: 0.82, rawConfidence: 0.9 }));
    expect(text).toContain('0.82 trust score');
    expect(text).toContain('0.90');
  });

  it('a failed call\'s placeholder is not shown as a number at all', () => {
    const text = confidenceText(assessment({ confidence: 0.6 }), { abstained: true, reasons: ['INFERENCE_FAILURE'] });
    expect(text).not.toContain('0.60');
    expect(text).toContain('did not answer');
  });
});

describe('abstention', () => {
  it('every reason the server can send has plain words', () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../../../fnol-orchestrator/src/main/java/com/guidewire/fnol/orchestrator/AbstentionPolicy.java'), 'utf-8');
    const reasons = [...source.matchAll(/static final String ([A-Z_]+) = "\1";/g)].map((m) => m[1]).filter((r) => r !== 'AI_ABSTAINED');
    expect(reasons.length).toBeGreaterThanOrEqual(8);
    for (const r of reasons) {
      expect(abstentionReasons({ abstained: true, reasons: [r] })[0], r).not.toBe(r);
    }
  });

  it('shows the reasons, and says it is not against the claim', () => {
    render(<AbstentionNotice abstention={{ abstained: true, reasons: ['INSUFFICIENT_QUALITY'] }} />);
    expect(screen.getByText(/too poor to read/)).toBeTruthy();
    expect(screen.getByText(/says nothing against the claim/)).toBeTruthy();
  });

  it('shows nothing when the AI did not abstain, or on an older claim', () => {
    const { container } = render(<><AbstentionNotice abstention={{ abstained: false, reasons: [] }} /><AbstentionNotice /></>);
    expect(container.textContent).toBe('');
  });
});

describe('regulatory rules', () => {
  it('shows how far each rule was checked, and that no lawyer reviewed it', () => {
    render(<AppliedRulesPanel rules={[{ ruleId: 'CA-RECOVERY', ruleVersion: 1, category: 'STATUTORY', verificationStatus: 'UNVERIFIED', effectiveFrom: null, effectiveTo: null, citation: 'Cal. Code', sourceUrl: null }]} />);
    expect(screen.getByText('Not verified')).toBeTruthy();
    expect(screen.getByText('Not researched')).toBeTruthy();
    expect(screen.getByText(/reviewed by a lawyer/)).toBeTruthy();
    expect(ruleLabel('EXPERIMENTAL')).toBe('Experimental');
  });
});

describe('operations', () => {
  it('lists dead letters, and a replay calls the server and reloads', async () => {
    signIn('SUPERVISOR');
    const item = { id: 'r1', eventId: 'e1', eventType: 'claim.submitted', aggregateId: 'CLM-1', status: 'DEAD_LETTER', attemptCount: 5, lastError: 'timeout', receivedAt: '2026-11-04T10:00:00Z' };
    const get = vi.spyOn(apiClient, 'getEventInbox').mockResolvedValue({ depth: { DEAD_LETTER: 1 }, items: [item] });
    const replay = vi.spyOn(apiClient, 'replayEvent').mockResolvedValue(undefined);
    render(<MemoryRouter><Operations /></MemoryRouter>);

    fireEvent.click(await screen.findByRole('button', { name: 'Replay' }));

    await waitFor(() => expect(replay).toHaveBeenCalledWith('r1'));
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2));
  });

  it('the server refusing is shown, not swallowed', async () => {
    signIn('SUPERVISOR');
    vi.spyOn(apiClient, 'getEventInbox').mockRejectedValue(new Error('Forbidden'));
    render(<MemoryRouter><Operations /></MemoryRouter>);
    expect(await screen.findByText('Forbidden')).toBeTruthy();
  });
});

describe('the claim file by role', () => {
  const open = (section = '') => render(
    <MemoryRouter initialEntries={[`/claim/CLM-1${section}`]}>
      <Routes>
        <Route path="/claim/:claimId" element={<ClaimFile />} />
        <Route path="/claim/:claimId/:section" element={<ClaimFile />} />
      </Routes>
    </MemoryRouter>,
  );

  it('an inspector gets the limited screen: no analysis is even requested', async () => {
    signIn('INSPECTOR');
    const analysis = vi.spyOn(apiClient, 'getAnalysis');
    const status = vi.spyOn(apiClient, 'getClaimStatus');
    vi.spyOn(apiClient, 'getDocuments').mockResolvedValue([]);
    vi.spyOn(apiClient, 'getInspectionView').mockResolvedValue({ claimId: 'CLM-1', lineOfBusiness: 'AUTO', inspections: [] } as any);

    open();

    expect(await screen.findByText('Claim CLM-1')).toBeTruthy();
    expect(analysis).not.toHaveBeenCalled();
    expect(status).not.toHaveBeenCalled();
    expect(screen.queryByText(/Record a decision/)).toBeNull();
  });

  it('an adjuster on an abstained claim sees it first, with no invented confidence, and may decide', async () => {
    signIn('ADJUSTER');
    vi.spyOn(apiClient, 'getDocuments').mockResolvedValue([]);
    vi.spyOn(apiClient, 'getInspections').mockResolvedValue([]);
    vi.spyOn(apiClient, 'getClaimStatus').mockResolvedValue({ claimId: 'CLM-1', status: 'HUMAN_REVIEW', policyNumber: 'P' } as any);
    vi.spyOn(apiClient, 'getExplanation').mockResolvedValue({ decision: 'ADVISORY_RECOMMENDED_ACTION', summary: 'Review', recommendedAction: 'ESCALATE_TO_HUMAN', factors: [], severityGate: 'LOW' } as any);
    vi.spyOn(apiClient, 'getAnalysis').mockResolvedValue({ abstention: { abstained: true, reasons: ['INFERENCE_FAILURE'] } } as any);

    open();

    expect(await screen.findByText(/The AI abstained/)).toBeTruthy();
    expect(screen.getByText(/main AI model did not answer/)).toBeTruthy();
    expect(screen.getByText('Record a decision')).toBeTruthy();
  });

  it('a claim that has finished cannot be decided again from the screen', async () => {
    signIn('ADJUSTER');
    vi.spyOn(apiClient, 'getDocuments').mockResolvedValue([]);
    vi.spyOn(apiClient, 'getInspections').mockResolvedValue([]);
    vi.spyOn(apiClient, 'getClaimStatus').mockResolvedValue({ claimId: 'CLM-1', status: 'APPROVED_BY_ADJUSTER' } as any);
    vi.spyOn(apiClient, 'getExplanation').mockResolvedValue({ decision: 'STRAIGHT_THROUGH', summary: 'Fine', recommendedAction: 'CONFIRM', factors: [], severityGate: 'LOW' } as any);
    vi.spyOn(apiClient, 'getAnalysis').mockResolvedValue(null as any);

    open();

    expect(await screen.findByText(/AI advisory/)).toBeTruthy();
    expect(screen.queryByText('Record a decision')).toBeNull();
  });
});

describe('the portal stays separate', () => {
  const files = (dir: string): string[] => fs.readdirSync(dir, { withFileTypes: true })
    .flatMap((e) => (e.isDirectory() ? files(path.join(dir, e.name)) : [path.join(dir, e.name)]));

  it('shares only data and photo helpers with the workspace: no page, component or credential code, and no adjuster route', () => {
    const portal = path.resolve(__dirname, '../../portal/src');
    const sources = files(portal).filter((f) => /\.tsx?$/.test(f));
    expect(sources.length).toBeGreaterThan(0);
    for (const file of sources) {
      const text = fs.readFileSync(file, 'utf-8');
      const fromWorkspace = [...text.matchAll(/from '(\.\.\/)+src\/([^']+)'/g)].map((m) => m[2]);
      expect(fromWorkspace.filter((m) => /^(pages|components|services)\//.test(m)), file).toEqual([]);
      expect(text, file).not.toMatch(/X-Adjuster-Token|\/api\/v1\/ai\/|\/api\/v1\/admin\//);
    }
  });
});
