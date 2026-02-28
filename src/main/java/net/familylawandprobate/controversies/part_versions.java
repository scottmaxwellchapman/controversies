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

public final class part_versions {

    public static final class VersionRec {
        public String uuid;
        public String versionLabel;
        public String source;
        public String mimeType;
        public String checksum;
        public String fileSizeBytes;
        public String storagePath;
        public String createdBy;
        public String notes;
        public String createdAt;
        public boolean current;
    }

    public static part_versions defaultStore() { return new part_versions(); }

    public List<VersionRec> listAll(String tenantUuid, String matterUuid, String docUuid, String partUuid) throws Exception {
        List<VersionRec> out = new ArrayList<VersionRec>();
        Path p = versionsPath(tenantUuid, matterUuid, docUuid, partUuid);
        if (p == null || !Files.exists(p)) return out;
        Document d = document_workflow_support.parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;
        NodeList nl = root.getElementsByTagName("version");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            out.add(readRec((Element) n));
        }
        out.sort(Comparator.comparing(a -> document_workflow_support.safe(a.createdAt)).reversed());
        return out;
    }

    public VersionRec create(String tenantUuid, String matterUuid, String docUuid, String partUuid, String versionLabel,
                             String source, String mimeType, String checksum, String fileSizeBytes,
                             String storagePath, String createdBy, String notes, boolean makeCurrent) throws Exception {
        if (document_workflow_support.safe(versionLabel).trim().isBlank()) throw new IllegalArgumentException("version label required");
        List<VersionRec> all = listAll(tenantUuid, matterUuid, docUuid, partUuid);
        if (makeCurrent) {
            for (VersionRec rec : all) rec.current = false;
        }
        VersionRec rec = new VersionRec();
        rec.uuid = UUID.randomUUID().toString();
        rec.versionLabel = document_workflow_support.safe(versionLabel).trim();
        rec.source = document_workflow_support.safe(source).trim();
        rec.mimeType = document_workflow_support.safe(mimeType).trim();
        rec.checksum = document_workflow_support.safe(checksum).trim();
        rec.fileSizeBytes = document_workflow_support.safe(fileSizeBytes).trim();
        rec.storagePath = document_workflow_support.safe(storagePath).trim();
        rec.createdBy = document_workflow_support.safe(createdBy).trim();
        rec.notes = document_workflow_support.safe(notes).trim();
        rec.createdAt = Instant.now().toString();
        rec.current = makeCurrent || all.isEmpty();
        all.add(rec);
        writeAll(tenantUuid, matterUuid, docUuid, partUuid, all);
        return rec;
    }

    private static VersionRec readRec(Element e) {
        VersionRec rec = new VersionRec();
        rec.uuid = document_workflow_support.text(e, "uuid");
        rec.versionLabel = document_workflow_support.text(e, "version_label");
        rec.source = document_workflow_support.text(e, "source");
        rec.mimeType = document_workflow_support.text(e, "mime_type");
        rec.checksum = document_workflow_support.text(e, "checksum");
        rec.fileSizeBytes = document_workflow_support.text(e, "file_size_bytes");
        rec.storagePath = document_workflow_support.text(e, "storage_path");
        rec.createdBy = document_workflow_support.text(e, "created_by");
        rec.notes = document_workflow_support.text(e, "notes");
        rec.createdAt = document_workflow_support.text(e, "created_at");
        rec.current = "true".equalsIgnoreCase(document_workflow_support.text(e, "current"));
        return rec;
    }

    private void writeAll(String tenantUuid, String matterUuid, String docUuid, String partUuid, List<VersionRec> all) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<versions updated=\"")
          .append(document_workflow_support.xmlText(document_workflow_support.nowIso())).append("\">\n");
        for (VersionRec rec : all) {
            if (rec == null || document_workflow_support.safe(rec.uuid).isBlank()) continue;
            sb.append("  <version>\n");
            writeTag(sb, "uuid", rec.uuid);
            writeTag(sb, "version_label", rec.versionLabel);
            writeTag(sb, "source", rec.source);
            writeTag(sb, "mime_type", rec.mimeType);
            writeTag(sb, "checksum", rec.checksum);
            writeTag(sb, "file_size_bytes", rec.fileSizeBytes);
            writeTag(sb, "storage_path", rec.storagePath);
            writeTag(sb, "created_by", rec.createdBy);
            writeTag(sb, "notes", rec.notes);
            writeTag(sb, "created_at", rec.createdAt);
            writeTag(sb, "current", rec.current ? "true" : "false");
            sb.append("  </version>\n");
        }
        sb.append("</versions>\n");
        document_workflow_support.writeAtomic(versionsPath(tenantUuid, matterUuid, docUuid, partUuid), sb.toString());
    }

    private static void writeTag(StringBuilder sb, String tag, String value) {
        sb.append("    <").append(tag).append(">")
          .append(document_workflow_support.xmlText(value))
          .append("</").append(tag).append(">\n");
    }

    private static Path versionsPath(String tenantUuid, String matterUuid, String docUuid, String partUuid) {
        document_parts parts = document_parts.defaultStore();
        Path partFolder = parts.partFolder(tenantUuid, matterUuid, docUuid, partUuid);
        if (partFolder == null) return null;
        return partFolder.resolve("versions.xml");
    }
}
