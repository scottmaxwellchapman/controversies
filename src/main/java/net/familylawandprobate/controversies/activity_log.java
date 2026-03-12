package net.familylawandprobate.controversies;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class activity_log {
    private static final Pattern EVENT_PATTERN = Pattern.compile("<event\\s+([^>]+)>([\\s\\S]*?)</event>");
    private static final Pattern DETAIL_PATTERN = Pattern.compile("<detail\\s+key=\\\"([^\\\"]*)\\\">([\\s\\S]*?)</detail>");
    private static final Pattern TENANT_ACTIVITY_FILE = Pattern.compile("activity_\\d{4}-\\d{2}-\\d{2}\\.xml");

    public static final class LogEntry {
        public final String time;
        public final String level;
        public final String action;
        public final String tenantUuid;
        public final String userUuid;
        public final String caseUuid;
        public final String documentUuid;
        public final String details;
        public final Map<String, String> detailMap;

        public LogEntry(String time,
                        String level,
                        String action,
                        String tenantUuid,
                        String userUuid,
                        String caseUuid,
                        String documentUuid,
                        String details,
                        Map<String, String> detailMap) {
            this.time = safe(time);
            this.level = safe(level);
            this.action = safe(action);
            this.tenantUuid = safe(tenantUuid);
            this.userUuid = safe(userUuid);
            this.caseUuid = safe(caseUuid);
            this.documentUuid = safe(documentUuid);
            this.details = safe(details);
            LinkedHashMap<String, String> copy = new LinkedHashMap<String, String>();
            if (detailMap != null) {
                for (Map.Entry<String, String> e : detailMap.entrySet()) {
                    if (e == null) continue;
                    String key = safe(e.getKey()).trim();
                    if (key.isBlank()) continue;
                    copy.put(key, safe(e.getValue()));
                }
            }
            this.detailMap = Collections.unmodifiableMap(copy);
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

    public void write(String level,
                      String action,
                      String tenantUuid,
                      String userUuid,
                      String caseUuid,
                      Map<String, String> details) {
        write(level, action, tenantUuid, userUuid, caseUuid, "", details);
    }

    public void write(String level,
                      String action,
                      String tenantUuid,
                      String userUuid,
                      String caseUuid,
                      String documentUuid,
                      Map<String, String> details) {
        String normalized = safe(level).trim().toLowerCase(Locale.ROOT);
        if ("error".equals(normalized)) {
            logError(action, tenantUuid, userUuid, caseUuid, documentUuid, details);
            return;
        }
        if ("warn".equals(normalized) || "warning".equals(normalized)) {
            logWarning(action, tenantUuid, userUuid, caseUuid, documentUuid, details);
            return;
        }
        logVerbose(action, tenantUuid, userUuid, caseUuid, documentUuid, details);
    }

    private void log(String level, String action, String tenantUuid, String userUuid, String caseUuid, String documentUuid, Map<String, String> details) {
        String tenant = safe(tenantUuid).trim();
        if (tenant.isBlank()) return;
        String caseId = safe(caseUuid).trim();
        String now = app_clock.now().toString();
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
                + "\" case_uuid=\"" + xmlAttr(caseId) + "\" document_uuid=\"" + xmlAttr(documentUuid) + "\">\n"
                + detailXml + "  </event>\n";

        synchronized (lock) {
            try {
                appendXmlEvent(logPath(tenant), "activity_log", tenantLogSeed(tenant), entry);
                if (!caseId.isBlank()) {
                    appendXmlEvent(caseLogPath(tenant, caseId), "activity_feed", caseLogSeed(tenant, caseId), entry);
                }
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
                List<Path> logs = tenantLogPathsNewestFirst(tenant);
                for (Path p : logs) {
                    if (p == null || !Files.exists(p)) continue;
                    String xml = Files.readString(p, StandardCharsets.UTF_8);
                    out.addAll(parseEntries(xml));
                    if (out.size() >= (max * 2)) break;
                }
            } catch (Exception ignored) {}
        }
        Collections.sort(out, Comparator.comparing((LogEntry e) -> safe(e.time)).reversed());
        if (out.size() > max) return new ArrayList<LogEntry>(out.subList(0, max));
        return out;
    }

    public List<LogEntry> recentForCase(String tenantUuid, String caseUuid, int limit) {
        String tenant = safe(tenantUuid).trim();
        String caseId = safe(caseUuid).trim();
        if (tenant.isBlank() || caseId.isBlank()) return List.of();
        int max = Math.max(1, Math.min(1000, limit));
        List<LogEntry> out = new ArrayList<LogEntry>();
        synchronized (lock) {
            try {
                Path p = caseLogPath(tenant, caseId);
                if (!Files.exists(p)) return List.of();
                String xml = Files.readString(p, StandardCharsets.UTF_8);
                out.addAll(parseEntries(xml));
            } catch (Exception ignored) {}
        }
        Collections.sort(out, Comparator.comparing((LogEntry e) -> safe(e.time)).reversed());
        if (out.size() > max) return new ArrayList<LogEntry>(out.subList(0, max));
        return out;
    }

    public String caseFeedXml(String tenantUuid, String caseUuid) {
        String tenant = safe(tenantUuid).trim();
        String caseId = safe(caseUuid).trim();
        if (tenant.isBlank() || caseId.isBlank()) return caseLogSeed("", "");
        synchronized (lock) {
            try {
                Path p = caseLogPath(tenant, caseId);
                if (!Files.exists(p)) return caseLogSeed(tenant, caseId);
                return Files.readString(p, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return caseLogSeed(tenant, caseId);
            }
        }
    }

    private static List<LogEntry> parseEntries(String xml) {
        List<LogEntry> out = new ArrayList<LogEntry>();
        Matcher m = EVENT_PATTERN.matcher(safe(xml));
        while (m.find()) {
            String attrs = safe(m.group(1));
            String body = safe(m.group(2)).trim();
            LinkedHashMap<String, String> detailMap = parseDetailMap(body);
            out.add(new LogEntry(
                    xmlUnescape(attr(attrs, "time")),
                    xmlUnescape(attr(attrs, "level")),
                    xmlUnescape(attr(attrs, "action")),
                    xmlUnescape(attr(attrs, "tenant_uuid")),
                    xmlUnescape(attr(attrs, "user_uuid")),
                    xmlUnescape(attr(attrs, "case_uuid")),
                    xmlUnescape(attr(attrs, "document_uuid")),
                    summarizeDetails(body, detailMap),
                    detailMap
            ));
        }
        return out;
    }

    private static LinkedHashMap<String, String> parseDetailMap(String body) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        Matcher detailMatcher = DETAIL_PATTERN.matcher(safe(body));
        while (detailMatcher.find()) {
            String key = xmlUnescape(safe(detailMatcher.group(1)).trim());
            if (key.isBlank()) continue;
            String val = xmlUnescape(safe(detailMatcher.group(2)).trim());
            out.put(key, val);
        }
        return out;
    }

    private static String summarizeDetails(String body, LinkedHashMap<String, String> detailMap) {
        if (detailMap != null && !detailMap.isEmpty()) {
            StringBuilder sb = new StringBuilder(Math.max(64, detailMap.size() * 20));
            for (Map.Entry<String, String> e : detailMap.entrySet()) {
                if (e == null) continue;
                if (sb.length() > 0) sb.append("; ");
                sb.append(safe(e.getKey())).append("=").append(safe(e.getValue()));
            }
            return sb.toString();
        }
        return xmlUnescape(safe(body).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
    }

    private static void appendXmlEvent(Path path, String rootTag, String seed, String entry) throws Exception {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            Files.writeString(path, safe(seed), StandardCharsets.UTF_8);
        }
        String existing = Files.readString(path, StandardCharsets.UTF_8);
        String closeTag = "</" + safe(rootTag) + ">";
        int cut = existing.lastIndexOf(closeTag);
        if (cut < 0) cut = existing.length();
        String merged = existing.substring(0, cut) + safe(entry) + closeTag + "\n";
        Files.writeString(path, merged, StandardCharsets.UTF_8);
    }

    private static String tenantLogSeed(String tenantUuid) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<activity_log tenant_uuid=\"" + xmlAttr(tenantUuid) + "\">\n"
                + "</activity_log>\n";
    }

    private static String caseLogSeed(String tenantUuid, String caseUuid) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<activity_feed tenant_uuid=\"" + xmlAttr(tenantUuid) + "\" case_uuid=\"" + xmlAttr(caseUuid) + "\">\n"
                + "</activity_feed>\n";
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
        String day = DAY.format(app_clock.now());
        return Paths.get("data", "tenants", safeFile(tenantUuid), "logs", "activity_" + day + ".xml").toAbsolutePath();
    }

    private static List<Path> tenantLogPathsNewestFirst(String tenantUuid) {
        Path logsDir = Paths.get("data", "tenants", safeFile(tenantUuid), "logs").toAbsolutePath();
        if (!Files.isDirectory(logsDir)) return List.of();
        ArrayList<Path> out = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(logsDir, "activity_*.xml")) {
            for (Path p : ds) {
                if (p == null) continue;
                String fn = safe(p.getFileName() == null ? "" : p.getFileName().toString()).trim();
                if (!TENANT_ACTIVITY_FILE.matcher(fn).matches()) continue;
                out.add(p);
            }
        } catch (Exception ignored) {
            return List.of();
        }
        out.sort((a, b) -> safe(b == null || b.getFileName() == null ? "" : b.getFileName().toString())
                .compareTo(safe(a == null || a.getFileName() == null ? "" : a.getFileName().toString())));
        return out;
    }

    private static Path caseLogPath(String tenantUuid, String caseUuid) {
        return Paths.get("data", "tenants", safeFile(tenantUuid), "matters", safeFile(caseUuid), "activity_feed.xml").toAbsolutePath();
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String safeFile(String s) { return safe(s).replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static String xmlAttr(String s) { return xmlText(s).replace("\"", "&quot;"); }
    private static String xmlText(String s) {
        return safe(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;");
    }
}
