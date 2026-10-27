import React, { useState } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { claimStore } from '../services/claimStore';

/** Top header with product tabs and a claim-number search, as in a claims-system workspace. */
export function AppHeader() {
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const lastClaimId = claimStore.lastClaimId();

  const goToClaim = (e: React.FormEvent) => {
    e.preventDefault();
    const id = search.trim().toUpperCase();
    if (id) {
      navigate(`/claim/${encodeURIComponent(id)}`);
      setSearch('');
    }
  };

  return (
    <header className="app-header">
      <div className="brand" aria-label="FNOL Claims Workspace">
        <span className="brand-mark" aria-hidden="true">F</span>
        <span className="brand-name">FNOL Claims Workspace</span>
      </div>
      <nav className="header-tabs" aria-label="Main">
        <NavLink to="/" end className={({ isActive }) => (isActive ? 'tab active' : 'tab')}>Desktop</NavLink>
        {lastClaimId && (
          <NavLink to={`/claim/${lastClaimId}`} className={({ isActive }) => (isActive ? 'tab active' : 'tab')}>
            Claim {lastClaimId}
          </NavLink>
        )}
        <NavLink to="/new" className={({ isActive }) => (isActive ? 'tab active' : 'tab')}>New Claim</NavLink>
      </nav>
      <form className="header-search" onSubmit={goToClaim} role="search">
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Claim #"
          aria-label="Go to claim number"
        />
        <button type="submit">Go</button>
      </form>
      <div className="header-user" title="Signed-in adjuster (prototype: shared adjuster token)">
        <span className="avatar" aria-hidden="true">A</span>
        Adjuster
      </div>
    </header>
  );
}

export type SideItem = { to: string; label: string; badge?: string | number; end?: boolean };

/** Left navigation: an optional Actions button and a list of links. */
export function Sidebar({ title, actions, items }: { title?: string; actions?: React.ReactNode; items: SideItem[] }) {
  return (
    <aside className="sidebar">
      {actions && <div className="sidebar-actions">{actions}</div>}
      {title && <div className="sidebar-title">{title}</div>}
      <nav>
        {items.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.end}
            className={({ isActive }) => (isActive ? 'side-link active' : 'side-link')}
          >
            <span>{item.label}</span>
            {item.badge !== undefined && item.badge !== '' && <span className="side-badge">{item.badge}</span>}
          </NavLink>
        ))}
      </nav>
    </aside>
  );
}

/** The grey strip under the header that identifies the open claim. */
export function InfoBar({ items }: { items: [string, React.ReactNode][] }) {
  return (
    <div className="info-bar">
      {items.map(([label, value]) => (
        <div className="info-item" key={label}>
          <span className="info-label">{label}</span>
          <span className="info-value">{value}</span>
        </div>
      ))}
    </div>
  );
}

/** Dark left navigation, then the content column: an optional info bar above the page. */
export function Workspace({ sidebar, bar, children }: { sidebar: React.ReactNode; bar?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="workspace">
      {sidebar}
      <div className="workspace-col">
        {bar}
        <main className="workspace-main">{children}</main>
      </div>
    </div>
  );
}

export function PageTitle({ title, subtitle }: { title: string; subtitle?: string }) {
  return (
    <div className="page-title">
      <h1>{title}</h1>
      {subtitle && <p>{subtitle}</p>}
    </div>
  );
}
