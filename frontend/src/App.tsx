import React, { useState } from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import './style.css';
import ClaimDashboard from './pages/ClaimDashboard';
import AuditLog from './pages/AuditLog';
import { apiClient } from './services/apiClient';
import { ClaimResult, FormData } from './types';

const demoScenarios = [
  {
    name: 'Clean STP',
    data: {
      policyNumber: 'POL-AUTO-112233',
      incidentDate: '2026-09-15',
      state: 'CA',
      description: 'Rear-ended at traffic light. Other driver admitted fault. Police report filed.',
      claimantName: 'Avery Johnson',
      claimantDob: '01/02/1980',
      claimantSsn: '123-45-6789',
    },
  },
  {
    name: 'Low Confidence',
    data: {
      policyNumber: 'POL-AUTO-223344',
      incidentDate: '2026-09-14',
      state: 'NY',
      description: 'Blurry photo evidence. Need manual review.',
      claimantName: 'Jane Doe',
      claimantDob: '05/10/1985',
      claimantSsn: '987-65-4321',
    },
  },
  {
    name: 'Compliance Block',
    data: {
      policyNumber: 'POL-AUTO-334455',
      incidentDate: '2026-09-13',
      state: 'TX',
      description: 'Medical Record #MR-2026-12345 attached.',
      claimantName: 'John Smith',
      claimantDob: '03/20/1975',
      claimantSsn: '555-66-7777',
    },
  },
  {
    name: 'Timeout Test',
    data: {
      policyNumber: 'POL-AUTO-445566',
      incidentDate: '2026-09-12',
      state: 'FL',
      description: 'Testing timeout scenario.',
      claimantName: 'Bob Wilson',
      claimantDob: '12/31/1980',
      claimantSsn: '111-22-3333',
    },
  },
  {
    name: 'Duplicate Test',
    data: {
      policyNumber: 'POL-AUTO-556677',
      incidentDate: '2026-09-11',
      state: 'IL',
      description: 'Duplicate submission test.',
      claimantName: 'Alice Brown',
      claimantDob: '07/15/1990',
      claimantSsn: '444-55-6666',
    },
  },
];

const mockClaim: ClaimResult = {
  claimId: 'CLM-2026-000001',
  status: 'OPEN',
  branchA: {
    damageAssessment: {
      damageType: 'FRONT_BUMPER',
      severity: 'MODERATE',
      estimatedDamage: 4200.0,
      confidence: 0.91,
    },
    coverage: {
      covered: true,
      coverageType: 'COLLISION',
      coverageLimit: 50000.0,
      deductible: 1000.0,
    },
    reserve: {
      estimatedDamage: 4200.0,
      factor: 1.15,
      recommendedReserve: 4830.0,
    },
  },
  branchB: {
    pii: {
      piiDetected: true,
      redactedDescription: 'Rear-ended at traffic light. [NAME] admitted fault. Police report filed.',
      detectedTypes: ['SSN', 'NAME', 'DOB'],
    },
    deadline: {
      state: 'CA',
      deadline: '2026-10-15',
      ruleSource: 'CA Insurance Code §2695.7',
      disclaimer: 'Failure to acknowledge within 10 days constitutes unfair practice.',
    },
    subrogation: {
      score: 78,
      recommendedAction: 'INVESTIGATE',
      factors: {
        thirdPartyMention: 35,
        policeReportSignal: 20,
        damageSeverity: 23,
      },
    },
    audit: [
      {
        agentName: 'VisionDamageAgent',
        status: 'SUCCESS',
        timestamp: '2026-09-15T10:45:00Z',
      },
      {
        agentName: 'PolicyValidatorAgent',
        status: 'SUCCESS',
        timestamp: '2026-09-15T10:45:01Z',
      },
      {
        agentName: 'ReserveCalculatorAgent',
        status: 'SUCCESS',
        timestamp: '2026-09-15T10:45:02Z',
      },
      {
        agentName: 'PiiRedactionAgent',
        status: 'SUCCESS',
        timestamp: '2026-09-15T10:45:02Z',
      },
      {
        agentName: 'StatutoryDeadlineAgent',
        status: 'SUCCESS',
        timestamp: '2026-09-15T10:45:03Z',
      },
      {
        agentName: 'SubrogationScorerAgent',
        status: 'SUCCESS',
        timestamp: '2026-09-15T10:45:03Z',
      },
    ],
  },
  explanation: {
    decision: 'ADVISORY_RECOMMENDED_ACTION',
    summary: 'All criteria met for advisory-guided pre-population of low-severity claim',
    recommendedAction: 'PREFILL_LOW_SEVERITY_CLAIM',
    severityGate: 'LOW',
    factors: [
      {
        factor: 'AI Confidence',
        value: '0.91',
        status: 'PASS',
        detail: 'Confidence score must be >= 0.85',
      },
      {
        factor: 'Coverage Status',
        value: 'COLLISION',
        status: 'PASS',
        detail: 'Relevant coverage must be active and matched',
      },
    ],
  },
  notes: [
    'AI-assisted advisory copilot only; human claim handlers retain final authority.',
    'Coverage verified by Guidewire PolicyCenter deterministic rules engine.',
  ],
};

function IntakeForm() {
  const [lastClaim, setLastClaim] = useState<ClaimResult | null>(() => {
    const stored = window.localStorage.getItem('fnol:lastClaim');
    return stored ? JSON.parse(stored) : null;
  });
  const [form, setForm] = useState<FormData>({
    policyNumber: '',
    incidentDate: '',
    state: 'CA',
    description: '',
    claimantName: '',
    claimantDob: '',
    claimantSsn: '',
  });

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<ClaimResult | null>(null);

  const storeResult = (claim: ClaimResult) => {
    window.localStorage.setItem('fnol:lastClaim', JSON.stringify(claim));
    setLastClaim(claim);
    setResult(claim);
  };

  const handleInputChange = (
    e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>
  ) => {
    setForm({
      ...form,
      [e.target.name]: e.target.value,
    });
  };

  const handleQuickLoad = (scenario: (typeof demoScenarios)[0]) => {
    setForm(scenario.data);
    setError('');
    setResult(null);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');

    try {
      const response = await apiClient.submitFNOL({
        ...form,
        evidenceUris: ['mock://photo/front-bumper.jpg'],
      });
      storeResult(response);
    } catch (err: any) {
      setError(err.message || 'Failed to submit FNOL');
    } finally {
      setLoading(false);
    }
  };

  if (result) {
    return <ClaimDashboard claim={result} />;
  }

  return (
    <div className="intake-page">
      <div className="intake-container">
        <header className="intake-header">
          <p className="eyebrow">FNOL INTAKE</p>
          <h1>Adjuster Intelligence Console</h1>
          <p>AI-Assisted Claims Decision Support with Guidewire Integration</p>
        </header>

        <div className="quick-load-section">
          <h2>Quick-Load Demo Scenarios</h2>
          <div className="button-grid">
            {demoScenarios.map((scenario, index) => (
              <button
                key={index}
                type="button"
                onClick={() => handleQuickLoad(scenario)}
                className="quick-load-btn"
              >
                {scenario.name}
              </button>
            ))}
          </div>
          {lastClaim && (
            <button type="button" onClick={() => setResult(lastClaim)} className="resume-btn">
              Resume Last Claim {lastClaim.claimId}
            </button>
          )}
        </div>

        <form onSubmit={handleSubmit} className="intake-form">
          <div className="form-section">
            <h2>Claim Details</h2>

            <div className="form-grid">
              <div className="form-field">
                <label>Policy Number</label>
                <input
                  type="text"
                  name="policyNumber"
                  value={form.policyNumber}
                  onChange={handleInputChange}
                  placeholder="POL-AUTO-112233"
                  required
                />
              </div>

              <div className="form-field">
                <label>Incident Date</label>
                <input
                  type="date"
                  name="incidentDate"
                  value={form.incidentDate}
                  onChange={handleInputChange}
                  required
                />
              </div>

              <div className="form-field">
                <label>State</label>
                <select
                  name="state"
                  value={form.state}
                  onChange={handleInputChange}
                  required
                >
                  <option value="CA">California</option>
                  <option value="NY">New York</option>
                  <option value="TX">Texas</option>
                  <option value="FL">Florida</option>
                  <option value="IL">Illinois</option>
                </select>
              </div>

              <div className="form-field">
                <label>Claimant Name</label>
                <input
                  type="text"
                  name="claimantName"
                  value={form.claimantName}
                  onChange={handleInputChange}
                  placeholder="Avery Johnson"
                  required
                />
              </div>

              <div className="form-field">
                <label>Claimant DOB</label>
                <input
                  type="text"
                  name="claimantDob"
                  value={form.claimantDob}
                  onChange={handleInputChange}
                  placeholder="01/02/1980"
                  required
                />
              </div>

              <div className="form-field">
                <label>Claimant SSN</label>
                <input
                  type="text"
                  name="claimantSsn"
                  value={form.claimantSsn}
                  onChange={handleInputChange}
                  placeholder="123-45-6789"
                  required
                />
              </div>
            </div>

            <div className="form-field full-width">
              <label>Description</label>
              <textarea
                name="description"
                value={form.description}
                onChange={handleInputChange}
                rows={5}
                placeholder="Describe the incident in detail..."
                required
              />
            </div>
          </div>

          {error && <div className="error-box">❌ {error}</div>}

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
        <Route path="/claim/:claimId" element={<ClaimDashboard claim={mockClaim} />} />
        <Route path="/audit/:claimId" element={<AuditLog />} />
      </Routes>
    </Router>
  );
}

export default App;
