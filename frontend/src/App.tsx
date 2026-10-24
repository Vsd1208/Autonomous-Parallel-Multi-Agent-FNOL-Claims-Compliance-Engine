import React, { useState } from 'react';
import { BrowserRouter as Router, Link, Route, Routes, useNavigate } from 'react-router-dom';
import './style.css';
import ClaimDashboard, { ClaimRoute, fromIntake } from './pages/ClaimDashboard';
import AuditLog from './pages/AuditLog';
import ClaimQueue from './pages/ClaimQueue';
import { ApiError, apiClient } from './services/apiClient';
import { claimStore } from './services/claimStore';
import { ClaimResult, FormData } from './types';

/** A recent incident date that is unique per load, so re-running a scenario is not a duplicate. */
function recentDate(): string {
  const daysAgo = 31 + Math.floor(Math.random() * 120);
  const d = new Date(Date.now() - daysAgo * 86_400_000);
  return d.toISOString().slice(0, 10);
}

type Scenario = { name: string; evidenceUri: string; data: Omit<FormData, 'incidentDate'> };

const MAX_PHOTO_BYTES = 5 * 1024 * 1024;
const insured = {
  claimantDob: '01/02/1980',
  claimantSsn: '123-45-6789',
  claimantPhone: '',
  claimantEmail: '',
  channel: 'WEB' as const,
};

// Mirrors demo-scenarios/*.json; mock:// photo keywords drive MockVisionProvider (GEMINI_VISION_ENABLED=false).
const demoScenarios: Scenario[] = [
  {
    name: 'Clean STP',
    evidenceUri: 'mock://photo/normal-front-bumper.jpg',
    data: { ...insured, policyNumber: 'POL-AUTO-112233', state: 'CA', claimantName: 'Avery Johnson',
      description: 'Vehicle involved in a minor collision. Front bumper damaged.' },
  },
  {
    name: 'Low Confidence',
    evidenceUri: 'mock://photo/blurry-damage.jpg',
    data: { ...insured, policyNumber: 'POL-AUTO-112233', state: 'CA', claimantName: 'Avery Johnson',
      description: 'Vehicle involved in a collision. Front bumper damaged.' },
  },
  {
    name: 'Compliance Block',
    evidenceUri: 'mock://photo/normal-damage.jpg',
    data: { ...insured, policyNumber: 'POL-AUTO-112233', state: 'CA', claimantName: 'Avery Johnson',
      description: 'Vehicle collision. Patient medical record number MRN-123456 is included in the report.' },
  },
  {
    name: 'Timeout',
    evidenceUri: 'mock://photo/slow-damage.jpg',
    data: { ...insured, policyNumber: 'POL-AUTO-112233', state: 'CA', claimantName: 'Avery Johnson',
      description: 'Vehicle involved in a collision. Front bumper damaged.' },
  },
  {
    name: 'High Claim Frequency',
    evidenceUri: 'mock://photo/normal-rear-bumper.jpg',
    data: { ...insured, policyNumber: 'POL-AUTO-445566', state: 'CA', claimantName: 'Jordan Rivera',
      description: 'Scraped a pole while parking. Rear bumper dented.' },
  },
  {
    name: 'Home Policy',
    evidenceUri: 'mock://photo/water-damage.jpg',
    data: { ...insured, policyNumber: 'POL-HOME-334455', state: 'TX', claimantName: 'Riley Smith',
      description: 'Kitchen pipe burst and caused water damage to the floor and cabinets.' },
  },
];

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

function IntakeForm() {
  const navigate = useNavigate();
  const lastClaimId = claimStore.lastClaimId();
  const [form, setForm] = useState<FormData>(emptyForm);
  const [evidenceUri, setEvidenceUri] = useState('mock://photo/normal-front-bumper.jpg');
  const [photoName, setPhotoName] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [escalatedClaimId, setEscalatedClaimId] = useState('');
  const [result, setResult] = useState<ClaimResult | null>(null);

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => {
    setForm({ ...form, [e.target.name]: e.target.value });
  };

  const handlePhoto = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      setEvidenceUri(await readPhoto(file));
      setPhotoName(file.name);
      setError('');
    } catch (err: any) {
      setError(err.message);
    }
  };

  const handleQuickLoad = (scenario: Scenario) => {
    setForm({ ...scenario.data, incidentDate: recentDate() });
    setEvidenceUri(scenario.evidenceUri);
    setPhotoName('');
    setError('');
    setEscalatedClaimId('');
    setResult(null);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');
    setEscalatedClaimId('');
    try {
      const response = await apiClient.submitFNOL({
        ...form,
        claimantPhone: form.claimantPhone || undefined,
        claimantEmail: form.claimantEmail || undefined,
        evidenceUris: evidenceUri.trim() ? [evidenceUri.trim()] : [],
      });
      claimStore.save(response);
      setResult(response);
    } catch (err: any) {
      setError(err.message || 'Failed to submit FNOL');
      if (err instanceof ApiError && err.claimId) setEscalatedClaimId(err.claimId);
    } finally {
      setLoading(false);
    }
  };

  if (result) {
    return <ClaimDashboard claim={fromIntake(result)} />;
  }

  return (
    <div className="intake-page">
      <div className="intake-container">
        <header className="intake-header">
          <p className="eyebrow">FNOL INTAKE</p>
          <h1>Adjuster Intelligence Console</h1>
          <p>AI-Assisted Claims Decision Support with Guidewire Integration</p>
          <p>
            <Link to="/queue">Open the adjuster queue →</Link>
          </p>
        </header>

        <div className="quick-load-section">
          <h2>Quick-Load Demo Scenarios</h2>
          <div className="button-grid">
            {demoScenarios.map((scenario) => (
              <button key={scenario.name} type="button" onClick={() => handleQuickLoad(scenario)} className="quick-load-btn">
                {scenario.name}
              </button>
            ))}
          </div>
          {lastClaimId && (
            <button type="button" onClick={() => navigate(`/claim/${lastClaimId}`)} className="resume-btn">
              Resume Last Claim {lastClaimId}
            </button>
          )}
        </div>

        <form onSubmit={handleSubmit} className="intake-form">
          <div className="form-section">
            <h2>Claim Details</h2>

            <div className="form-grid">
              <div className="form-field">
                <label>Policy Number</label>
                <input type="text" name="policyNumber" value={form.policyNumber} onChange={handleInputChange}
                  placeholder="POL-AUTO-112233" required />
              </div>
              <div className="form-field">
                <label>Incident Date</label>
                <input type="date" name="incidentDate" value={form.incidentDate} onChange={handleInputChange} required />
              </div>
              <div className="form-field">
                <label>State</label>
                <select name="state" value={form.state} onChange={handleInputChange} required>
                  <option value="CA">California</option>
                  <option value="NY">New York</option>
                  <option value="TX">Texas</option>
                  <option value="FL">Florida</option>
                  <option value="IL">Illinois</option>
                </select>
              </div>
              <div className="form-field">
                <label>Intake Channel</label>
                <select name="channel" value={form.channel} onChange={handleInputChange} required>
                  <option value="WEB">Web portal</option>
                  <option value="MOBILE">Mobile app</option>
                  <option value="PHONE">Phone (entered by agent)</option>
                  <option value="API">API / partner</option>
                </select>
              </div>
              <div className="form-field">
                <label>Claimant Name</label>
                <input type="text" name="claimantName" value={form.claimantName} onChange={handleInputChange}
                  placeholder="Avery Johnson" required />
              </div>
              <div className="form-field">
                <label>Claimant DOB</label>
                <input type="text" name="claimantDob" value={form.claimantDob} onChange={handleInputChange}
                  placeholder="01/02/1980" />
              </div>
              <div className="form-field">
                <label>Claimant SSN</label>
                <input type="text" name="claimantSsn" value={form.claimantSsn} onChange={handleInputChange}
                  placeholder="123-45-6789" />
              </div>
              <div className="form-field">
                <label>Claimant Phone (optional)</label>
                <input type="tel" name="claimantPhone" value={form.claimantPhone} onChange={handleInputChange}
                  placeholder="555-0100" />
              </div>
              <div className="form-field">
                <label>Claimant Email (optional)</label>
                <input type="email" name="claimantEmail" value={form.claimantEmail} onChange={handleInputChange}
                  placeholder="avery@example.com" />
              </div>
            </div>

            <div className="form-field full-width">
              <label>Damage Photo</label>
              <input type="file" accept="image/jpeg,image/png,image/webp,image/gif" onChange={handlePhoto} />
              {photoName && <p className="muted">Uploaded {photoName} (sent inline to the vision model)</p>}
            </div>

            <div className="form-field full-width">
              <label>…or Evidence Photo URI</label>
              <input type="text" value={photoName ? '' : evidenceUri}
                onChange={(e) => { setEvidenceUri(e.target.value); setPhotoName(''); }}
                placeholder="mock://photo/normal.jpg (demo) or an allow-listed https URL" />
            </div>

            <div className="form-field full-width">
              <label>Description</label>
              <textarea name="description" value={form.description} onChange={handleInputChange} rows={5}
                placeholder="Describe the incident in detail..." required />
            </div>
          </div>

          {error && (
            <div className="error-box">
              ❌ {error}
              {escalatedClaimId && (
                <>
                  {' '}Saved for human attention as <Link to={`/claim/${escalatedClaimId}`}>{escalatedClaimId}</Link>.
                </>
              )}
            </div>
          )}

          <button type="submit" className="submit-btn" disabled={loading}>
            {loading ? 'Processing FNOL...' : 'Submit FNOL'}
          </button>
        </form>
      </div>
    </div>
  );
}

function App() {
  return (
    <Router>
      <Routes>
        <Route path="/" element={<IntakeForm />} />
        <Route path="/queue" element={<ClaimQueue />} />
        <Route path="/claim/:claimId" element={<ClaimRoute />} />
        <Route path="/audit/:claimId" element={<AuditLog />} />
      </Routes>
    </Router>
  );
}

export default App;
