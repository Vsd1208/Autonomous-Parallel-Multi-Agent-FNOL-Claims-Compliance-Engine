import React from 'react';
import { BrowserRouter as Router, Link, NavLink, Navigate, Route, Routes } from 'react-router-dom';
import './portal.css';
import Home from './pages/Home';
import Report from './pages/Report';
import Track from './pages/Track';
import Upload from './pages/Upload';

/**
 * The policyholder's FNOL portal: report a loss, track a claim, and send photos an adjuster asked for. A separate
 * application from the adjuster workspace, with its own build and its own API (no adjuster token, no AI internals).
 */
export default function PortalApp() {
  return (
    <Router>
      <div className="p-app">
        <header className="p-header">
          <div className="p-header-inner">
            <Link to="/" className="p-brand" aria-label="Claims Portal home">
              <svg viewBox="0 0 32 32" width="28" height="28" aria-hidden="true">
                <path d="M16 3l11 4v8c0 7-4.7 12.3-11 14C9.7 27.3 5 22 5 15V7z" fill="currentColor" />
                <path d="M11 16l3.5 3.5L21 13" stroke="var(--p-header)" strokeWidth="2.6" fill="none"
                  strokeLinecap="round" strokeLinejoin="round" />
              </svg>
              <span>Claims Portal</span>
            </Link>
            <nav className="p-nav" aria-label="Main">
              <NavLink to="/report">Report a claim</NavLink>
              <NavLink to="/track">Track a claim</NavLink>
            </nav>
          </div>
        </header>
        <main className="p-main">
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/report" element={<Report />} />
            <Route path="/track" element={<Track />} />
            <Route path="/upload/:token" element={<Upload />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </main>
        <footer className="p-footer">
          Demonstration portal for the Autonomous Parallel Multi-Agent FNOL Claims Compliance Engine. Every claim is
          decided by a licensed claims adjuster.
        </footer>
      </div>
    </Router>
  );
}
