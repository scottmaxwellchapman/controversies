package net.familylawandprobate.controversies;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class document_parts {

    private static final String CATEGORY_LEAD = "lead";
    private static final String CATEGORY_ATTACHMENT = "attachment";

    public static final class PartRec {
        public String uuid;
        public String label;
        public String partType;
        public String status;
        public String sequence;
        public String confidentiality;
        public String author;
        public String notes;
        public String createdAt;
        public String updatedAt;
        public boolean trashed;
    }

    public static document_parts defaultStore() { return new document_parts(); }

    public List<PartRec> listAll(String tenantUuid, String matterUuid, String docUuid) throws Exception {
        List<PartRec> out = new ArrayList<PartRec>();
        Path p = partsPath(tenantUuid, matterUuid, docUuid);
        if (p == null || !Files.exists(p)) return out;
        Document d = document_workflow_support.parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;
        NodeList nl = root.getElementsByTagName("part");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            out.add(readRec((Element) n));
        }
        out.sort(Comparator.comparing(a -> document_workflow_support.safe(a.sequence) + "-" + document_workflow_support.safe(a.label).toLowerCase()));
        return out;
    }

    public PartRec create(String tenantUuid, String matterUuid, String docUuid, String label, String partType,
                          String status, String sequence, String confidentiality, String author, String notes) throws Exception {
        if (document_workflow_support.safe(label).trim().isBlank()) throw new IllegalArgumentException("label required");
        List<PartRec> all = listAll(tenantUuid, matterUuid, docUuid);
        String category = canonicalCategory(partType);
        if (CATEGORY_LEAD.equals(category) && hasActiveLead(all, null)) {
            throw new IllegalArgumentException("Only one Lead part is allowed per document.");
        }
        PartRec rec = new PartRec();
        rec.uuid = UUID.randomUUID().toString();
        rec.label = document_workflow_support.safe(label).trim();
        rec.partType = category;
        rec.status = document_workflow_support.normalizeToken(status);
        rec.sequence = document_workflow_support.safe(sequence).trim();
        rec.confidentiality = document_workflow_support.safe(confidentiality).trim();
        rec.author = document_workflow_support.safe(author).trim();
        rec.notes = document_workflow_support.safe(notes).trim();
        rec.createdAt = Instant.now().toString();
        rec.updatedAt = rec.createdAt;
        rec.trashed = false;
        all.add(rec);
        writeAll(tenantUuid, matterUuid, docUuid, all);
        Files.createDirectories(partFolder(tenantUuid, matterUuid, docUuid, rec.uuid));
        return rec;
    }

    public boolean setTrashed(String tenantUuid, String matterUuid, String docUuid, String partUuid, boolean trashed) throws Exception {
        List<PartRec> all = listAll(tenantUuid, matterUuid, docUuid);
        if (!trashed) {
            PartRec target = null;
            for (PartRec rec : all) {
                if (document_workflow_support.safe(partUuid).trim().equals(document_workflow_support.safe(rec.uuid).trim())) {
                    target = rec;
                    break;
                }
            }
            if (target != null && CATEGORY_LEAD.equals(canonicalCategory(target.partType)) && hasActiveLead(all, target.uuid)) {
                throw new IllegalArgumentException("Only one Lead part is allowed per document.");
            }
        }

        boolean changed = false;
        for (PartRec rec : all) {
            if (!document_workflow_support.safe(partUuid).trim().equals(document_workflow_support.safe(rec.uuid).trim())) continue;
            rec.trashed = trashed;
            rec.updatedAt = Instant.now().toString();
            changed = true;
        }
        if (changed) writeAll(tenantUuid, matterUuid, docUuid, all);
        return changed;
    }

    public PartRec get(String tenantUuid, String matterUuid, String docUuid, String partUuid) throws Exception {
        for (PartRec rec : listAll(tenantUuid, matterUuid, docUuid)) {
            if (document_workflow_support.safe(partUuid).trim().equals(document_workflow_support.safe(rec.uuid).trim())) return rec;
        }
        return null;
    }

    public Path partFolder(String tenantUuid, String matterUuid, String docUuid, String partUuid) {
        documents docs = documents.defaultStore();
        Path docFolder = docs.documentFolder(tenantUuid, matterUuid, docUuid);
        if (docFolder == null || document_workflow_support.safe(partUuid).trim().isBlank()) return null;
        return docFolder.resolve("parts").resolve(partUuid);
    }

    private static PartRec readRec(Element e) {
        PartRec rec = new PartRec();
        rec.uuid = document_workflow_support.text(e, "uuid");
        rec.label = document_workflow_support.text(e, "label");
        rec.partType = canonicalCategory(document_workflow_support.text(e, "part_type"));
        rec.status = document_workflow_support.text(e, "status");
        rec.sequence = document_workflow_support.text(e, "sequence");
        rec.confidentiality = document_workflow_support.text(e, "confidentiality");
        rec.author = document_workflow_support.text(e, "author");
        rec.notes = document_workflow_support.text(e, "notes");
        rec.createdAt = document_workflow_support.text(e, "created_at");
        rec.updatedAt = document_workflow_support.text(e, "updated_at");
        rec.trashed = "true".equalsIgnoreCase(document_workflow_support.text(e, "trashed"));
        return rec;
    }

    private void writeAll(String tenantUuid, String matterUuid, String docUuid, List<PartRec> all) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<parts updated=\"")
          .append(document_workflow_support.xmlText(document_workflow_support.nowIso())).append("\">\n");
        for (PartRec rec : all) {
            if (rec == null || document_workflow_support.safe(rec.uuid).isBlank()) continue;
            sb.append("  <part>\n");
            writeTag(sb, "uuid", rec.uuid);
            writeTag(sb, "label", rec.label);
            writeTag(sb, "part_type", canonicalCategory(rec.partType));
            writeTag(sb, "status", rec.status);
            writeTag(sb, "sequence", rec.sequence);
            writeTag(sb, "confidentiality", rec.confidentiality);
            writeTag(sb, "author", rec.author);
            writeTag(sb, "notes", rec.notes);
            writeTag(sb, "created_at", rec.createdAt);
            writeTag(sb, "updated_at", rec.updatedAt);
            writeTag(sb, "trashed", rec.trashed ? "true" : "false");
            sb.append("  </part>\n");
        }
        sb.append("</parts>\n");
        document_workflow_support.writeAtomic(partsPath(tenantUuid, matterUuid, docUuid), sb.toString());
    }

    private static void writeTag(StringBuilder sb, String tag, String value) {
        sb.append("    <").append(tag).append(">")
          .append(document_workflow_support.xmlText(value))
          .append("</").append(tag).append(">\n");
    }

    private static Path partsPath(String tenantUuid, String matterUuid, String docUuid) {
        documents docs = documents.defaultStore();
        Path docFolder = docs.documentFolder(tenantUuid, matterUuid, docUuid);
        if (docFolder == null) return null;
        return docFolder.resolve("parts.xml");
    }

    private static String canonicalCategory(String raw) {
        String v = document_workflow_support.normalizeToken(raw);
        if (CATEGORY_LEAD.equals(v)) return CATEGORY_LEAD;
        return CATEGORY_ATTACHMENT;
    }

    private static boolean hasActiveLead(List<PartRec> all, String exceptPartUuid) {
        if (all == null || all.isEmpty()) return false;
        String except = document_workflow_support.safe(exceptPartUuid).trim();
        for (PartRec rec : all) {
            if (rec == null) continue;
            if (rec.trashed) continue;
            String uuid = document_workflow_support.safe(rec.uuid).trim();
            if (!except.isBlank() && except.equals(uuid)) continue;
            if (CATEGORY_LEAD.equals(canonicalCategory(rec.partType))) return true;
        }
        return false;
    }
}
