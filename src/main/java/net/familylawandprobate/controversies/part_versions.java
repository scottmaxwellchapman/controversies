package net.familylawandprobate.controversies;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class part_versions {
    private static final activity_log LOGS = activity_log.defaultStore();

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
        out.sort(Comparator.comparing((VersionRec a) -> document_workflow_support.safe(a.createdAt)).reversed());
        return out;
    }

    public VersionRec get(String tenantUuid, String matterUuid, String docUuid, String partUuid, String versionUuid) throws Exception {
        String id = document_workflow_support.safe(versionUuid).trim();
        if (id.isBlank()) return null;
        for (VersionRec rec : listAll(tenantUuid, matterUuid, docUuid, partUuid)) {
            if (rec == null) continue;
            if (id.equals(document_workflow_support.safe(rec.uuid).trim())) return rec;
        }
        return null;
    }

    public VersionRec create(String tenantUuid, String matterUuid, String docUuid, String partUuid, String versionLabel,
                             String source, String mimeType, String checksum, String fileSizeBytes,
                             String storagePath, String createdBy, String notes, boolean makeCurrent) throws Exception {
        return createInternal(tenantUuid, matterUuid, docUuid, partUuid, versionLabel, source, mimeType, checksum, fileSizeBytes, storagePath, createdBy, notes, makeCurrent, true);
    }

    public VersionRec createForExternalSync(String tenantUuid, String matterUuid, String docUuid, String partUuid, String versionLabel,
                                            String source, String mimeType, String checksum, String fileSizeBytes,
                                            String storagePath, String createdBy, String notes, boolean makeCurrent) throws Exception {
        return createInternal(tenantUuid, matterUuid, docUuid, partUuid, versionLabel, source, mimeType, checksum, fileSizeBytes, storagePath, createdBy, notes, makeCurrent, false);
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

    private static void publishVersionEvent(String tenantUuid,
                                            String matterUuid,
                                            String docUuid,
                                            String partUuid,
                                            String eventType,
                                            VersionRec rec) {
        if (rec == null) return;
        try {
            LinkedHashMap<String, String> payload = new LinkedHashMap<String, String>();
            payload.put("matter_uuid", document_workflow_support.safe(matterUuid));
            payload.put("doc_uuid", document_workflow_support.safe(docUuid));
            payload.put("part_uuid", document_workflow_support.safe(partUuid));
            payload.put("version_uuid", document_workflow_support.safe(rec.uuid));
            payload.put("version_label", document_workflow_support.safe(rec.versionLabel));
            payload.put("source", document_workflow_support.safe(rec.source));
            payload.put("mime_type", document_workflow_support.safe(rec.mimeType));
            payload.put("checksum", document_workflow_support.safe(rec.checksum));
            payload.put("file_size_bytes", document_workflow_support.safe(rec.fileSizeBytes));
            payload.put("storage_path", document_workflow_support.safe(rec.storagePath));
            payload.put("created_by", document_workflow_support.safe(rec.createdBy));
            payload.put("notes", document_workflow_support.safe(rec.notes));
            payload.put("created_at", document_workflow_support.safe(rec.createdAt));
            payload.put("current", rec.current ? "true" : "false");
            business_process_manager.defaultService().triggerEvent(
                    document_workflow_support.safe(tenantUuid),
                    document_workflow_support.safe(eventType),
                    payload,
                    "",
                    "part_versions.store"
            );
        } catch (Exception ignored) {
        }
    }

    private static Path versionsPath(String tenantUuid, String matterUuid, String docUuid, String partUuid) {
        document_parts parts = document_parts.defaultStore();
        Path partFolder = parts.partFolder(tenantUuid, matterUuid, docUuid, partUuid);
        if (partFolder == null) return null;
        return partFolder.resolve("versions.xml");
    }

    private VersionRec createInternal(String tenantUuid,
                                      String matterUuid,
                                      String docUuid,
                                      String partUuid,
                                      String versionLabel,
                                      String source,
                                      String mimeType,
                                      String checksum,
                                      String fileSizeBytes,
                                      String storagePath,
                                      String createdBy,
                                      String notes,
                                      boolean makeCurrent,
                                      boolean enforceEditable) throws Exception {
        if (document_workflow_support.safe(versionLabel).trim().isBlank()) throw new IllegalArgumentException("version label required");
        if (enforceEditable) documents.defaultStore().requireEditable(tenantUuid, matterUuid, docUuid);
        Path resolvedStoragePath = pdf_redaction_service.resolveStoragePath(storagePath);
        if (resolvedStoragePath != null) {
            pdf_redaction_service.requirePathWithinTenant(resolvedStoragePath, tenantUuid, "Storage path");
        }
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
        rec.createdAt = app_clock.now().toString();
        rec.current = makeCurrent || all.isEmpty();
        all.add(rec);
        writeAll(tenantUuid, matterUuid, docUuid, partUuid, all);
        LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
        details.put("part_uuid", document_workflow_support.safe(partUuid).trim());
        details.put("version_uuid", rec.uuid);
        details.put("version_label", rec.versionLabel);
        details.put("source", rec.source);
        details.put("mime_type", rec.mimeType);
        details.put("checksum", rec.checksum);
        details.put("file_size_bytes", rec.fileSizeBytes);
        details.put("make_current", rec.current ? "true" : "false");
        details.put("sync_mode", enforceEditable ? "interactive" : "external_sync");
        LOGS.logVerbose(
                "document.version.created",
                document_workflow_support.safe(tenantUuid).trim(),
                document_workflow_support.safe(createdBy).trim(),
                document_workflow_support.safe(matterUuid).trim(),
                document_workflow_support.safe(docUuid).trim(),
                details
        );
        publishVersionEvent(tenantUuid, matterUuid, docUuid, partUuid, "document.version.created", rec);
        return rec;
    }
}
