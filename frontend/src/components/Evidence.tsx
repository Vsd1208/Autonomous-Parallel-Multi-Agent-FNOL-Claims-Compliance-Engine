import React, { useEffect, useRef, useState } from 'react';
import { apiClient } from '../services/apiClient';
import { ClaimDocument } from '../types';
import { Button, DetailView, dateTime } from './ui';

/** Loads a protected evidence image (adjuster token) and shows it; frees the object URL on unmount. */
export function EvidenceImage({ doc, className, onClick }: {
  doc: ClaimDocument; className?: string; onClick?: () => void;
}) {
  const [url, setUrl] = useState('');
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let objectUrl = '';
    let cancelled = false;
    setUrl('');
    setFailed(false);
    if (!doc.viewable) return undefined;
    apiClient.getDocumentImageUrl(doc.claimId, doc.documentId)
      .then((u) => {
        objectUrl = u;
        if (cancelled) URL.revokeObjectURL(u);
        else setUrl(u);
      })
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [doc.claimId, doc.documentId, doc.viewable]);

  if (!doc.viewable) {
    return (
      <div className={`evidence-placeholder ${className ?? ''}`} title={doc.sourceUri ?? ''}>
        <span>{doc.source === 'MOCK' ? 'Demo reference' : 'Linked file'}</span>
        <small>{doc.name}</small>
      </div>
    );
  }
  if (failed) return <div className={`evidence-placeholder ${className ?? ''}`}><span>Image unavailable</span></div>;
  if (!url) return <div className={`evidence-placeholder loading ${className ?? ''}`}><span>Loading…</span></div>;
  const img = <img src={url} alt={`Evidence ${doc.name}`} className={className} />;
  return onClick
    ? <button type="button" className="evidence-button" onClick={onClick} aria-label={`View ${doc.name} full size`}>{img}</button>
    : img;
}

/** Full-size viewer for one document, with its integrity details. Closes on Escape or backdrop click. */
export function EvidenceViewer({ doc, onClose }: { doc: ClaimDocument; onClose: () => void }) {
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className="viewer-backdrop" onClick={onClose} role="presentation">
      <div className="viewer" role="dialog" aria-modal="true" aria-label={doc.name} onClick={(e) => e.stopPropagation()}>
        <header className="panel-title">
          <h2>{doc.name}</h2>
          <button type="button" ref={closeRef} className="btn btn-secondary" onClick={onClose}>Close</button>
        </header>
        <div className="viewer-body">
          <div className="viewer-image"><EvidenceImage doc={doc} /></div>
          <aside className="viewer-meta">
            <DetailView rows={[
              ['Source', doc.source === 'UPLOAD' ? 'Uploaded at intake' : doc.source === 'MOCK' ? 'Demo reference' : 'Linked URL'],
              ['Type', doc.contentType],
              ['Size', doc.sizeBytes != null ? `${(doc.sizeBytes / 1024).toFixed(1)} KB` : null],
              ['Received', dateTime(doc.uploadedAt)],
              ['Reference', doc.sourceUri],
            ]} />
            <div className="field-label" style={{ marginTop: 10 }}>SHA-256 (recorded in the audit chain)</div>
            <code className="sha">{doc.sha256 ?? '—'}</code>
          </aside>
        </div>
      </div>
    </div>
  );
}

/** Thumbnail grid; clicking one opens the viewer. */
export function EvidenceGallery({ docs, empty }: { docs: ClaimDocument[]; empty?: string }) {
  const [open, setOpen] = useState<ClaimDocument | null>(null);
  if (docs.length === 0) return <p className="muted">{empty ?? 'No evidence was submitted with this claim.'}</p>;
  return (
    <>
      <div className="evidence-grid">
        {docs.map((d) => (
          <figure key={d.documentId} className="evidence-card">
            <EvidenceImage doc={d} className="evidence-thumb" onClick={() => setOpen(d)} />
            <figcaption>
              <span className="evidence-name">{d.name}</span>
              <Button onClick={() => setOpen(d)}>View</Button>
            </figcaption>
          </figure>
        ))}
      </div>
      {open && <EvidenceViewer doc={open} onClose={() => setOpen(null)} />}
    </>
  );
}
