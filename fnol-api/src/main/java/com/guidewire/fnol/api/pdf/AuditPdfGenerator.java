package com.guidewire.fnol.api.pdf;

import com.guidewire.fnol.api.persistence.AuditEntity;
import com.guidewire.fnol.api.persistence.ClaimEntity;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Generates a SHA-256 hashed PDF audit report for a given claim.
 *
 * <p>The PDF contains:
 * <ol>
 *   <li>Cover page with claim metadata and generation timestamp</li>
 *   <li>AI Advisory / WHY panel (from persisted explanationJson)</li>
 *   <li>All audit trail events (agent name, hashes, status)</li>
 *   <li>Adjuster decision block (if reviewed)</li>
 *   <li>SHA-256 document integrity hash on the final page</li>
 * </ol>
 */
@Component
public class AuditPdfGenerator {

    private static final Font TITLE_FONT   = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, Color.WHITE);
    private static final Font HEADER_FONT  = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, new Color(30, 58, 138));
    private static final Font LABEL_FONT   = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.DARK_GRAY);
    private static final Font VALUE_FONT   = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
    private static final Font MONO_FONT    = FontFactory.getFont(FontFactory.COURIER, 8, Color.DARK_GRAY);
    private static final Font FOOTER_FONT  = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.GRAY);

    private static final Color BRAND_BLUE  = new Color(30, 58, 138);
    private static final Color LIGHT_BLUE  = new Color(219, 234, 254);
    private static final Color PASS_GREEN  = new Color(21, 128, 61);
    private static final Color FAIL_RED    = new Color(185, 28, 28);

    public byte[] generate(ClaimEntity claim, List<AuditEntity> auditRecords) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 50, 50, 60, 60);
            PdfWriter writer = PdfWriter.getInstance(doc, baos);
            writer.setPageEvent(new FooterEvent(claim.claimId));
            doc.open();

            // ── Cover / Header ────────────────────────────────────────────────
            addCoverHeader(doc, claim);

            // ── Claim Metadata ────────────────────────────────────────────────
            addSectionHeader(doc, "1. Claim Metadata");
            PdfPTable meta = twoColumnTable();
            addRow(meta, "Claim ID",         claim.claimId);
            addRow(meta, "Policy Number",    claim.policyNumber);
            addRow(meta, "Status",           claim.status);
            addRow(meta, "State",            claim.state);
            addRow(meta, "Incident Date",    String.valueOf(claim.incidentDate));
            addRow(meta, "Channel",          nvl(claim.channel, "N/A"));
            addRow(meta, "Estimated Damage", "$" + claim.estimatedDamage);
            addRow(meta, "Reserve",          "$" + claim.reserve);
            addRow(meta, "Created At",       String.valueOf(claim.createdAt));
            doc.add(meta);
            doc.add(Chunk.NEWLINE);

            // ── AI WHY Panel ─────────────────────────────────────────────────
            addSectionHeader(doc, "2. AI Advisory — WHY Panel");
            if (claim.explanationJson != null) {
                // Pretty-print the JSON as monospace block
                PdfPTable whyTable = new PdfPTable(1);
                whyTable.setWidthPercentage(100);
                PdfPCell cell = new PdfPCell(new Phrase(claim.explanationJson, MONO_FONT));
                cell.setBackgroundColor(new Color(248, 250, 252));
                cell.setPadding(8);
                cell.setBorderColor(new Color(203, 213, 225));
                whyTable.addCell(cell);
                doc.add(whyTable);
            } else {
                doc.add(new Paragraph("No explanation available.", VALUE_FONT));
            }
            doc.add(Chunk.NEWLINE);

            // ── Audit Trail ──────────────────────────────────────────────────
            addSectionHeader(doc, "3. Audit Trail Events");
            PdfPTable audit = new PdfPTable(new float[]{2.5f, 1.5f, 3f, 3f, 1f});
            audit.setWidthPercentage(100);
            for (String h : new String[]{"Agent", "Status", "Input Hash (SHA-256)", "Output Hash (SHA-256)", "Timestamp"}) {
                PdfPCell hc = new PdfPCell(new Phrase(h, LABEL_FONT));
                hc.setBackgroundColor(LIGHT_BLUE);
                hc.setPadding(5);
                audit.addCell(hc);
            }
            for (AuditEntity a : auditRecords) {
                audit.addCell(cell(a.agentName,  VALUE_FONT));
                PdfPCell statusCell = new PdfPCell(new Phrase(a.status,
                        FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9,
                                "SUCCESS".equals(a.status) ? PASS_GREEN : FAIL_RED)));
                statusCell.setPadding(5);
                audit.addCell(statusCell);
                audit.addCell(cell(truncate(a.inputHash, 28),  MONO_FONT));
                audit.addCell(cell(truncate(a.outputHash, 28), MONO_FONT));
                audit.addCell(cell(shortTime(a.timestamp),     MONO_FONT));
            }
            doc.add(audit);
            doc.add(Chunk.NEWLINE);

            // ── Adjuster Decision ────────────────────────────────────────────
            addSectionHeader(doc, "4. Adjuster Decision");
            PdfPTable adj = twoColumnTable();
            addRow(adj, "Decision",       nvl(claim.adjusterDecision, "PENDING"));
            addRow(adj, "Adjuster ID",    nvl(claim.adjusterId,       "—"));
            addRow(adj, "Notes",          nvl(claim.adjusterNotes,    "—"));
            addRow(adj, "Reviewed At",    claim.reviewedAt != null ? String.valueOf(claim.reviewedAt) : "Not yet reviewed");
            doc.add(adj);
            doc.add(Chunk.NEWLINE);

            // ── Document Integrity Hash ───────────────────────────────────────
            addSectionHeader(doc, "5. Document Integrity — SHA-256");
            String docHash = sha256(claim.claimId + claim.status + claim.policyNumber
                    + claim.estimatedDamage + Instant.now());
            PdfPTable hashTable = new PdfPTable(1);
            hashTable.setWidthPercentage(100);
            PdfPCell hashCell = new PdfPCell(new Phrase("SHA-256: " + docHash, MONO_FONT));
            hashCell.setBackgroundColor(new Color(254, 252, 232));
            hashCell.setBorderColor(new Color(202, 138, 4));
            hashCell.setPadding(10);
            hashTable.addCell(hashCell);
            doc.add(hashTable);

            Paragraph disclaimer = new Paragraph(
                "\n⚠  AI ADVISORY ONLY — Final decisions are made exclusively by licensed human adjusters. "
                + "This document is generated for compliance and audit purposes.",
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, Color.GRAY));
            doc.add(disclaimer);

            doc.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("PDF generation failed for claim " + claim.claimId, e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void addCoverHeader(Document doc, ClaimEntity claim) throws DocumentException {
        PdfPTable header = new PdfPTable(1);
        header.setWidthPercentage(100);
        PdfPCell titleCell = new PdfPCell();
        titleCell.setBackgroundColor(BRAND_BLUE);
        titleCell.setPadding(16);
        titleCell.setBorder(Rectangle.NO_BORDER);
        Paragraph title = new Paragraph("FNOL Claims Intelligence Platform", TITLE_FONT);
        title.setAlignment(Element.ALIGN_CENTER);
        titleCell.addElement(title);
        Paragraph sub = new Paragraph("Audit & Compliance Report  |  Claim: " + claim.claimId,
                FontFactory.getFont(FontFactory.HELVETICA, 11, Color.WHITE));
        sub.setAlignment(Element.ALIGN_CENTER);
        titleCell.addElement(sub);
        Paragraph gen = new Paragraph("Generated: " + Instant.now() + "  |  HUMAN-IN-THE-LOOP ENFORCED",
                FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, new Color(186, 230, 253)));
        gen.setAlignment(Element.ALIGN_CENTER);
        titleCell.addElement(gen);
        header.addCell(titleCell);
        doc.add(header);
        doc.add(Chunk.NEWLINE);
    }

    private void addSectionHeader(Document doc, String text) throws DocumentException {
        Paragraph p = new Paragraph(text, HEADER_FONT);
        p.setSpacingBefore(10);
        p.setSpacingAfter(6);
        doc.add(p);
    }

    private PdfPTable twoColumnTable() throws DocumentException {
        PdfPTable t = new PdfPTable(new float[]{2f, 4f});
        t.setWidthPercentage(100);
        return t;
    }

    private void addRow(PdfPTable table, String label, String value) {
        PdfPCell lc = new PdfPCell(new Phrase(label, LABEL_FONT));
        lc.setBackgroundColor(LIGHT_BLUE);
        lc.setPadding(5);
        table.addCell(lc);
        table.addCell(cell(value, VALUE_FONT));
    }

    private PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text != null ? text : "—", font));
        c.setPadding(5);
        return c;
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return "HASH_ERROR";
        }
    }

    private String nvl(String s, String fallback) {
        return (s != null && !s.isBlank()) ? s : fallback;
    }

    private String truncate(String s, int max) {
        if (s == null) return "—";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private String shortTime(java.time.Instant ts) {
        if (ts == null) return "—";
        String s = ts.toString();
        return s.length() > 19 ? s.substring(0, 19) + "Z" : s;
    }

    // ── Page Footer ──────────────────────────────────────────────────────────
    private static class FooterEvent extends PdfPageEventHelper {
        private final String claimId;
        FooterEvent(String claimId) { this.claimId = claimId; }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            Phrase footer = new Phrase(
                "FNOL Intelligence Platform  |  Claim: " + claimId
                + "  |  Page " + writer.getPageNumber()
                + "  |  CONFIDENTIAL — For compliance use only",
                FontFactory.getFont(FontFactory.HELVETICA, 7, Color.GRAY));
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, footer,
                    (document.left() + document.right()) / 2, document.bottom() - 10, 0);
        }
    }
}
