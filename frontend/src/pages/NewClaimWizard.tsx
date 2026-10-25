import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { PageTitle } from '../components/Shell';
import { Button, DetailView, MessageBar, Panel, Toolbar, channelLabel, money } from '../components/ui';
import { demoScenarios, recentDate } from '../demoScenarios';
import { ApiError, apiClient } from '../services/apiClient';
import { claimStore } from '../services/claimStore';
import { FormData } from '../types';

const MAX_PHOTO_BYTES = 5 * 1024 * 1024;

const STEPS = ['Policy', 'Claimant & Reporting', 'Loss Details', 'Review & Submit'];

const emptyForm: FormData = {
  policyNumber: '',
  incidentDate: '',
  state: 'CA',
  description: '',
  claimantName: '',
  claimantDob: '',
  claimantSsn: '',
  claimantPhone: '',
  claimantEmail: '',
  channel: 'WEB',
  claimedAmount: '',
};

/** Reads an uploaded damage photo as a data: URI, which the API sends to the vision model. */
function readPhoto(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    if (!/^image\/(jpeg|png|webp|gif)$/.test(file.type)) {
      reject(new Error('Photo must be a JPEG, PNG, WebP or GIF image'));
      return;
    }
    if (file.size > MAX_PHOTO_BYTES) {
      reject(new Error('Photo must be 5 MB or smaller'));
      return;
    }
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error('Could not read the photo'));
    reader.readAsDataURL(file);
  });
}

function Field({ label, required, children, wide }: {
  label: string; required?: boolean; children: React.ReactNode; wide?: boolean;
}) {
  return (
    <label className={wide ? 'field wide' : 'field'}>
      <span className="field-label">{label}{required && <span className="required" aria-hidden="true"> *</span>}</span>
      {children}
    </label>
  );
}

/** New Claim wizard: step list on the left, Back / Next / Finish toolbar, review page before submit. */
export default function NewClaimWizard() {
  const navigate = useNavigate();
  const [step, setStep] = useState(0);
  const [form, setForm] = useState<FormData>(emptyForm);
  const [evidenceUri, setEvidenceUri] = useState('');
  const [photoName, setPhotoName] = useState('');
  const [photoPreview, setPhotoPreview] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [escalatedClaimId, setEscalatedClaimId] = useState('');
  const [stepError, setStepError] = useState('');

  const set = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
    setForm({ ...form, [e.target.name]: e.target.value });

  const loadScenario = (name: string) => {
    const scenario = demoScenarios.find((s) => s.name === name);
    if (!scenario) return;
    setForm({ ...scenario.data, incidentDate: recentDate() });
    setEvidenceUri(scenario.evidenceUri);
    setPhotoName('');
    setPhotoPreview('');
    setError('');
    setStepError('');
  };

  const onPhoto = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      const uri = await readPhoto(file);
      setEvidenceUri(uri);
      setPhotoName(file.name);
      setPhotoPreview(uri);
      setStepError('');
    } catch (err: any) {
      setStepError(err.message);
    }
  };

  const missing = (): string[] => {
    const req: [keyof FormData, string, number][] = [
      ['policyNumber', 'Policy number', 0], ['incidentDate', 'Loss date', 0], ['state', 'Loss state', 0],
      ['claimantName', 'Claimant name', 1], ['description', 'Loss description', 2],
      ['claimedAmount', 'Amount claimed', 2],
    ];
    const m = req.filter(([k, , s]) => s === step && !String(form[k]).trim()).map(([, label]) => label);
    if (step === 2 && form.claimedAmount.trim() && !(Number(form.claimedAmount) >= 0)) m.push('Amount claimed (a number, 0 or more)');
    return m;
  };

  const next = () => {
    const m = missing();
    if (m.length) {
      setStepError(`Required: ${m.join(', ')}`);
      return;
    }
    setStepError('');
    setStep(Math.min(step + 1, STEPS.length - 1));
  };

  const finish = async () => {
    setBusy(true);
    setError('');
    setEscalatedClaimId('');
    try {
      const response = await apiClient.submitFNOL({
        ...form,
        claimantPhone: form.claimantPhone || undefined,
        claimantEmail: form.claimantEmail || undefined,
        evidenceUris: evidenceUri.trim() ? [evidenceUri.trim()] : [],
        claimedAmount: form.claimedAmount.trim() ? Number(form.claimedAmount) : undefined,
      });
      claimStore.save(response);
      navigate(`/claim/${response.claimId}`);
    } catch (err: any) {
      setError(err.message || 'The claim could not be submitted');
      if (err instanceof ApiError && err.claimId) setEscalatedClaimId(err.claimId);
    } finally {
      setBusy(false);
    }
  };

  const last = step === STEPS.length - 1;

  return (
    <div className="wizard">
      <aside className="wizard-steps" aria-label="Wizard steps">
        <div className="sidebar-title">New Claim</div>
        <ol>
          {STEPS.map((label, i) => (
            <li key={label} className={i === step ? 'current' : i < step ? 'done' : ''}>
              <button type="button" disabled={i > step || busy} onClick={() => setStep(i)}>
                <span className="step-no">{i < step ? '✓' : i + 1}</span>
                <span>Step {i + 1} of {STEPS.length}: {label}</span>
              </button>
            </li>
          ))}
        </ol>
        <div className="wizard-demo">
          <label className="field">
            <span className="field-label">Load demo scenario</span>
            <select defaultValue="" onChange={(e) => { loadScenario(e.target.value); e.target.value = ''; }} disabled={busy}>
              <option value="" disabled>Choose…</option>
              {demoScenarios.map((s) => <option key={s.name} value={s.name}>{s.name}</option>)}
            </select>
          </label>
        </div>
      </aside>

      <main className="workspace-main">
        <Toolbar>
          <Button disabled={step === 0 || busy} onClick={() => { setStepError(''); setStep(step - 1); }}>‹ Back</Button>
          {!last && <Button variant="primary" onClick={next}>Next ›</Button>}
          {last && <Button variant="primary" disabled={busy} onClick={finish}>{busy ? 'Processing…' : 'Finish'}</Button>}
          <Button disabled={busy} onClick={() => navigate('/')}>Cancel</Button>
        </Toolbar>

        <PageTitle title={`Step ${step + 1} of ${STEPS.length}: ${STEPS[step]}`} />

        {busy && (
          <MessageBar tone="info" title="Analysing the claim">
            Damage, coverage, reserve, PII, deadlines and subrogation are running in parallel. This usually takes a few seconds.
          </MessageBar>
        )}
        {stepError && <MessageBar tone="warning" title={stepError} />}
        {error && (
          <MessageBar tone="error" title="The claim was not filed">
            {error}
            {escalatedClaimId && (
              <> It was saved for human attention as <Link to={`/claim/${escalatedClaimId}`}>{escalatedClaimId}</Link>.</>
            )}
          </MessageBar>
        )}

        {step === 0 && (
          <Panel title="Policy and loss">
            <div className="form-grid">
              <Field label="Policy number" required>
                <input name="policyNumber" value={form.policyNumber} onChange={set} placeholder="POL-AUTO-112233" />
              </Field>
              <Field label="Loss date" required>
                <input type="date" name="incidentDate" value={form.incidentDate} onChange={set} />
              </Field>
              <Field label="Loss state" required>
                <select name="state" value={form.state} onChange={set}>
                  <option value="CA">California</option>
                  <option value="NY">New York</option>
                  <option value="TX">Texas</option>
                  <option value="FL">Florida</option>
                  <option value="IL">Illinois</option>
                </select>
              </Field>
            </div>
            <p className="hint">Seeded policies: POL-AUTO-112233 (CA), POL-AUTO-223344 (NY), POL-AUTO-445566 (CA), POL-HOME-334455 (TX).</p>
          </Panel>
        )}

        {step === 1 && (
          <Panel title="Claimant and how the loss was reported">
            <div className="form-grid">
              <Field label="Claimant name" required>
                <input name="claimantName" value={form.claimantName} onChange={set} placeholder="As on the policy" />
              </Field>
              <Field label="How reported">
                <select name="channel" value={form.channel} onChange={set}>
                  <option value="WEB">Web portal</option>
                  <option value="MOBILE">Mobile app</option>
                  <option value="PHONE">Phone (entered by agent)</option>
                  <option value="API">API / partner</option>
                </select>
              </Field>
              <Field label="Date of birth">
                <input name="claimantDob" value={form.claimantDob} onChange={set} placeholder="MM/DD/YYYY" />
              </Field>
              <Field label="SSN">
                <input name="claimantSsn" value={form.claimantSsn} onChange={set} placeholder="123-45-6789" />
              </Field>
              <Field label="Phone">
                <input type="tel" name="claimantPhone" value={form.claimantPhone} onChange={set} />
              </Field>
              <Field label="Email">
                <input type="email" name="claimantEmail" value={form.claimantEmail} onChange={set} />
              </Field>
            </div>
            <p className="hint">Personal details are redacted before analysis and never stored.</p>
          </Panel>
        )}

        {step === 2 && (
          <Panel title="Loss details and evidence">
            <div className="form-grid">
              <Field label="Description of loss" required wide>
                <textarea name="description" value={form.description} onChange={set} rows={5}
                  placeholder="What happened, where, and which parts were damaged" />
              </Field>
              <Field label="Amount claimed ($)" required>
                <input type="number" min="0" step="1" name="claimedAmount" value={form.claimedAmount} onChange={set}
                  placeholder="Repair quote or the claimant's estimate" />
              </Field>
              <Field label="Damage photo" wide>
                <input type="file" accept="image/jpeg,image/png,image/webp,image/gif" onChange={onPhoto} />
              </Field>
              <Field label="…or evidence URI" wide>
                <input value={photoName ? '' : evidenceUri} disabled={!!photoName}
                  onChange={(e) => setEvidenceUri(e.target.value)}
                  placeholder="mock://photo/normal.jpg (demo) or an allow-listed https URL" />
              </Field>
            </div>
            {photoPreview && (
              <figure className="photo-preview">
                <img src={photoPreview} alt="Uploaded damage" />
                <figcaption>{photoName} <Button onClick={() => { setPhotoName(''); setPhotoPreview(''); setEvidenceUri(''); }}>Remove</Button></figcaption>
              </figure>
            )}
          </Panel>
        )}

        {last && (
          <>
            <Panel title="Review">
              <DetailView
                columns={2}
                rows={[
                  ['Policy number', form.policyNumber],
                  ['Loss date', form.incidentDate],
                  ['Loss state', form.state],
                  ['How reported', channelLabel(form.channel)],
                  ['Claimant', form.claimantName],
                  ['Date of birth', form.claimantDob ? 'Provided (redacted before analysis)' : ''],
                  ['SSN', form.claimantSsn ? 'Provided (redacted before analysis)' : ''],
                  ['Amount claimed', form.claimedAmount ? money(Number(form.claimedAmount)) : ''],
                  ['Evidence', photoName || evidenceUri || 'None (the claim will be flagged MISSING EVIDENCE)'],
                ]}
              />
              <div className="review-description">
                <div className="field-label">Description of loss</div>
                <p>{form.description}</p>
              </div>
            </Panel>
            <MessageBar tone="info" title="What happens on Finish">
              The AI analyses the claim and pre-populates a draft in ClaimCenter. It recommends; an adjuster decides.
              If the amount claimed is within what the photos support, the claimed amount (less the deductible) is
              recommended; if it is higher, the claim goes to an adjuster to request more photos or investigate.
            </MessageBar>
          </>
        )}
      </main>
    </div>
  );
}
