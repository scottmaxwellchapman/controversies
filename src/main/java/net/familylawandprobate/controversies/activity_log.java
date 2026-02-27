package net.familylawandprobate.controversies;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class activity_log {
    public static final class LogEntry {
        public final String time;
        public final String level;
        public final String action;
        public final String tenantUuid;
        public final String userUuid;
        public final String caseUuid;
        public final String documentUuid;
        public final String details;

        public LogEntry(String time, String level, String action, String tenantUuid, String userUuid, String caseUuid, String documentUuid, String details) {
            this.time = safe(time);
            this.level = safe(level);
            this.action = safe(action);
            this.tenantUuid = safe(tenantUuid);
            this.userUuid = safe(userUuid);
            this.caseUuid = safe(caseUuid);
            this.documentUuid = safe(documentUuid);
            this.details = safe(details);
        }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private final Object lock = new Object();

    public static activity_log defaultStore() { return new activity_log(); }

    public void logVerbose(String action, String tenantUuid, String userUuid, String caseUuid, String documentUuid, Map<String, String> details) {
        log("verbose", action, tenantUuid, userUuid, caseUuid, documentUuid, details);
    }

    public void logWarning(String action, String tenantUuid, String userUuid, String caseUuid, String documentUuid, Map<String, String> details) {
        log("warning", action, tenantUuid, userUuid, caseUuid, documentUuid, details);
    }

    public void logError(String action, String tenantUuid, String userUuid, String caseUuid, String documentUuid, Map<String, String> details) {
        log("error", action, tenantUuid, userUuid, caseUuid, documentUuid, details);
    }

    private void log(String level, String action, String tenantUuid, String userUuid, String caseUuid, String documentUuid, Map<String, String> details) {
        String tenant = safe(tenantUuid).trim();
        if (tenant.isBlank()) return;
        String now = Instant.now().toString();
        StringBuilder detailXml = new StringBuilder(256);
        if (details != null) {
            for (Map.Entry<String, String> e : details.entrySet()) {
                if (e == null) continue;
                String key = safe(e.getKey()).trim();
                if (key.isBlank()) continue;
                String redacted = secret_redactor.redactIfSensitive(key, e.getValue());
                detailXml.append("    <detail key=\"").append(xmlAttr(key)).append("\">")
                        .append(xmlText(redacted)).append("</detail>\n");
            }
        }

        String entry = "  <event time=\"" + xmlAttr(now) + "\" level=\"" + xmlAttr(level) + "\" action=\"" + xmlAttr(action)
                + "\" tenant_uuid=\"" + xmlAttr(tenant) + "\" user_uuid=\"" + xmlAttr(userUuid)
                + "\" case_uuid=\"" + xmlAttr(caseUuid) + "\" document_uuid=\"" + xmlAttr(documentUuid) + "\">\n"
                + detailXml + "  </event>\n";

        synchronized (lock) {
            try {
                Path p = logPath(tenant);
                Files.createDirectories(p.getParent());
                if (!Files.exists(p)) {
                    String seed = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<activity_log tenant_uuid=\"" + xmlAttr(tenant) + "\">\n</activity_log>\n";
                    Files.writeString(p, seed, StandardCharsets.UTF_8);
                }
                String existing = Files.readString(p, StandardCharsets.UTF_8);
                int cut = existing.lastIndexOf("</activity_log>");
                if (cut < 0) cut = existing.length();
                String merged = existing.substring(0, cut) + entry + "</activity_log>\n";
                Files.writeString(p, merged, StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
    }

    public List<LogEntry> recent(String tenantUuid, int limit) {
        String tenant = safe(tenantUuid).trim();
        if (tenant.isBlank()) return List.of();
        int max = Math.max(1, Math.min(500, limit));
        List<LogEntry> out = new ArrayList<LogEntry>();
        synchronized (lock) {
            try {
                Path p = logPath(tenant);
                if (!Files.exists(p)) return List.of();
                String xml = Files.readString(p, StandardCharsets.UTF_8);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("<event\\s+([^>]+)>([\\s\\S]*?)</event>")
                        .matcher(xml);
                while (m.find()) {
                    String attrs = safe(m.group(1));
                    String body = safe(m.group(2)).trim();
                    out.add(new LogEntry(
                            xmlUnescape(attr(attrs, "time")),
                            xmlUnescape(attr(attrs, "level")),
                            xmlUnescape(attr(attrs, "action")),
                            xmlUnescape(attr(attrs, "tenant_uuid")),
                            xmlUnescape(attr(attrs, "user_uuid")),
                            xmlUnescape(attr(attrs, "case_uuid")),
                            xmlUnescape(attr(attrs, "document_uuid")),
                            xmlUnescape(body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim())
                    ));
                }
            } catch (Exception ignored) {}
        }
        Collections.sort(out, Comparator.comparing((LogEntry e) -> safe(e.time)).reversed());
        if (out.size() > max) return new ArrayList<LogEntry>(out.subList(0, max));
        return out;
    }

    private static String attr(String attrs, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(key + "=\\\"([^\\\"]*)\\\"")
                .matcher(safe(attrs));
        return m.find() ? m.group(1) : "";
    }

    private static String xmlUnescape(String s) {
        return safe(s)
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static Path logPath(String tenantUuid) {
        String day = DAY.format(Instant.now());
        return Paths.get("data", "tenants", safeFile(tenantUuid), "logs", "activity_" + day + ".xml").toAbsolutePath();
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String safeFile(String s) { return safe(s).replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static String xmlAttr(String s) { return xmlText(s).replace("\"", "&quot;"); }
    private static String xmlText(String s) {
        return safe(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;");
    }
}
