import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { demoScenarios, recentDate } from '../../../src/demoScenarios';
import { AUTO_ANGLES, HOME_ANGLES, anglesFor } from '../../../src/photoAngles';
import { HOME_SET_FILES, SET_FILES, homeSampleSets, sampleSets, vehicleSampleSets } from '../../../src/sampleSets';
import { readPhoto } from '../../../src/utils/photos';
import ClaimStatus from '../ClaimStatus';
import { PortalClaim, PortalError, portalApi } from '../portalApi';
import { Alert, Card, Field, STATES, longDate } from '../ui';

type Photo = { uri: string; name: string };
type Form = {
  policyNumber: string; claimantName: string; claimantPhone: string; claimantEmail: string;
  incidentDate: string; state: string; description: string; claimedAmount: string;
};
const EMPTY: Form = {
  policyNumber: '', claimantName: '', claimantPhone: '', claimantEmail: '',
  incidentDate: '', state: 'CA', description: '', claimedAmount: '',
};
const STEPS = ['Your policy', 'What happened', 'Photos', 'Check and send'];
const today = () => new Date().toISOString().slice(0, 10);

/** Reporting a loss: four short steps, then the claim number and what happens next. */
export default function Report() {
  const [step, setStep] = useState(0);
  const [form, setForm] = useState<Form>(EMPTY);
  const [photos, setPhotos] = useState<Record<string, Photo>>({});
  const [noMorePhotos, setNoMorePhotos] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [problem, setProblem] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<{ title: string; text: string } | null>(null);
  const [receipt, setReceipt] = useState<PortalClaim | null>(null);
  const [loadingSet, setLoadingSet] = useState(false);

  const angles = anglesFor(form.policyNumber);
  const home = angles === HOME_ANGLES;
  const missingAngles = angles.filter((a) => a.required && !photos[a.key]);
  const set = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
    setForm({ ...form, [e.target.name]: e.target.value });

  const check = (): string[] => {
    const m: string[] = [];
    if (step === 0) {
      if (!form.policyNumber.trim()) m.push('your policy number');
      if (!form.claimantName.trim()) m.push('your name');
      if (form.claimantEmail && !/^\S+@\S+\.\S+$/.test(form.claimantEmail)) m.push('a valid email address');
    }
    if (step === 1) {
      if (!form.incidentDate) m.push('the date it happened');
      else if (form.incidentDate > today()) m.push('a date that is not in the future');
      if (form.description.trim().length < 15) m.push('a short description of what happened (a sentence or two)');
      if (form.claimedAmount && !(Number(form.claimedAmount) >= 0)) m.push('an estimated cost as a number');
    }
    if (step === 2 && missingAngles.length && !noMorePhotos) {
      m.push(`photos of: ${missingAngles.map((a) => a.label.toLowerCase()).join(', ')} (or tick the box below)`);
    }
    if (step === 3 && !confirmed) m.push('your confirmation that the information is correct');
    return m;
  };

  const next = () => {
    const m = check();
    if (m.length) { setProblem(`Please add ${m.join(', ')}.`); return; }
    setProblem('');
    setStep(step + 1);
    window.scrollTo(0, 0);
  };

  const onPhoto = (key: string) => async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    try {
      const uri = await readPhoto(file);
      setPhotos((p) => ({ ...p, [key]: { uri, name: file.name } }));
      setProblem('');
    } catch (err: any) {
      setProblem(err.message);
    }
  };

  const removePhoto = (key: string) => setPhotos((p) => {
    const nextPhotos = { ...p };
    delete nextPhotos[key];
    return nextPhotos;
  });

  /** Demo only: fills the form with one of the real photo sets, as a policyholder would upload it. */
  const loadSampleSet = async (folder: string) => {
    const sample = sampleSets.find((s) => s.folder === folder);
    if (!sample) return;
    setLoadingSet(true);
    setProblem('');
    try {
      const isHome = sample.line === 'HOME';
      const loaded: Record<string, Photo> = {};
      for (const a of isHome ? HOME_ANGLES : AUTO_ANGLES) {
        const name = (isHome ? HOME_SET_FILES : SET_FILES)[a.key];
        if (!name) continue;
        const res = await fetch(`/${sample.folder}/${name}`);
        const found = res.ok && (res.headers.get('content-type') ?? '').startsWith('image/');
        if (!found && !a.required) continue;
        if (!found) throw new Error(`Sample photo ${name} could not be loaded`);
        const blob = await res.blob();
        loaded[a.key] = { uri: await readPhoto(new File([blob], name, { type: blob.type || 'image/jpeg' })), name };
      }
      const base = (isHome && demoScenarios.find((s) => s.data.policyNumber.startsWith('POL-HOME'))) || demoScenarios[0];
      setForm({ ...EMPTY, policyNumber: base.data.policyNumber, claimantName: base.data.claimantName,
        state: base.data.state, incidentDate: recentDate(), description: sample.description,
        claimedAmount: sample.claimedAmount });
      setPhotos(loaded);
      setNoMorePhotos(false);
      setStep(2);
    } catch (err: any) {
      setProblem(err.message || 'The sample photos could not be loaded');
    } finally {
      setLoadingSet(false);
    }
  };

  const send = async () => {
    const m = check();
    if (m.length) { setProblem(`Please add ${m.join(', ')}.`); return; }
    setBusy(true);
    setProblem('');
    setFailure(null);
    const sent = angles.filter((a) => photos[a.key]);
    try {
      setReceipt(await portalApi.report({
        policyNumber: form.policyNumber.trim().toUpperCase(),
        claimantName: form.claimantName.trim(),
        claimantPhone: form.claimantPhone || undefined,
        claimantEmail: form.claimantEmail || undefined,
        incidentDate: form.incidentDate,
        state: form.state,
        description: form.description.trim(),
        claimedAmount: form.claimedAmount ? Number(form.claimedAmount) : undefined,
        evidenceUris: sent.map((a) => photos[a.key].uri),
        evidenceAngles: sent.map((a) => a.key),
      }));
      window.scrollTo(0, 0);
    } catch (err: any) {
      const e = err instanceof PortalError ? err : null;
      if (e?.code === 'DUPLICATE_FNOL') {
        setFailure({ title: 'This loss has already been reported',
          text: 'A claim for this policy, date and state already exists. Track it with the claim number you were '
            + 'given, or contact us if you did not report it.' });
      } else if (e?.claimId) {
        setFailure({ title: `We received your report: reference ${e.claimId}`,
          text: 'We could not open the claim automatically, so a claims specialist will contact you to complete it. '
            + 'Please keep this reference.' });
      } else if (e?.status === 400) {
        setFailure({ title: 'Some details need correcting', text: e.message });
      } else {
        setFailure({ title: 'Your claim could not be sent',
          text: 'Nothing was submitted. Please try again in a few minutes.' });
      }
    } finally {
      setBusy(false);
    }
  };

  if (receipt) {
    return (
      <div className="p-narrow">
        <Alert tone="success" title="Thank you. Your claim has been reported.">
          Please keep your claim number: <strong>{receipt.claimId}</strong>. We have also filed a written
          acknowledgement, below.
        </Alert>
        <ClaimStatus claim={receipt} />
        <div className="p-actions">
          <Link to="/track" state={{ claimId: receipt.claimId, policyNumber: form.policyNumber }}
            className="p-btn p-btn-primary">Track this claim</Link>
          <button type="button" className="p-btn p-btn-secondary" onClick={() => {
            setReceipt(null); setForm(EMPTY); setPhotos({}); setConfirmed(false); setNoMorePhotos(false); setStep(0);
          }}>Report another claim</button>
        </div>
      </div>
    );
  }

  return (
    <div className="p-narrow">
      <h1 className="p-title">Report a claim</h1>
      <ol className="p-stepper" aria-label="Steps">
        {STEPS.map((label, i) => (
          <li key={label} className={i === step ? 'current' : i < step ? 'done' : ''}
            aria-current={i === step ? 'step' : undefined}>
            <span className="p-step-no">{i < step ? '✓' : i + 1}</span>
            <span className="p-step-name">{label}</span>
          </li>
        ))}
      </ol>

      {problem && <Alert tone="warning" title={problem} />}
      {failure && <Alert tone={failure.title.startsWith('We received') ? 'info' : 'error'} title={failure.title}>{failure.text}</Alert>}

      {step === 0 && (
        <Card title="Your policy and contact details">
          <div className="p-grid">
            <Field label="Policy number" required hint="On your policy documents or insurance card, e.g. POL-AUTO-112233">
              <input name="policyNumber" value={form.policyNumber} onChange={set} autoComplete="off" />
            </Field>
            <Field label="Your full name" required hint="As it appears on the policy">
              <input name="claimantName" value={form.claimantName} onChange={set} autoComplete="name" />
            </Field>
            <Field label="Phone">
              <input type="tel" name="claimantPhone" value={form.claimantPhone} onChange={set} autoComplete="tel" />
            </Field>
            <Field label="Email">
              <input type="email" name="claimantEmail" value={form.claimantEmail} onChange={set} autoComplete="email" />
            </Field>
          </div>
          <p className="p-muted">Your adjuster uses these details only to contact you about this claim.</p>
        </Card>
      )}

      {step === 1 && (
        <Card title="What happened">
          <div className="p-grid">
            <Field label="Date it happened" required>
              <input type="date" name="incidentDate" value={form.incidentDate} max={today()} onChange={set} />
            </Field>
            <Field label="State where it happened" required>
              <select name="state" value={form.state} onChange={set}>
                {STATES.map(([code, name]) => <option key={code} value={code}>{name}</option>)}
              </select>
            </Field>
            <Field label="Describe what happened" required wide
              hint={home
                ? 'What caused the damage, which rooms or parts of the home are affected, and what you have done so far.'
                : 'Where it happened, what was damaged and how. If another driver was involved, include their plate, '
                  + 'their insurer and any police report number.'}>
              <textarea name="description" value={form.description} onChange={set} rows={6} />
            </Field>
            <Field label="Estimated cost of repair ($)" hint="A repair quote if you have one, or your best estimate.">
              <input type="number" min="0" step="1" inputMode="numeric" name="claimedAmount" value={form.claimedAmount}
                onChange={set} />
            </Field>
          </div>
        </Card>
      )}

      {step === 2 && (
        <Card title={home ? 'Photos of the damage' : 'Photos of your vehicle'}>
          <p className="p-muted">
            {home
              ? 'Please photograph the whole affected area as well as the damage itself.'
              : 'Please photograph every side of the vehicle, not only the damage: it shows the full extent of the damage.'}
            {' '}On a phone you can take each photo now.
          </p>
          <div className="p-photo-grid">
            {angles.map((a) => {
              const p = photos[a.key];
              return (
                <div key={a.key} className={`p-photo${p ? ' filled' : ''}`}>
                  <div className="p-photo-head">
                    <strong>{a.label}</strong>
                    {!a.required && <span className="p-optional"> (optional)</span>}
                    <div className="p-hint">{a.hint}</div>
                  </div>
                  {p ? <img src={p.uri} alt={`${a.label} photo`} /> : <div className="p-photo-empty" aria-hidden="true">+</div>}
                  <div className="p-photo-actions">
                    <label className="p-btn p-btn-secondary p-file">
                      {p ? 'Replace' : 'Add photo'}
                      <input type="file" accept="image/jpeg,image/png,image/webp,image/gif" capture="environment"
                        aria-label={`${a.label} photo`} onChange={onPhoto(a.key)} />
                    </label>
                    {p && <button type="button" className="p-btn p-btn-link" onClick={() => removePhoto(a.key)}>Remove</button>}
                  </div>
                </div>
              );
            })}
          </div>
          {missingAngles.length > 0 && (
            <label className="p-check">
              <input type="checkbox" checked={noMorePhotos} onChange={(e) => setNoMorePhotos(e.target.checked)} />
              <span>I can't take some of these photos now. (Your adjuster will send you a secure link to add them later.)</span>
            </label>
          )}
        </Card>
      )}

      {step === 3 && (
        <>
          <Card title="Check your details">
            <dl className="p-summary">
              <dt>Policy number</dt><dd>{form.policyNumber.toUpperCase()}</dd>
              <dt>Name</dt><dd>{form.claimantName}</dd>
              {form.claimantPhone && <><dt>Phone</dt><dd>{form.claimantPhone}</dd></>}
              {form.claimantEmail && <><dt>Email</dt><dd>{form.claimantEmail}</dd></>}
              <dt>Date</dt><dd>{longDate(form.incidentDate)}</dd>
              <dt>State</dt><dd>{STATES.find(([c]) => c === form.state)?.[1]}</dd>
              <dt>Estimated cost</dt><dd>{form.claimedAmount ? `$${Number(form.claimedAmount).toLocaleString('en-US')}` : 'Not given'}</dd>
              <dt>Photos</dt>
              <dd>{Object.keys(photos).length} added{missingAngles.length ? ` (still to come: ${missingAngles.map((a) => a.label.toLowerCase()).join(', ')})` : ''}</dd>
              <dt>What happened</dt><dd className="p-pre">{form.description}</dd>
            </dl>
            <button type="button" className="p-btn p-btn-link" onClick={() => setStep(0)}>Change details</button>
          </Card>
          <Card title="Before you send">
            <p className="p-muted">We use automated tools to read your photos and description so that your claim reaches
              the right adjuster faster. Faces and number plates are blurred before photos are analysed. A licensed
              claims adjuster reviews every claim and makes every decision.</p>
            <label className="p-check">
              <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
              <span>The information I have given is true and complete to the best of my knowledge.</span>
            </label>
          </Card>
        </>
      )}

      <div className="p-actions">
        {step > 0 && (
          <button type="button" className="p-btn p-btn-secondary" disabled={busy}
            onClick={() => { setProblem(''); setStep(step - 1); }}>Back</button>
        )}
        {step < STEPS.length - 1 && <button type="button" className="p-btn p-btn-primary" onClick={next}>Continue</button>}
        {step === STEPS.length - 1 && (
          <button type="button" className="p-btn p-btn-primary" disabled={busy} onClick={send}>
            {busy ? 'Sending your claim…' : 'Send my claim'}
          </button>
        )}
      </div>
      {busy && <p className="p-muted p-center">This usually takes a few seconds. Please keep this page open.</p>}

      {step < 3 && (
        <details className="p-demo">
          <summary>Demo tools</summary>
          <label className="p-field">
            <span className="p-label">Fill the form with a real photo set</span>
            <select defaultValue="" disabled={loadingSet}
              onChange={(e) => { loadSampleSet(e.target.value); e.target.value = ''; }}>
              <option value="" disabled>{loadingSet ? 'Loading photos…' : 'Choose…'}</option>
              <optgroup label="Vehicles (auto policy)">
                {vehicleSampleSets.map((s) => <option key={s.folder} value={s.folder}>{s.label}</option>)}
              </optgroup>
              <optgroup label="Homes (homeowners policy)">
                {homeSampleSets.map((s) => <option key={s.folder} value={s.folder}>{s.label}</option>)}
              </optgroup>
            </select>
          </label>
          <p className="p-hint">Seeded policies: POL-AUTO-112233 (CA), POL-AUTO-223344 (NY), POL-AUTO-445566 (CA),
            POL-HOME-334455 (TX). Photos: NHTSA crash tests and FEMA / public-domain home damage.</p>
        </details>
      )}
    </div>
  );
}
