import React from 'react';
import { BrowserRouter as Router, Navigate, Route, Routes, useParams } from 'react-router-dom';
import './style.css';
import { AppHeader } from './components/Shell';
import ClaimFile from './pages/ClaimFile';
import Desktop from './pages/Desktop';
import NewClaimWizard from './pages/NewClaimWizard';

function AuditRedirect() {
  const { claimId } = useParams<{ claimId: string }>();
  return <Navigate to={`/claim/${claimId}/history`} replace />;
}

function App() {
  return (
    <Router>
      <div className="app">
        <AppHeader />
        <Routes>
          <Route path="/" element={<Desktop />} />
          <Route path="/queue/:view" element={<Desktop />} />
          <Route path="/queue" element={<Navigate to="/" replace />} />
          <Route path="/new" element={<NewClaimWizard />} />
          <Route path="/claim/:claimId" element={<ClaimFile />} />
          <Route path="/claim/:claimId/:section" element={<ClaimFile />} />
          <Route path="/audit/:claimId" element={<AuditRedirect />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
        <footer className="app-footer">
          AI provides decision support only. Final decisions are made by licensed adjusters.
        </footer>
      </div>
    </Router>
  );
}

export default App;
