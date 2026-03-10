package net.familylawandprobate.controversies;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asynchronous search jobs endpoint for UI pages.
 */
public final class search_jobs_servlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(search_jobs_servlet.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String S_TENANT_UUID = "tenant.uuid";
    private static final String S_USER_UUID = users_roles.S_USER_UUID;
    private static final String S_USER_EMAIL = users_roles.S_USER_EMAIL;
    private static final activity_log ACTIVITY_LOGS = activity_log.defaultStore();

    private final search_jobs_service service = search_jobs_service.defaultService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        handle(req, resp, "GET");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        handle(req, resp, "POST");
    }

    private void handle(HttpServletRequest req, HttpServletResponse resp, String method) throws IOException {
        String requestId = UUID.randomUUID().toString();
        String tenantUuid = "";
        String requestedBy = "";
        String action = "";
        try {
            HttpSession sess = req.getSession(false);
            security.sec_bind(req, resp, null, sess);
            if (!security.require_login()) return;
            boolean canDocuments = users_roles.hasPermissionTrue(sess, "documents.access");
            boolean canConflicts = users_roles.hasPermissionTrue(sess, "conflicts.access");
            if (!canDocuments && !canConflicts) {
                if (!security.require_permission("documents.access")) return;
            }

            sess = req.getSession(false);
            tenantUuid = safe(sess == null ? "" : (String) sess.getAttribute(S_TENANT_UUID)).trim();
            String userUuid = safe(sess == null ? "" : (String) sess.getAttribute(S_USER_UUID)).trim();
            String userEmail = safe(sess == null ? "" : (String) sess.getAttribute(S_USER_EMAIL)).trim();
            requestedBy = !userEmail.isBlank() ? userEmail : userUuid;
            if (tenantUuid.isBlank() || requestedBy.isBlank()) {
                LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
                details.put("request_id", requestId);
                details.put("reason", "missing_authenticated_context");
                ACTIVITY_LOGS.logWarning("search.job.request_unauthorized", tenantUuid, requestedBy, "", "", details);
                writeError(resp, 401, "unauthorized", "Authenticated tenant/user context is required.", requestId);
                return;
            }

            action = safe(req.getParameter("action")).trim().toLowerCase(Locale.ROOT);
            if (action.isBlank()) action = safe(req.getHeader("X-Search-Action")).trim().toLowerCase(Locale.ROOT);
            if (action.isBlank()) action = "list";

            if ("get".equals(action) || "status".equals(action)) {
                handleGetJob(req, resp, tenantUuid, requestedBy, requestId);
                return;
            }
            if ("types".equals(action)) {
                handleTypes(resp, requestId, sess);
                return;
            }
            if ("enqueue".equals(action)) {
                if (!"POST".equalsIgnoreCase(method)) {
                    writeError(resp, 405, "method_not_allowed", "Use POST for enqueue.", requestId);
                    return;
                }
                handleEnqueue(req, resp, tenantUuid, requestedBy, requestId);
                return;
            }
            handleList(req, resp, tenantUuid, requestedBy, requestId);
        } catch (IllegalArgumentException ex) {
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("request_id", requestId);
            details.put("action", safe(action));
            details.put("reason", safe(ex.getMessage()));
            ACTIVITY_LOGS.logWarning("search.job.request_invalid", tenantUuid, requestedBy, "", "", details);
            writeError(resp, 400, "bad_request", safe(ex.getMessage()), requestId);
        } catch (IllegalStateException ex) {
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("request_id", requestId);
            details.put("action", safe(action));
            details.put("reason", safe(ex.getMessage()));
            ACTIVITY_LOGS.logWarning("search.job.request_conflict", tenantUuid, requestedBy, "", "", details);
            writeError(resp, 409, "conflict", safe(ex.getMessage()), requestId);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Search jobs request failed [" + requestId + "]: " + safe(ex.getMessage()), ex);
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("request_id", requestId);
            details.put("action", safe(action));
            details.put("reason", safe(ex.getMessage()));
            ACTIVITY_LOGS.logError("search.job.request_failed", tenantUuid, requestedBy, "", "", details);
            writeError(resp, 500, "server_error", safe(ex.getMessage()), requestId);
        }
    }

    private void handleTypes(HttpServletResponse resp, String requestId, HttpSession sess) throws IOException {
        ArrayList<search_jobs_service.SearchTypeInfo> types = service.listSearchTypes();
        ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
        for (search_jobs_service.SearchTypeInfo type : types) {
            if (type == null) continue;
            String permission = safe(type.permissionKey).trim();
            if (!permission.isBlank() && (sess == null || !users_roles.hasPermissionTrue(sess, permission))) {
                continue;
            }
            items.add(typeMap(type));
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("request_id", requestId);
        out.put("tesseract_available", service.isTesseractAvailable());
        out.put("items", items);
        out.put("count", items.size());
        writeJson(resp, 200, out);
    }

    private void handleList(HttpServletRequest req,
                            HttpServletResponse resp,
                            String tenantUuid,
                            String requestedBy,
                            String requestId) throws IOException {
        int limit = intOrDefault(req.getParameter("limit"), 20);
        boolean includeResults = "1".equals(req.getParameter("include_results"));
        ArrayList<search_jobs_service.SearchJobSnapshot> rows = service.listJobs(
                tenantUuid,
                requestedBy,
                limit,
                includeResults
        );
        ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
        for (search_jobs_service.SearchJobSnapshot row : rows) {
            if (row == null) continue;
            items.add(jobMap(row, includeResults));
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("request_id", requestId);
        out.put("items", items);
        out.put("count", items.size());
        writeJson(resp, 200, out);
    }

    private void handleGetJob(HttpServletRequest req,
                              HttpServletResponse resp,
                              String tenantUuid,
                              String requestedBy,
                              String requestId) throws IOException {
        String jobId = safe(req.getParameter("job_id")).trim();
        if (jobId.isBlank()) throw new IllegalArgumentException("job_id is required.");
        boolean includeResults = !"0".equals(safe(req.getParameter("include_results")).trim());
        search_jobs_service.SearchJobSnapshot row = service.getJob(tenantUuid, requestedBy, jobId, includeResults);
        if (row == null) {
            writeError(resp, 404, "not_found", "Search job not found.", requestId);
            return;
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("request_id", requestId);
        out.put("job", jobMap(row, includeResults));
        writeJson(resp, 200, out);
    }

    private void handleEnqueue(HttpServletRequest req,
                               HttpServletResponse resp,
                               String tenantUuid,
                               String requestedBy,
                               String requestId) throws Exception {
        search_jobs_service.SearchJobRequest in = new search_jobs_service.SearchJobRequest();
        in.tenantUuid = tenantUuid;
        in.requestedBy = requestedBy;
        in.searchType = safe(req.getParameter("search_type")).trim().toLowerCase(Locale.ROOT);
        in.logic = safe(req.getParameter("logic")).trim().toLowerCase(Locale.ROOT);
        in.query = safe(req.getParameter("query"));
        in.operator = safe(req.getParameter("operator")).trim().toLowerCase(Locale.ROOT);
        in.caseSensitive = "1".equals(req.getParameter("case_sensitive")) || "true".equalsIgnoreCase(req.getParameter("case_sensitive"));
        in.includeMetadata = !"0".equals(safe(req.getParameter("include_metadata")).trim());
        in.includeOcr = !"0".equals(safe(req.getParameter("include_ocr")).trim());
        in.maxResults = intOrDefault(req.getParameter("max_results"), 200);
        in.criteria = parseCriteria(safe(req.getParameter("criteria_json")));

        search_jobs_service.SearchTypeInfo type = service.getSearchType(in.searchType);
        if (type == null) throw new IllegalArgumentException("Unknown search type.");
        String permission = safe(type.permissionKey).trim();
        if (!permission.isBlank() && !security.require_permission(permission)) {
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("request_id", requestId);
            details.put("search_type", safe(in.searchType));
            details.put("required_permission", permission);
            ACTIVITY_LOGS.logWarning("search.job.request_permission_denied", tenantUuid, requestedBy, "", "", details);
            return;
        }

        String jobId = service.enqueue(in);
        search_jobs_service.SearchJobSnapshot snapshot = service.getJob(tenantUuid, requestedBy, jobId, false);
        LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
        details.put("request_id", requestId);
        details.put("job_id", safe(jobId));
        details.put("search_type", safe(in.searchType));
        ACTIVITY_LOGS.logVerbose("search.job.request_enqueued", tenantUuid, requestedBy, "", "", details);

        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("request_id", requestId);
        out.put("job_id", jobId);
        out.put("job", jobMap(snapshot, false));
        writeJson(resp, 202, out);
    }

    private static LinkedHashMap<String, Object> typeMap(search_jobs_service.SearchTypeInfo type) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (type == null) return out;
        out.put("key", safe(type.key));
        out.put("label", safe(type.label));
        out.put("description", safe(type.description));
        out.put("permission_key", safe(type.permissionKey));
        out.put("operators", new ArrayList<String>(type.operators == null ? java.util.List.of() : type.operators));
        return out;
    }

    private static LinkedHashMap<String, Object> jobMap(search_jobs_service.SearchJobSnapshot job, boolean includeResults) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (job == null) return out;
        out.put("job_id", safe(job.jobId));
        out.put("tenant_uuid", safe(job.tenantUuid));
        out.put("requested_by", safe(job.requestedBy));
        out.put("search_type", safe(job.searchType));
        out.put("query", safe(job.query));
        out.put("operator", safe(job.operator));
        out.put("logic", safe(job.logic));
        ArrayList<LinkedHashMap<String, Object>> criteria = new ArrayList<LinkedHashMap<String, Object>>();
        for (search_jobs_service.SearchCriterion c : job.criteria) {
            if (c == null) continue;
            criteria.add(criterionMap(c));
        }
        out.put("criteria", criteria);
        out.put("case_sensitive", job.caseSensitive);
        out.put("include_metadata", job.includeMetadata);
        out.put("include_ocr", job.includeOcr);
        out.put("status", safe(job.status));
        out.put("message", safe(job.message));
        out.put("created_at", safe(job.createdAt));
        out.put("started_at", safe(job.startedAt));
        out.put("completed_at", safe(job.completedAt));
        out.put("processed_count", job.processedCount);
        out.put("total_count", job.totalCount);
        out.put("result_count", job.resultCount);
        out.put("truncated", job.truncated);
        out.put("queued_ahead", job.queuedAhead);

        if (includeResults) {
            ArrayList<LinkedHashMap<String, Object>> rows = new ArrayList<LinkedHashMap<String, Object>>();
            for (search_jobs_service.SearchResultRec r : job.results) {
                if (r == null) continue;
                rows.add(resultMap(r));
            }
            out.put("results", rows);
        }
        return out;
    }

    private static LinkedHashMap<String, Object> resultMap(search_jobs_service.SearchResultRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("matter_uuid", safe(r.matterUuid));
        out.put("matter_label", safe(r.matterLabel));
        out.put("document_uuid", safe(r.documentUuid));
        out.put("document_title", safe(r.documentTitle));
        out.put("part_uuid", safe(r.partUuid));
        out.put("part_label", safe(r.partLabel));
        out.put("version_uuid", safe(r.versionUuid));
        out.put("version_label", safe(r.versionLabel));
        out.put("source", safe(r.source));
        out.put("mime_type", safe(r.mimeType));
        out.put("created_at", safe(r.createdAt));
        out.put("created_by", safe(r.createdBy));
        out.put("matched_in", safe(r.matchedIn));
        out.put("snippet", safe(r.snippet));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<search_jobs_service.SearchCriterion> parseCriteria(String criteriaJson) {
        ArrayList<search_jobs_service.SearchCriterion> out = new ArrayList<search_jobs_service.SearchCriterion>();
        String raw = safe(criteriaJson).trim();
        if (raw.isBlank()) return out;
        try {
            Object parsed = JSON.readValue(raw.getBytes(StandardCharsets.UTF_8), Object.class);
            if (!(parsed instanceof List<?> rows)) return out;
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> map)) continue;
                search_jobs_service.SearchCriterion c = new search_jobs_service.SearchCriterion();
                Object q = map.get("query");
                Object o = map.get("operator");
                Object s = map.get("scope");
                c.query = q == null ? "" : safe(String.valueOf(q));
                c.operator = o == null ? "" : safe(String.valueOf(o));
                c.scope = s == null ? "" : safe(String.valueOf(s));
                if (safe(c.query).trim().isBlank()) continue;
                out.add(c);
            }
        } catch (Exception ignored) {
            return new ArrayList<search_jobs_service.SearchCriterion>();
        }
        return out;
    }

    private static LinkedHashMap<String, Object> criterionMap(search_jobs_service.SearchCriterion c) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (c == null) return out;
        out.put("query", safe(c.query));
        out.put("operator", safe(c.operator));
        out.put("scope", safe(c.scope));
        return out;
    }

    private static int intOrDefault(String raw, int d) {
        try {
            return Integer.parseInt(safe(raw).trim());
        } catch (Exception ignored) {
            return d;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void writeJson(HttpServletResponse resp, int status, Object payload) throws IOException {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
        resp.setStatus(status);
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }

    private static void writeError(HttpServletResponse resp,
                                   int status,
                                   String code,
                                   String message,
                                   String requestId) throws IOException {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", false);
        out.put("error", safe(code));
        out.put("message", safe(message));
        out.put("request_id", safe(requestId));
        writeJson(resp, status, out);
    }
}
