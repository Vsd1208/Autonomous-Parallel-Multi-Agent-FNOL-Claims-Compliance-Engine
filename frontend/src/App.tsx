import Login from './pages/Login';
import { session } from './services/session';
import React from 'react';
import { BrowserRouter as Router, Navigate, Route, Routes, useParams } from 'react-router-dom';
import './style.css';
import { AppHeader } from './components/Shell';
import ClaimFile from './pages/ClaimFile';
import Desktop from './pages/Desktop';
import NewClaimWizard from './pages/NewClaimWizard';
import Governance from './pages/Governance';
import Operations from './pages/Operations';
import { RoleGuard } from './components/RoleGuard';

function AuditRedirect() {
  const { claimId } = useParams<{ claimId: string }>();
  return <Navigate to={`/claim/${claimId}/history`} replace />;
}

const PORTAL_URL = (import.meta.env.VITE_PORTAL_URL || 'http://localhost:5174').replace(/\/+$/, '');

/** Policyholder pages live in the separate portal app; an old upload link on the workspace is sent there. */
function PortalRedirect() {
  const { token = '' } = useParams<{ token: string }>();
  React.useEffect(() => { window.location.replace(`${PORTAL_URL}/upload/${encodeURIComponent(token)}`); }, [token]);
  return null;
}

function App() {
  return (
    <Router>
      <Routes>
        <Route path="/upload/:token" element={<PortalRedirect />} />
        <Route path="*" element={<AdjusterWorkspace />} />
      </Routes>
    </Router>
  );
}

function AdjusterWorkspace() {
  const [signedIn, setSignedIn] = React.useState(session.get());
  if (!signedIn) return <Login onSignedIn={setSignedIn} />;
  return (
      <div className="app">
        <AppHeader onSignedOut={() => setSignedIn(null)} />
        <Routes>
          <Route path="/" element={<Desktop />} />
          <Route path="/queue/:view" element={<Desktop />} />
          <Route path="/queue" element={<Navigate to="/" replace />} />
          <Route path="/new" element={<RoleGuard area="NEW_CLAIM"><NewClaimWizard /></RoleGuard>} />
          <Route path="/governance" element={<RoleGuard area="GOVERNANCE"><Governance /></RoleGuard>} />
          <Route path="/operations" element={<RoleGuard area="OPERATIONS"><Operations /></RoleGuard>} />
          <Route path="/claim/:claimId" element={<ClaimFile />} />
          <Route path="/claim/:claimId/:section" element={<ClaimFile />} />
          <Route path="/audit/:claimId" element={<AuditRedirect />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
        <footer className="app-footer">
          AI provides decision support only. Final decisions are made by licensed adjusters.
        </footer>
      </div>
  );
}

export default App;
