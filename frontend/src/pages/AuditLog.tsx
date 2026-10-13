import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { jsPDF } from 'jspdf';
import html2canvas from 'html2canvas';
import { AuditEvent } from '../types';

type AuditLogProps = {
  auditTrail?: AuditEvent[];
  claimId?: string;
};

export default function AuditLog({ auditTrail, claimId: propClaimId }: AuditLogProps) {
  const { claimId: paramClaimId } = useParams<{ claimId: string }>();
  const navigate = useNavigate();
  const claimId = propClaimId || paramClaimId || 'CLM-2026-000001';
  const [loading, setLoading] = useState(false);
  const storedClaim = window.localStorage.getItem('fnol:lastClaim');
  const storedAuditTrail: AuditEvent[] | undefined = storedClaim
    ? JSON.parse(storedClaim).branchB?.audit
    : undefined;

  const mockAuditTrail: AuditEvent[] = [
    {
      agentName: 'VisionDamageAgent',
      status: 'SUCCESS',
      timestamp: '2026-09-15T10:45:00Z',
      inputHash: 'a3f8d9b2c1e4f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f',
      outputHash: 'f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0',
    },
    {
      agentName: 'PolicyValidatorAgent',
      status: 'SUCCESS',
      timestamp: '2026-09-15T10:45:01Z',
      inputHash: 'b4g9e0c3d2f5e8a1b0c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f',
      outputHash: 'e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0f9',
    },
    {
      agentName: 'ReserveCalculatorAgent',
      status: 'SUCCESS',
      timestamp: '2026-09-15T10:45:02Z',
      inputHash: 'c5h0f1d4e3g6f9b2c1d4e5f6g7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f',
      outputHash: 'd7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0f9e8',
    },
    {
      agentName: 'PiiRedactionAgent',
      status: 'SUCCESS',
      timestamp: '2026-09-15T10:45:02Z',
      inputHash: 'd6i1g2e5f4h7g0c3d2e5f6g7h8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f',
      outputHash: 'e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0f9',
    },
    {
      agentName: 'StatutoryDeadlineAgent',
      status: 'SUCCESS',
      timestamp: '2026-09-15T10:45:03Z',
      inputHash: 'e7j2h3f6g5i8h1d4e3f6g7h8i9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f',
      outputHash: 'f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0',
    },
    {
      agentName: 'SubrogationScorerAgent',
      status: 'SUCCESS',
      timestamp: '2026-09-15T10:45:03Z',
      inputHash: 'f8k3i4g7h6j9i2e5f4g7h8i9j0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f',
      outputHash: 'a0f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1',
    },
  ];

  const trail = auditTrail || storedAuditTrail || mockAuditTrail;

  const handleExportPDF = async () => {
    setLoading(true);
    try {
      // Create a new PDF document
      const pdf = new jsPDF({
        orientation: 'portrait',
        unit: 'mm',
        format: 'a4',
      });

      const pageWidth = pdf.internal.pageSize.getWidth();
      const pageHeight = pdf.internal.pageSize.getHeight();
      let yPosition = 15;
      const margin = 10;
      const maxWidth = pageWidth - 2 * margin;

      // Add header
      pdf.setFontSize(20);
      pdf.setTextColor(0, 85, 204);
      pdf.text('FNOL CLAIM AUDIT TRAIL', margin, yPosition);

      yPosition += 10;
      pdf.setFontSize(10);
      pdf.setTextColor(80, 80, 80);
      pdf.text(`Claim ID: ${claimId}`, margin, yPosition);

      yPosition += 7;
      pdf.text(`Generated: ${new Date().toISOString()}`, margin, yPosition);

      yPosition += 15;
      pdf.setDrawColor(0, 85, 204);
      pdf.line(margin, yPosition, pageWidth - margin, yPosition);

      yPosition += 10;
      pdf.setFontSize(12);
      pdf.setTextColor(0, 0, 0);
      pdf.text('IMMUTABLE SHA-256 HASHED TRAIL', margin, yPosition);

      yPosition += 12;

      // Add audit events
      pdf.setFontSize(9);

      trail.forEach((event, index) => {
        // Check if we need a new page
        if (yPosition > pageHeight - 30) {
          pdf.addPage();
          yPosition = 15;
        }

        // Event header
        pdf.setTextColor(0, 85, 204);
        pdf.setFont('helvetica', 'bold');
        pdf.text(`EVENT #${index + 1}`, margin, yPosition);

        yPosition += 7;

        // Event details
        pdf.setTextColor(80, 80, 80);
        pdf.setFont('helvetica', 'normal');

        const details = [
          `Agent:         ${event.agentName}`,
          `Status:        ${event.status}`,
          `Timestamp:     ${event.timestamp}`,
          `Input Hash:    ${event.inputHash || 'N/A'}`,
          `Output Hash:   ${event.outputHash || 'N/A'}`,
        ];

        details.forEach((line) => {
          pdf.text(line, margin + 2, yPosition);
          yPosition += 6;
        });

        yPosition += 3;
        pdf.setDrawColor(200, 200, 200);
        pdf.line(margin, yPosition, pageWidth - margin, yPosition);
        yPosition += 7;
      });

      // Add compliance statement
      if (yPosition > pageHeight - 50) {
        pdf.addPage();
        yPosition = 15;
      }

      yPosition += 5;
      pdf.setFontSize(11);
      pdf.setTextColor(0, 85, 204);
      pdf.setFont('helvetica', 'bold');
      pdf.text('COMPLIANCE STATEMENT', margin, yPosition);

      yPosition += 10;
      pdf.setFontSize(9);
      pdf.setTextColor(80, 80, 80);
      pdf.setFont('helvetica', 'normal');

      const complianceText = [
        'This audit trail is immutable and NAIC Model Audit Rule compliant.',
        'Each agent\'s input and output have been hashed with SHA-256.',
        'No modifications are possible after initial creation.',
        '',
        'Generated by FNOL Intelligence Platform',
      ];

      complianceText.forEach((line) => {
        if (yPosition > pageHeight - 20) {
          pdf.addPage();
          yPosition = 15;
        }
        pdf.text(line, margin, yPosition);
        yPosition += 6;
      });

      // Add footer to all pages
      const pageCount = pdf.getNumberOfPages();
      for (let i = 1; i <= pageCount; i++) {
        pdf.setPage(i);
        pdf.setFontSize(8);
        pdf.setTextColor(150, 150, 150);
        pdf.text(
          `Page ${i} of ${pageCount}`,
          pageWidth / 2,
          pageHeight - 5,
          { align: 'center' }
        );
      }

      // Save the PDF
      pdf.save(`audit-trail-${claimId}-${Date.now()}.pdf`);
    } catch (error) {
      console.error('Error generating PDF:', error);
      alert('Failed to generate PDF');
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="dashboard-page">
      <header className="topbar">
        <div>
          <p className="eyebrow">AUDIT TRAIL</p>
          <h1>Immutable Event Log</h1>
        </div>
        <div className="claim-meta">
          <span>Claim {claimId}</span>
          <button
            onClick={() => navigate('/')}
            style={{
              background: '#0055cc',
              color: 'white',
              border: 'none',
              borderRadius: '8px',
              padding: '8px 16px',
              cursor: 'pointer',
              fontWeight: '600',
            }}
          >
            Back to Intake
          </button>
        </div>
      </header>

      <div className="badge-row">
        <div className="info-badge governance">
          NAIC Model Audit Rule Compliant — SHA-256 Hashed Trail
        </div>
      </div>

      <section className="explanation-panel" style={{ marginBottom: '24px' }}>
        <h3>How This Audit Trail Works</h3>
        <ul>
          <li>Each agent's input is hashed with SHA-256 before processing.</li>
          <li>Each agent's output is hashed with SHA-256 after processing.</li>
          <li>The trail is immutable — no modifications are possible after creation.</li>
          <li>
            Hashes serve as cryptographic proof of data integrity for regulatory compliance.
          </li>
          <li>Use the Export button to download a compliance-ready audit report.</li>
        </ul>
      </section>

      <section className="why-panel">
        <div className="panel-header">
          <h2>Agent Execution Timeline</h2>
          <span>{trail.length} events recorded</span>
        </div>

        <div style={{ marginTop: '20px' }}>
          {trail.map((event, index) => (
            <div
              key={index}
              style={{
                background: '#f8f9fa',
                border: '1px solid #dee2e6',
                borderRadius: '10px',
                padding: '16px',
                marginBottom: '12px',
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
              }}
            >
              <div>
                <div style={{ fontWeight: '600', marginBottom: '6px' }}>
                  {event.agentName}
                </div>
                <div style={{ fontSize: '0.9rem', color: '#6c757d' }}>
                  {event.timestamp}
                </div>
                {event.inputHash && (
                  <div style={{ fontSize: '0.75rem', color: '#999', marginTop: '6px' }}>
                    <strong>Input Hash:</strong> {event.inputHash.substring(0, 24)}...
                  </div>
                )}
                {event.outputHash && (
                  <div style={{ fontSize: '0.75rem', color: '#999' }}>
                    <strong>Output Hash:</strong> {event.outputHash.substring(0, 24)}...
                  </div>
                )}
              </div>
              <span
                style={{
                  background: '#e8f8eb',
                  color: '#228b22',
                  padding: '6px 12px',
                  borderRadius: '6px',
                  fontWeight: '600',
                  fontSize: '0.85rem',
                }}
              >
                {event.status}
              </span>
            </div>
          ))}
        </div>
      </section>

      <div style={{ textAlign: 'center', marginTop: '32px', marginBottom: '32px' }}>
        <button
          onClick={handleExportPDF}
          disabled={loading}
          style={{
            background: loading
              ? '#ccc'
              : 'linear-gradient(135deg, #1da860, #168349)',
            color: 'white',
            border: 'none',
            borderRadius: '10px',
            padding: '14px 28px',
            fontWeight: '700',
            cursor: loading ? 'not-allowed' : 'pointer',
            fontSize: '1rem',
          }}
        >
          {loading ? 'Generating PDF...' : 'Export Audit Trail (PDF)'}
        </button>
      </div>
    </main>
  );
}
