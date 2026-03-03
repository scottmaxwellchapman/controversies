package net.familylawandprobate.controversies;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tenant-scoped JSON API for automation clients.
 *
 * Authenticated requests rely on filter.java setting request attributes:
 *  - api.tenant_uuid
 *  - api.credential_id
 *  - api.credential_label
 *  - api.credential_scope
 */
public final class api_servlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(api_servlet.class.getName());

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static final String REQ_TENANT_UUID = "api.tenant_uuid";
    public static final String REQ_CREDENTIAL_ID = "api.credential_id";
    public static final String REQ_CREDENTIAL_LABEL = "api.credential_label";
    public static final String REQ_CREDENTIAL_SCOPE = "api.credential_scope";

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<LinkedHashMap<String, Object>>() {};

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        handle(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        handle(req, resp);
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        handle(req, resp);
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String method = safe(req == null ? "" : req.getMethod()).toUpperCase(Locale.ROOT);
        if ("PATCH".equals(method)) {
            handle(req, resp);
            return;
        }
        super.service(req, resp);
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        handle(req, resp);
    }

    private void handle(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String method = safe(req.getMethod()).toUpperCase(Locale.ROOT);
        String path = normalizePath(req.getPathInfo()); // mapped at /api/*

        if ("/v1/help/readme".equals(path)) {
            writeText(resp, 200, helpReadme(req));
            return;
        }

        if ("/v1/help".equals(path)) {
            writeJson(resp, 200, helpJson(req));
            return;
        }

        if ("/v1/ping".equals(path)) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", true);
            out.put("service", "controversies-api");
            out.put("version", "v1");
            out.put("time", Instant.now().toString());
            writeJson(resp, 200, out);
            return;
        }

        if ("/v1/capabilities".equals(path)) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", true);
            out.put("version", "v1");
            out.put("operations", capabilityItems());
            writeJson(resp, 200, out);
            return;
        }

        String tenantUuid = safe((String) req.getAttribute(REQ_TENANT_UUID)).trim();
        if (tenantUuid.isBlank()) {
            writeError(resp, 401, "unauthorized", "API authentication required.");
            return;
        }

        LinkedHashMap<String, Object> payload = readBodyJson(req);
        String op = resolveOperation(path, req, payload);
        if (op.isBlank()) {
            writeError(resp, 400, "missing_operation", "Operation is required.");
            return;
        }

        LinkedHashMap<String, Object> params = collectParams(req, payload);

        String operation = safe(op).trim().toLowerCase(Locale.ROOT);
        try {
            LinkedHashMap<String, Object> result = executeOperation(
                    operation,
                    params,
                    tenantUuid,
                    safe((String) req.getAttribute(REQ_CREDENTIAL_ID)),
                    safe((String) req.getAttribute(REQ_CREDENTIAL_LABEL)),
                    safe(req.getRemoteAddr()),
                    method
            );

            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", true);
            out.put("version", "v1");
            out.put("operation", operation);
            out.put("tenant_uuid", tenantUuid);
            out.put("result", result);
            writeJson(resp, 200, out);
            auditApiInvocation(
                    tenantUuid,
                    operation,
                    safe((String) req.getAttribute(REQ_CREDENTIAL_ID)),
                    safe(req.getRemoteAddr()),
                    method,
                    true,
                    200,
                    "",
                    ""
            );
        } catch (IllegalArgumentException ex) {
            auditApiInvocation(
                    tenantUuid,
                    operation,
                    safe((String) req.getAttribute(REQ_CREDENTIAL_ID)),
                    safe(req.getRemoteAddr()),
                    method,
                    false,
                    400,
                    "bad_request",
                    safe(ex.getMessage())
            );
            writeError(resp, 400, "bad_request", safe(ex.getMessage()));
        } catch (UnsupportedOperationException ex) {
            auditApiInvocation(
                    tenantUuid,
                    operation,
                    safe((String) req.getAttribute(REQ_CREDENTIAL_ID)),
                    safe(req.getRemoteAddr()),
                    method,
                    false,
                    404,
                    "unknown_operation",
                    safe(ex.getMessage())
            );
            writeError(resp, 404, "unknown_operation", safe(ex.getMessage()));
        } catch (Exception ex) {
            LOG.log(
                    Level.WARNING,
                    "API operation failed op=" + operation + ", tenant=" + tenantUuid + ", credential="
                            + safe((String) req.getAttribute(REQ_CREDENTIAL_ID)) + ", ip=" + safe(req.getRemoteAddr())
                            + ", method=" + method + ", message=" + safe(ex.getMessage()),
                    ex
            );
            auditApiInvocation(
                    tenantUuid,
                    operation,
                    safe((String) req.getAttribute(REQ_CREDENTIAL_ID)),
                    safe(req.getRemoteAddr()),
                    method,
                    false,
                    500,
                    "server_error",
                    safe(ex.getMessage())
            );
            writeError(resp, 500, "server_error", "Internal server error.");
        }
    }

    private static String resolveOperation(String path, HttpServletRequest req, Map<String, Object> payload) {
        String p = normalizePath(path);

        if (p.startsWith("/v1/op/")) {
            String raw = safe(p.substring("/v1/op/".length())).trim();
            return raw.replace('/', '.');
        }

        if ("/v1/execute".equals(p)) {
            String op = safe(req.getParameter("op")).trim();
            if (op.isBlank()) op = safe(req.getParameter("operation")).trim();
            if (op.isBlank() && payload != null) {
                op = safe(asString(payload.get("operation"))).trim();
                if (op.isBlank()) op = safe(asString(payload.get("op"))).trim();
            }
            return op;
        }

        if (p.startsWith("/v1/")) {
            String raw = safe(p.substring("/v1/".length())).trim();
            if (raw.isBlank()) return "";
            return raw.replace('/', '.');
        }
        return "";
    }

    private static LinkedHashMap<String, Object> collectParams(HttpServletRequest req, LinkedHashMap<String, Object> payload) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();

        if (req != null) {
            Map<String, String[]> pm = req.getParameterMap();
            if (pm != null) {
                for (Map.Entry<String, String[]> e : pm.entrySet()) {
                    if (e == null) continue;
                    String k = safe(e.getKey()).trim();
                    if (k.isBlank()) continue;
                    if ("operation".equals(k) || "op".equals(k)) continue;
                    String[] vals = e.getValue();
                    if (vals == null || vals.length == 0) {
                        out.put(k, "");
                    } else if (vals.length == 1) {
                        out.put(k, safe(vals[0]));
                    } else {
                        ArrayList<String> xs = new ArrayList<String>(vals.length);
                        for (String v : vals) xs.add(safe(v));
                        out.put(k, xs);
                    }
                }
            }
        }

        if (payload == null || payload.isEmpty()) return out;

        Object nested = payload.get("params");
        if (nested instanceof Map<?, ?> nm) {
            for (Map.Entry<?, ?> e : nm.entrySet()) {
                String k = safe(asString(e.getKey())).trim();
                if (k.isBlank()) continue;
                out.put(k, e.getValue());
            }
        }

        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (e == null) continue;
            String k = safe(e.getKey()).trim();
            if (k.isBlank()) continue;
            if ("operation".equals(k) || "op".equals(k) || "params".equals(k)) continue;
            if (!out.containsKey(k)) out.put(k, e.getValue());
        }

        return out;
    }

    private static LinkedHashMap<String, Object> readBodyJson(HttpServletRequest req) {
        if (req == null) return new LinkedHashMap<String, Object>();
        String ct = safe(req.getContentType()).toLowerCase(Locale.ROOT);
        if (!ct.contains("application/json")) return new LinkedHashMap<String, Object>();
        try {
            byte[] bytes = req.getInputStream().readAllBytes();
            if (bytes == null || bytes.length == 0) return new LinkedHashMap<String, Object>();
            return JSON.readValue(bytes, MAP_TYPE);
        } catch (Exception ignored) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private static LinkedHashMap<String, Object> executeOperation(String op,
                                                                   Map<String, Object> params,
                                                                   String tenantUuid,
                                                                   String credentialId,
                                                                   String credentialLabel,
                                                                   String clientIp,
                                                                   String method) throws Exception {

        String operation = safe(op).trim().toLowerCase(Locale.ROOT);

        LinkedHashMap<String, Object> omnichannel = executeOmnichannelOperation(operation, params, tenantUuid);
        if (omnichannel != null) return omnichannel;

        switch (operation) {
            case "auth.whoami": {
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("tenant_uuid", tenantUuid);
                out.put("credential_id", credentialId);
                out.put("credential_label", credentialLabel);
                out.put("scope", "full_access");
                return out;
            }

            case "activity.recent": {
                int limit = clampInt(intVal(params, "limit", 100), 1, 500);
                List<activity_log.LogEntry> rows = activity_log.defaultStore().recent(tenantUuid, limit);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (activity_log.LogEntry r : rows) {
                    if (r == null) continue;
                    LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("time", r.time);
                    m.put("level", r.level);
                    m.put("action", r.action);
                    m.put("tenant_uuid", r.tenantUuid);
                    m.put("user_uuid", r.userUuid);
                    m.put("case_uuid", r.caseUuid);
                    m.put("document_uuid", r.documentUuid);
                    m.put("details", r.details);
                    items.add(m);
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "api.credentials.list": {
                List<api_credentials.CredentialRec> rows = api_credentials.defaultStore().list(tenantUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (api_credentials.CredentialRec r : rows) {
                    if (r == null) continue;
                    LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("credential_id", r.credentialId);
                    m.put("label", r.label);
                    m.put("scope", r.scope);
                    m.put("created_at", r.createdAt);
                    m.put("created_by_user_uuid", r.createdByUserUuid);
                    m.put("last_used_at", r.lastUsedAt);
                    m.put("last_used_from_ip", r.lastUsedFromIp);
                    m.put("revoked", r.revoked);
                    items.add(m);
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "api.credentials.create": {
                String label = str(params, "label");
                String userUuid = str(params, "created_by_user_uuid");
                api_credentials.GeneratedCredential created = api_credentials.defaultStore().create(tenantUuid, label, userUuid);

                LinkedHashMap<String, Object> c = new LinkedHashMap<String, Object>();
                c.put("credential_id", created.credential.credentialId);
                c.put("label", created.credential.label);
                c.put("scope", created.credential.scope);
                c.put("created_at", created.credential.createdAt);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("credential", c);
                out.put("api_key", created.apiKey);
                out.put("api_secret", created.apiSecret);
                out.put("note", "Store the API secret now. It is not retrievable later.");
                return out;
            }

            case "api.credentials.revoke": {
                String credential = str(params, "credential_id");
                boolean changed = api_credentials.defaultStore().revoke(tenantUuid, credential);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("revoked", changed);
                return out;
            }

            case "tenant.settings.get": {
                tenant_settings store = tenant_settings.defaultStore();
                LinkedHashMap<String, String> settings = store.read(tenantUuid);
                LinkedHashMap<String, String> redacted = redactSecrets(settings);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("settings", redacted);
                return out;
            }

            case "tenant.settings.update": {
                tenant_settings store = tenant_settings.defaultStore();
                LinkedHashMap<String, String> updates = stringMap(params.get("settings"));
                boolean replace = boolVal(params, "replace", false);

                LinkedHashMap<String, String> current = replace ? new LinkedHashMap<String, String>() : store.read(tenantUuid);
                for (Map.Entry<String, String> e : updates.entrySet()) {
                    String k = safe(e.getKey());
                    if (k.isBlank()) continue;
                    current.put(k, safe(e.getValue()));
                }
                store.write(tenantUuid, current);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("saved", true);
                out.put("settings", redactSecrets(store.read(tenantUuid)));
                return out;
            }

            case "tenant.fields.get": {
                Map<String, String> rows = tenant_fields.defaultStore().read(tenantUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("fields", new LinkedHashMap<String, String>(rows));
                out.put("count", rows.size());
                return out;
            }

            case "tenant.fields.update": {
                tenant_fields store = tenant_fields.defaultStore();
                boolean replace = boolVal(params, "replace", false);
                LinkedHashMap<String, String> incoming = stringMap(params.get("fields"));
                LinkedHashMap<String, String> next = replace ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(store.read(tenantUuid));
                next.putAll(incoming);
                store.write(tenantUuid, next);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("saved", true);
                out.put("fields", store.read(tenantUuid));
                return out;
            }

            case "roles.list": {
                users_roles store = users_roles.defaultStore();
                List<users_roles.RoleRec> rows = store.listRoles(tenantUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (users_roles.RoleRec r : rows) {
                    if (r == null) continue;
                    LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("uuid", r.uuid);
                    m.put("enabled", r.enabled);
                    m.put("label", r.label);
                    m.put("permissions", new LinkedHashMap<String, String>(r.permissions));
                    items.add(m);
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "roles.create": {
                String label = str(params, "label");
                boolean enabled = boolVal(params, "enabled", true);
                users_roles.RoleRec rec = users_roles.defaultStore().createRole(tenantUuid, label, enabled);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("role", roleMap(rec));
                return out;
            }

            case "roles.update": {
                users_roles store = users_roles.defaultStore();
                String roleUuid = str(params, "role_uuid");
                boolean changed = false;
                if (hasParam(params, "label")) {
                    changed = store.updateRoleLabel(tenantUuid, roleUuid, str(params, "label")) || changed;
                }
                if (hasParam(params, "enabled")) {
                    changed = store.updateRoleEnabled(tenantUuid, roleUuid, boolVal(params, "enabled", true)) || changed;
                }
                users_roles.RoleRec rec = store.getRoleByUuid(tenantUuid, roleUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("role", roleMap(rec));
                return out;
            }

            case "roles.permission.set": {
                users_roles store = users_roles.defaultStore();
                boolean changed = store.setRolePermission(
                        tenantUuid,
                        str(params, "role_uuid"),
                        str(params, "key"),
                        str(params, "value")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                return out;
            }

            case "roles.permission.remove": {
                users_roles store = users_roles.defaultStore();
                boolean changed = store.removeRolePermission(
                        tenantUuid,
                        str(params, "role_uuid"),
                        str(params, "key")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                return out;
            }

            case "roles.permissions.replace": {
                users_roles store = users_roles.defaultStore();
                LinkedHashMap<String, String> perms = stringMap(params.get("permissions"));
                boolean changed = store.replaceRolePermissions(
                        tenantUuid,
                        str(params, "role_uuid"),
                        perms
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                return out;
            }

            case "users.list": {
                users_roles store = users_roles.defaultStore();
                List<users_roles.UserRec> rows = store.listUsers(tenantUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (users_roles.UserRec r : rows) {
                    if (r == null) continue;
                    LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("uuid", r.uuid);
                    m.put("enabled", r.enabled);
                    m.put("role_uuid", r.roleUuid);
                    m.put("email_address", r.emailAddress);
                    items.add(m);
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "users.create": {
                users_roles store = users_roles.defaultStore();
                users_roles.UserRec rec = store.createUser(
                        tenantUuid,
                        str(params, "email"),
                        str(params, "role_uuid"),
                        boolVal(params, "enabled", true),
                        str(params, "password").toCharArray()
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("user", userMap(rec));
                return out;
            }

            case "users.update": {
                users_roles store = users_roles.defaultStore();
                String userUuid = str(params, "user_uuid");
                boolean changed = false;
                if (hasParam(params, "email")) {
                    changed = store.updateUserEmail(tenantUuid, userUuid, str(params, "email")) || changed;
                }
                if (hasParam(params, "role_uuid")) {
                    changed = store.updateUserRole(tenantUuid, userUuid, str(params, "role_uuid")) || changed;
                }
                if (hasParam(params, "enabled")) {
                    changed = store.updateUserEnabled(tenantUuid, userUuid, boolVal(params, "enabled", true)) || changed;
                }
                if (hasParam(params, "password")) {
                    changed = store.updateUserPassword(tenantUuid, userUuid, str(params, "password").toCharArray()) || changed;
                }
                users_roles.UserRec rec = store.getUserByUuid(tenantUuid, userUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("user", userMap(rec));
                return out;
            }

            case "users.disable": {
                boolean changed = users_roles.defaultStore().disableUser(tenantUuid, str(params, "user_uuid"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                return out;
            }

            case "matters.list": {
                boolean includeTrashed = boolVal(params, "include_trashed", false);
                List<matters.MatterRec> rows = matters.defaultStore().listAll(tenantUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (matters.MatterRec r : rows) {
                    if (r == null) continue;
                    if (!includeTrashed && r.trashed) continue;
                    items.add(matterMap(r));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "matters.get": {
                matters.MatterRec rec = matters.defaultStore().getByUuid(tenantUuid, str(params, "matter_uuid"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("matter", matterMap(rec));
                return out;
            }

            case "matters.create": {
                matters.MatterRec rec = matters.defaultStore().create(
                        tenantUuid,
                        str(params, "label"),
                        str(params, "jurisdiction_uuid"),
                        str(params, "matter_category_uuid"),
                        str(params, "matter_subcategory_uuid"),
                        str(params, "matter_status_uuid"),
                        str(params, "matter_substatus_uuid"),
                        str(params, "cause_docket_number"),
                        str(params, "county")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("matter", matterMap(rec));
                return out;
            }

            case "matters.update": {
                boolean changed = matters.defaultStore().update(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "label"),
                        str(params, "jurisdiction_uuid"),
                        str(params, "matter_category_uuid"),
                        str(params, "matter_subcategory_uuid"),
                        str(params, "matter_status_uuid"),
                        str(params, "matter_substatus_uuid"),
                        str(params, "cause_docket_number"),
                        str(params, "county")
                );
                matters.MatterRec rec = matters.defaultStore().getByUuid(tenantUuid, str(params, "matter_uuid"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("matter", matterMap(rec));
                return out;
            }

            case "matters.trash": {
                boolean changed = matters.defaultStore().trash(tenantUuid, str(params, "matter_uuid"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                return out;
            }

            case "matters.restore": {
                boolean changed = matters.defaultStore().restore(tenantUuid, str(params, "matter_uuid"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                return out;
            }

            case "matters.update_source_metadata": {
                boolean changed = matters.defaultStore().updateSourceMetadata(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "source"),
                        str(params, "source_matter_id"),
                        str(params, "canonical_label"),
                        str(params, "source_updated_at")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                return out;
            }

            case "case.attributes.list": {
                case_attributes store = case_attributes.defaultStore();
                boolean enabledOnly = boolVal(params, "enabled_only", false);
                List<case_attributes.AttributeRec> rows = enabledOnly
                        ? store.listEnabled(tenantUuid)
                        : store.listAll(tenantUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (case_attributes.AttributeRec r : rows) {
                    if (r == null) continue;
                    LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("uuid", r.uuid);
                    m.put("key", r.key);
                    m.put("label", r.label);
                    m.put("data_type", r.dataType);
                    m.put("options", r.options);
                    m.put("required", r.required);
                    m.put("enabled", r.enabled);
                    m.put("built_in", r.builtIn);
                    m.put("sort_order", r.sortOrder);
                    m.put("updated_at", r.updatedAt);
                    items.add(m);
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "case.attributes.save": {
                case_attributes store = case_attributes.defaultStore();
                List<case_attributes.AttributeRec> rows = parseCaseAttributeRows(params.get("rows"));
                store.saveAll(tenantUuid, rows);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("saved", true);
                out.put("count", store.listAll(tenantUuid).size());
                return out;
            }

            case "case.fields.get": {
                String matterUuid = str(params, "matter_uuid");
                Map<String, String> rows = case_fields.defaultStore().read(tenantUuid, matterUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("matter_uuid", matterUuid);
                out.put("fields", new LinkedHashMap<String, String>(rows));
                return out;
            }

            case "case.fields.update": {
                case_fields store = case_fields.defaultStore();
                String matterUuid = str(params, "matter_uuid");
                boolean replace = boolVal(params, "replace", false);
                LinkedHashMap<String, String> incoming = stringMap(params.get("fields"));
                LinkedHashMap<String, String> next = replace ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(store.read(tenantUuid, matterUuid));
                next.putAll(incoming);
                store.write(tenantUuid, matterUuid, next);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("saved", true);
                out.put("fields", store.read(tenantUuid, matterUuid));
                return out;
            }

            case "case.list_items.get": {
                String matterUuid = str(params, "matter_uuid");
                Map<String, String> rows = case_list_items.defaultStore().read(tenantUuid, matterUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("matter_uuid", matterUuid);
                out.put("lists", new LinkedHashMap<String, String>(rows));
                return out;
            }

            case "case.list_items.update": {
                case_list_items store = case_list_items.defaultStore();
                String matterUuid = str(params, "matter_uuid");
                boolean replace = boolVal(params, "replace", false);
                LinkedHashMap<String, String> incoming = stringMap(params.get("lists"));
                LinkedHashMap<String, String> next = replace ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(store.read(tenantUuid, matterUuid));
                next.putAll(incoming);
                store.write(tenantUuid, matterUuid, next);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("saved", true);
                out.put("lists", store.read(tenantUuid, matterUuid));
                return out;
            }

            case "document.taxonomy.get": {
                document_taxonomy.Taxonomy tx = document_taxonomy.defaultStore().read(tenantUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("categories", new ArrayList<String>(tx.categories));
                out.put("subcategories", new ArrayList<String>(tx.subcategories));
                out.put("statuses", new ArrayList<String>(tx.statuses));
                return out;
            }

            case "document.taxonomy.add": {
                document_taxonomy.defaultStore().addValues(
                        tenantUuid,
                        stringList(params.get("categories")),
                        stringList(params.get("subcategories")),
                        stringList(params.get("statuses"))
                );
                document_taxonomy.Taxonomy tx = document_taxonomy.defaultStore().read(tenantUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("saved", true);
                out.put("categories", new ArrayList<String>(tx.categories));
                out.put("subcategories", new ArrayList<String>(tx.subcategories));
                out.put("statuses", new ArrayList<String>(tx.statuses));
                return out;
            }

            case "document.attributes.list": {
                document_attributes store = document_attributes.defaultStore();
                boolean enabledOnly = boolVal(params, "enabled_only", false);
                List<document_attributes.AttributeRec> rows = enabledOnly
                        ? store.listEnabled(tenantUuid)
                        : store.listAll(tenantUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (document_attributes.AttributeRec r : rows) {
                    if (r == null) continue;
                    LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("uuid", r.uuid);
                    m.put("key", r.key);
                    m.put("label", r.label);
                    m.put("data_type", r.dataType);
                    m.put("options", r.options);
                    m.put("required", r.required);
                    m.put("enabled", r.enabled);
                    m.put("sort_order", r.sortOrder);
                    m.put("updated_at", r.updatedAt);
                    items.add(m);
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "document.attributes.save": {
                document_attributes store = document_attributes.defaultStore();
                List<document_attributes.AttributeRec> rows = parseDocumentAttributeRows(params.get("rows"));
                store.saveAll(tenantUuid, rows);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("saved", true);
                out.put("count", store.listAll(tenantUuid).size());
                return out;
            }

            case "documents.list": {
                documents store = documents.defaultStore();
                String matterUuid = str(params, "matter_uuid");
                boolean includeTrashed = boolVal(params, "include_trashed", false);
                List<documents.DocumentRec> rows = store.listAll(tenantUuid, matterUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (documents.DocumentRec r : rows) {
                    if (r == null) continue;
                    if (!includeTrashed && r.trashed) continue;
                    items.add(documentMap(r));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "documents.get": {
                documents.DocumentRec rec = documents.defaultStore().get(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "doc_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("document", documentMap(rec));
                return out;
            }

            case "documents.create": {
                documents.DocumentRec rec = documents.defaultStore().create(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "title"),
                        str(params, "category"),
                        str(params, "subcategory"),
                        str(params, "status"),
                        str(params, "owner"),
                        str(params, "privilege_level"),
                        str(params, "filed_on"),
                        str(params, "external_reference"),
                        str(params, "notes")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("document", documentMap(rec));
                return out;
            }

            case "documents.update": {
                documents store = documents.defaultStore();
                documents.DocumentRec rec = new documents.DocumentRec();
                rec.uuid = str(params, "doc_uuid");
                rec.title = str(params, "title");
                rec.category = str(params, "category");
                rec.subcategory = str(params, "subcategory");
                rec.status = str(params, "status");
                rec.owner = str(params, "owner");
                rec.privilegeLevel = str(params, "privilege_level");
                rec.filedOn = str(params, "filed_on");
                rec.externalReference = str(params, "external_reference");
                rec.notes = str(params, "notes");

                boolean changed = store.update(tenantUuid, str(params, "matter_uuid"), rec);
                documents.DocumentRec updated = store.get(tenantUuid, str(params, "matter_uuid"), str(params, "doc_uuid"));

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("document", documentMap(updated));
                return out;
            }

            case "documents.trash": {
                boolean changed = documents.defaultStore().setTrashed(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "doc_uuid"),
                        true
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                return out;
            }

            case "documents.restore": {
                boolean changed = documents.defaultStore().setTrashed(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "doc_uuid"),
                        false
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                return out;
            }

            case "document.fields.get": {
                Map<String, String> rows = document_fields.defaultStore().read(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "doc_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("fields", new LinkedHashMap<String, String>(rows));
                return out;
            }

            case "document.fields.update": {
                document_fields store = document_fields.defaultStore();
                String matterUuid = str(params, "matter_uuid");
                String docUuid = str(params, "doc_uuid");
                boolean replace = boolVal(params, "replace", false);
                LinkedHashMap<String, String> incoming = stringMap(params.get("fields"));
                LinkedHashMap<String, String> next = replace
                        ? new LinkedHashMap<String, String>()
                        : new LinkedHashMap<String, String>(store.read(tenantUuid, matterUuid, docUuid));
                next.putAll(incoming);
                store.write(tenantUuid, matterUuid, docUuid, next);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("saved", true);
                out.put("fields", store.read(tenantUuid, matterUuid, docUuid));
                return out;
            }

            case "document.parts.list": {
                document_parts store = document_parts.defaultStore();
                boolean includeTrashed = boolVal(params, "include_trashed", false);
                List<document_parts.PartRec> rows = store.listAll(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "doc_uuid")
                );
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (document_parts.PartRec r : rows) {
                    if (r == null) continue;
                    if (!includeTrashed && r.trashed) continue;
                    items.add(partMap(r));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "document.parts.get": {
                document_parts.PartRec rec = document_parts.defaultStore().get(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "doc_uuid"),
                        str(params, "part_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("part", partMap(rec));
                return out;
            }

            case "document.parts.create": {
                document_parts.PartRec rec = document_parts.defaultStore().create(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "doc_uuid"),
                        str(params, "label"),
                        str(params, "category"),
                        str(params, "sequence"),
                        str(params, "confidentiality"),
                        str(params, "author"),
                        str(params, "notes")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("part", partMap(rec));
                return out;
            }

            case "document.parts.trash": {
                boolean changed = document_parts.defaultStore().setTrashed(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "doc_uuid"),
                        str(params, "part_uuid"),
                        true
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                return out;
            }

            case "document.parts.restore": {
                boolean changed = document_parts.defaultStore().setTrashed(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "doc_uuid"),
                        str(params, "part_uuid"),
                        false
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                return out;
            }

            case "document.versions.list": {
                List<part_versions.VersionRec> rows = part_versions.defaultStore().listAll(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "doc_uuid"),
                        str(params, "part_uuid")
                );
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (part_versions.VersionRec r : rows) {
                    if (r == null) continue;
                    LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("uuid", r.uuid);
                    m.put("version_label", r.versionLabel);
                    m.put("source", r.source);
                    m.put("mime_type", r.mimeType);
                    m.put("checksum", r.checksum);
                    m.put("file_size_bytes", r.fileSizeBytes);
                    m.put("storage_path", r.storagePath);
                    m.put("created_by", r.createdBy);
                    m.put("notes", r.notes);
                    m.put("created_at", r.createdAt);
                    m.put("current", r.current);
                    items.add(m);
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "document.versions.create": {
                part_versions.VersionRec rec = part_versions.defaultStore().create(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "doc_uuid"),
                        str(params, "part_uuid"),
                        str(params, "version_label"),
                        str(params, "source"),
                        str(params, "mime_type"),
                        str(params, "checksum"),
                        str(params, "file_size_bytes"),
                        str(params, "storage_path"),
                        str(params, "created_by"),
                        str(params, "notes"),
                        boolVal(params, "make_current", true)
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("version", versionMap(rec));
                return out;
            }

            case "document.versions.render_page": {
                String matterUuid = str(params, "matter_uuid");
                String docUuid = str(params, "doc_uuid");
                String partUuid = str(params, "part_uuid");
                String sourceVersionUuid = str(params, "source_version_uuid");
                int page = intVal(params, "page", 0);

                List<part_versions.VersionRec> rows = part_versions.defaultStore().listAll(
                        tenantUuid,
                        matterUuid,
                        docUuid,
                        partUuid
                );
                part_versions.VersionRec source = findPartVersion(rows, sourceVersionUuid);
                if (source == null) throw new IllegalArgumentException("Source version not found.");
                if (!pdf_redaction_service.isPdfVersion(source)) {
                    throw new IllegalArgumentException("Source version is not a PDF.");
                }

                Path sourcePath = pdf_redaction_service.resolveStoragePath(source.storagePath);
                pdf_redaction_service.requirePathWithinTenant(sourcePath, tenantUuid, "Source PDF path");
                if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
                    throw new IllegalArgumentException("Source PDF file not found.");
                }

                pdf_redaction_service.RenderedPage rendered = pdf_redaction_service.renderPage(sourcePath, page);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("source_version", versionMap(source));
                out.put("page_index", rendered.pageIndex);
                out.put("page_number", rendered.pageIndex + 1);
                out.put("total_pages", rendered.totalPages);
                out.put("has_prev", rendered.pageIndex > 0);
                out.put("has_next", rendered.pageIndex + 1 < rendered.totalPages);
                out.put("image_width_px", rendered.imageWidthPx);
                out.put("image_height_px", rendered.imageHeightPx);
                out.put("image_png_base64", encodeBase64(rendered.pngBytes));
                return out;
            }

            case "document.versions.redact": {
                String matterUuid = str(params, "matter_uuid");
                String docUuid = str(params, "doc_uuid");
                String partUuid = str(params, "part_uuid");
                String sourceVersionUuid = str(params, "source_version_uuid");

                List<part_versions.VersionRec> rows = part_versions.defaultStore().listAll(
                        tenantUuid,
                        matterUuid,
                        docUuid,
                        partUuid
                );
                part_versions.VersionRec source = findPartVersion(rows, sourceVersionUuid);
                if (source == null) throw new IllegalArgumentException("Source version not found.");
                if (!pdf_redaction_service.isPdfVersion(source)) {
                    throw new IllegalArgumentException("Source version is not a PDF.");
                }

                Path sourcePath = pdf_redaction_service.resolveStoragePath(source.storagePath);
                pdf_redaction_service.requirePathWithinTenant(sourcePath, tenantUuid, "Source PDF path");
                if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
                    throw new IllegalArgumentException("Source PDF file not found.");
                }

                List<pdf_redaction_service.RedactionRectNorm> normalized = pdf_redaction_service.parseNormalizedObjects(params.get("redactions"));
                if (normalized.isEmpty()) {
                    normalized = pdf_redaction_service.parseNormalizedPayload(str(params, "redactions_payload"));
                }
                if (normalized.isEmpty()) {
                    throw new IllegalArgumentException("At least one redaction rectangle is required.");
                }

                List<pdf_redaction_service.RedactionRectPt> rects = pdf_redaction_service.toPageCoordinates(sourcePath, normalized);
                if (rects.isEmpty()) {
                    throw new IllegalArgumentException("No valid redaction rectangles after page coordinate conversion.");
                }

                Path partFolder = document_parts.defaultStore().partFolder(tenantUuid, matterUuid, docUuid, partUuid);
                if (partFolder == null) throw new IllegalArgumentException("Part folder unavailable.");
                Path outputDir = partFolder.resolve("version_files");
                Files.createDirectories(outputDir);

                String sourceFileName = sourcePath.getFileName() == null ? "document.pdf" : sourcePath.getFileName().toString();
                String outputName = pdf_redaction_service.suggestRedactedFileName(sourceFileName);
                String outputVersionUuid = UUID.randomUUID().toString();
                Path outputPath = outputDir.resolve(outputVersionUuid + "__" + outputName);

                pdf_redaction_service.RedactionRun run = pdf_redaction_service.redact(sourcePath, outputPath, rects);
                long outputBytes = Files.size(outputPath);
                String outputSha = pdf_redaction_service.sha256(outputPath);

                String versionLabel = str(params, "version_label");
                if (versionLabel.isBlank()) {
                    String base = safe(source.versionLabel).trim();
                    if (base.isBlank()) base = "PDF";
                    versionLabel = base + " (Redacted)";
                }
                String notes = str(params, "notes");
                if (!run.usedPdfRedactor) {
                    String fallback = "Rendered with PDFBox rasterized redaction burn-in.";
                    notes = notes.isBlank() ? fallback : (notes + " " + fallback);
                }

                part_versions.VersionRec created = part_versions.defaultStore().create(
                        tenantUuid,
                        matterUuid,
                        docUuid,
                        partUuid,
                        versionLabel,
                        "redacted",
                        "application/pdf",
                        outputSha,
                        String.valueOf(outputBytes),
                        outputPath.toUri().toString(),
                        str(params, "created_by"),
                        notes,
                        boolVal(params, "make_current", true)
                );

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("version", versionMap(created));
                out.put("source_version_uuid", sourceVersionUuid);
                out.put("used_pdfredactor", run.usedPdfRedactor);
                out.put("redaction_count", run.appliedRectCount);
                return out;
            }

            case "templates.list": {
                List<form_templates.TemplateRec> rows = form_templates.defaultStore().list(tenantUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (form_templates.TemplateRec r : rows) {
                    if (r == null) continue;
                    items.add(templateMap(r));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "templates.get": {
                form_templates.TemplateRec rec = form_templates.defaultStore().get(tenantUuid, str(params, "template_uuid"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("template", templateMap(rec));
                return out;
            }

            case "templates.content.get": {
                form_templates store = form_templates.defaultStore();
                String templateUuid = str(params, "template_uuid");
                form_templates.TemplateRec rec = store.get(tenantUuid, templateUuid);
                byte[] bytes = store.readBytes(tenantUuid, templateUuid);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("template", templateMap(rec));
                out.put("content_base64", encodeBase64(bytes));
                return out;
            }

            case "templates.create": {
                form_templates store = form_templates.defaultStore();
                String fileName = str(params, "file_name");
                String label = str(params, "label");
                String folder = str(params, "folder_path");
                byte[] bytes = decodeBase64(str(params, "content_base64"));
                form_templates.TemplateRec rec = store.create(tenantUuid, label, folder, fileName, bytes);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("template", templateMap(rec));
                return out;
            }

            case "templates.update_meta": {
                form_templates store = form_templates.defaultStore();
                String templateUuid = str(params, "template_uuid");
                String label = str(params, "label");
                String folder = str(params, "folder_path");
                boolean changed;
                if (folder.isBlank()) {
                    changed = store.updateMeta(tenantUuid, templateUuid, label);
                } else {
                    changed = store.updateMeta(tenantUuid, templateUuid, label, folder);
                }
                form_templates.TemplateRec rec = store.get(tenantUuid, templateUuid);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("template", templateMap(rec));
                return out;
            }

            case "templates.replace_file": {
                form_templates store = form_templates.defaultStore();
                String templateUuid = str(params, "template_uuid");
                String fileName = str(params, "file_name");
                byte[] bytes = decodeBase64(str(params, "content_base64"));
                boolean changed = store.replaceFile(tenantUuid, templateUuid, fileName, bytes);
                form_templates.TemplateRec rec = store.get(tenantUuid, templateUuid);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("template", templateMap(rec));
                return out;
            }

            case "templates.delete": {
                boolean changed = form_templates.defaultStore().delete(tenantUuid, str(params, "template_uuid"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("deleted", changed);
                return out;
            }

            case "template.tools.delete_text_and_above": {
                form_templates store = form_templates.defaultStore();
                String templateUuid = str(params, "template_uuid");
                form_templates.TemplateRec rec = store.get(tenantUuid, templateUuid);
                if (rec == null) throw new IllegalArgumentException("Template not found.");

                byte[] current = store.readBytes(tenantUuid, templateUuid);
                byte[] next = new document_assembler().deleteTextAndAbove(current, rec.fileExt, str(params, "anchor_text"));
                boolean changed = store.replaceFile(tenantUuid, templateUuid, rec.fileName, next);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("template", templateMap(store.get(tenantUuid, templateUuid)));
                return out;
            }

            case "template.tools.delete_text_and_below": {
                form_templates store = form_templates.defaultStore();
                String templateUuid = str(params, "template_uuid");
                form_templates.TemplateRec rec = store.get(tenantUuid, templateUuid);
                if (rec == null) throw new IllegalArgumentException("Template not found.");

                byte[] current = store.readBytes(tenantUuid, templateUuid);
                byte[] next = new document_assembler().deleteTextAndBelow(current, rec.fileExt, str(params, "anchor_text"));
                boolean changed = store.replaceFile(tenantUuid, templateUuid, rec.fileName, next);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("template", templateMap(store.get(tenantUuid, templateUuid)));
                return out;
            }

            case "template.tools.normalize_font_family": {
                form_templates store = form_templates.defaultStore();
                String templateUuid = str(params, "template_uuid");
                form_templates.TemplateRec rec = store.get(tenantUuid, templateUuid);
                if (rec == null) throw new IllegalArgumentException("Template not found.");

                byte[] current = store.readBytes(tenantUuid, templateUuid);
                byte[] next = new document_assembler().normalizeFontFamily(current, rec.fileExt, str(params, "font_family"));
                boolean changed = store.replaceFile(tenantUuid, templateUuid, rec.fileName, next);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("template", templateMap(store.get(tenantUuid, templateUuid)));
                return out;
            }

            case "assembler.preview": {
                AssemblerInputs in = resolveAssemblerInputs(tenantUuid, params);
                document_assembler.PreviewResult pr = new document_assembler().preview(in.templateBytes, in.templateExtOrName, in.values);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("template_ext", in.templateExtOrName);
                out.put("source_text", pr.sourceText);
                out.put("assembled_text", pr.assembledText);
                out.put("used_tokens", new ArrayList<String>(pr.usedTokens));
                out.put("missing_tokens", new ArrayList<String>(pr.missingTokens));
                out.put("token_counts", new LinkedHashMap<String, Integer>(pr.tokenCounts));
                return out;
            }

            case "assembler.assemble": {
                AssemblerInputs in = resolveAssemblerInputs(tenantUuid, params);
                document_assembler.AssembledFile af = new document_assembler().assemble(in.templateBytes, in.templateExtOrName, in.values);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("output_base64", encodeBase64(af.bytes));
                out.put("output_extension", af.extension);
                out.put("content_type", af.contentType);
                out.put("output_file_name", suggestOutputFileName(str(params, "output_file_name"), in.templateLabel, af.extension));
                return out;
            }

            case "assembly.run": {
                String matterUuid = str(params, "matter_uuid");
                String templateUuid = str(params, "template_uuid");
                if (matterUuid.isBlank() || templateUuid.isBlank()) {
                    throw new IllegalArgumentException("matter_uuid and template_uuid are required.");
                }

                AssemblerInputs in = resolveAssemblerInputs(tenantUuid, params);
                document_assembler assembler = new document_assembler();
                document_assembler.AssembledFile af = assembler.assemble(in.templateBytes, in.templateExtOrName, in.values);

                assembled_forms store = assembled_forms.defaultStore();
                String userUuid = str(params, "user_uuid");
                String userEmail = str(params, "user_email");
                String preferredAssemblyUuid = str(params, "assembly_uuid");
                String outputName = suggestOutputFileName(str(params, "output_file_name"), in.templateLabel, af.extension);
                assembled_forms.AssemblyRec rec = store.markCompleted(
                        tenantUuid,
                        matterUuid,
                        preferredAssemblyUuid,
                        templateUuid,
                        in.templateLabel,
                        in.templateExtOrName,
                        userUuid,
                        userEmail,
                        in.overrides,
                        outputName,
                        af.extension,
                        af.bytes
                );

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("assembly", assemblyMap(rec));
                out.put("output_extension", af.extension);
                out.put("content_type", af.contentType);
                out.put("output_file_name", outputName);
                if (boolVal(params, "include_output", false)) {
                    out.put("output_base64", encodeBase64(af.bytes));
                }
                return out;
            }

            case "assembled_forms.list": {
                List<assembled_forms.AssemblyRec> rows = assembled_forms.defaultStore().listByMatter(
                        tenantUuid,
                        str(params, "matter_uuid")
                );
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (assembled_forms.AssemblyRec r : rows) {
                    if (r == null) continue;
                    items.add(assemblyMap(r));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "assembled_forms.get": {
                assembled_forms.AssemblyRec rec = assembled_forms.defaultStore().getByUuid(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "assembly_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("assembly", assemblyMap(rec));
                return out;
            }

            case "assembled_forms.sync_state": {
                String state = assembled_forms.defaultStore().syncState(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "assembly_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("state", state);
                return out;
            }

            case "assembled_forms.retry_sync": {
                boolean queued = assembled_forms.defaultStore().retrySyncNow(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "assembly_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("queued", queued);
                return out;
            }

            case "custom_objects.list": {
                custom_objects store = custom_objects.defaultStore();
                boolean publishedOnly = boolVal(params, "published_only", false);
                List<custom_objects.ObjectRec> rows = publishedOnly
                        ? store.listPublished(tenantUuid)
                        : store.listAll(tenantUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (custom_objects.ObjectRec r : rows) {
                    if (r == null) continue;
                    items.add(customObjectMap(r));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "custom_objects.get": {
                custom_objects.ObjectRec rec = custom_objects.defaultStore().getByUuid(tenantUuid, str(params, "object_uuid"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("object", customObjectMap(rec));
                return out;
            }

            case "custom_objects.save": {
                custom_objects store = custom_objects.defaultStore();
                List<custom_objects.ObjectRec> rows = parseCustomObjectRows(params.get("rows"));
                store.saveAll(tenantUuid, rows);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("saved", true);
                out.put("count", store.listAll(tenantUuid).size());
                return out;
            }

            case "custom_objects.set_published": {
                boolean changed = custom_objects.defaultStore().setPublished(
                        tenantUuid,
                        str(params, "object_uuid"),
                        boolVal(params, "published", false)
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                return out;
            }

            case "custom_object_attributes.list": {
                custom_object_attributes store = custom_object_attributes.defaultStore();
                boolean enabledOnly = boolVal(params, "enabled_only", false);
                String objectUuid = str(params, "object_uuid");
                List<custom_object_attributes.AttributeRec> rows = enabledOnly
                        ? store.listEnabled(tenantUuid, objectUuid)
                        : store.listAll(tenantUuid, objectUuid);

                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (custom_object_attributes.AttributeRec r : rows) {
                    if (r == null) continue;
                    LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("uuid", r.uuid);
                    m.put("key", r.key);
                    m.put("label", r.label);
                    m.put("data_type", r.dataType);
                    m.put("options", r.options);
                    m.put("required", r.required);
                    m.put("enabled", r.enabled);
                    m.put("sort_order", r.sortOrder);
                    m.put("updated_at", r.updatedAt);
                    items.add(m);
                }

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "custom_object_attributes.save": {
                custom_object_attributes store = custom_object_attributes.defaultStore();
                String objectUuid = str(params, "object_uuid");
                List<custom_object_attributes.AttributeRec> rows = parseCustomObjectAttributeRows(params.get("rows"));
                store.saveAll(tenantUuid, objectUuid, rows);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("saved", true);
                out.put("count", store.listAll(tenantUuid, objectUuid).size());
                return out;
            }

            case "custom_object_records.list": {
                custom_object_records store = custom_object_records.defaultStore();
                String objectUuid = str(params, "object_uuid");
                boolean includeTrashed = boolVal(params, "include_trashed", false);
                List<custom_object_records.RecordRec> rows = store.listAll(tenantUuid, objectUuid);

                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (custom_object_records.RecordRec r : rows) {
                    if (r == null) continue;
                    if (!includeTrashed && r.trashed) continue;
                    items.add(customObjectRecordMap(r));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "custom_object_records.get": {
                custom_object_records.RecordRec rec = custom_object_records.defaultStore().getByUuid(
                        tenantUuid,
                        str(params, "object_uuid"),
                        str(params, "record_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("record", customObjectRecordMap(rec));
                return out;
            }

            case "custom_object_records.create": {
                custom_object_records.RecordRec rec = custom_object_records.defaultStore().create(
                        tenantUuid,
                        str(params, "object_uuid"),
                        str(params, "label"),
                        stringMap(params.get("values"))
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("record", customObjectRecordMap(rec));
                return out;
            }

            case "custom_object_records.update": {
                custom_object_records store = custom_object_records.defaultStore();
                String objectUuid = str(params, "object_uuid");
                String recordUuid = str(params, "record_uuid");
                boolean changed = store.update(
                        tenantUuid,
                        objectUuid,
                        recordUuid,
                        str(params, "label"),
                        stringMap(params.get("values"))
                );
                custom_object_records.RecordRec rec = store.getByUuid(tenantUuid, objectUuid, recordUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("record", customObjectRecordMap(rec));
                return out;
            }

            case "custom_object_records.set_trashed": {
                boolean changed = custom_object_records.defaultStore().setTrashed(
                        tenantUuid,
                        str(params, "object_uuid"),
                        str(params, "record_uuid"),
                        boolVal(params, "trashed", true)
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                return out;
            }

            case "bpm.processes.list": {
                business_process_manager bpm = business_process_manager.defaultService();
                boolean includeDisabled = boolVal(params, "include_disabled", true);
                List<business_process_manager.ProcessDefinition> rows = bpm.listProcesses(tenantUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (business_process_manager.ProcessDefinition row : rows) {
                    if (row == null) continue;
                    if (!includeDisabled && !row.enabled) continue;
                    items.add(bpmProcessMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "bpm.actions.catalog": {
                List<LinkedHashMap<String, Object>> rows = business_process_manager.defaultService().listBuiltInActions();
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", rows);
                out.put("count", rows.size());
                return out;
            }

            case "bpm.processes.get": {
                business_process_manager.ProcessDefinition row = business_process_manager.defaultService().getProcess(
                        tenantUuid,
                        str(params, "process_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("process", bpmProcessMap(row));
                return out;
            }

            case "bpm.processes.save": {
                business_process_manager.ProcessDefinition parsed = parseBpmProcess(
                        mapFrom(params.get("process"), params)
                );
                business_process_manager.ProcessDefinition saved = business_process_manager.defaultService()
                        .saveProcess(tenantUuid, parsed);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("saved", true);
                out.put("process", bpmProcessMap(saved));
                return out;
            }

            case "bpm.processes.delete": {
                boolean deleted = business_process_manager.defaultService().deleteProcess(
                        tenantUuid,
                        str(params, "process_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("deleted", deleted);
                return out;
            }

            case "bpm.events.trigger": {
                business_process_manager bpm = business_process_manager.defaultService();
                String processUuid = str(params, "process_uuid");
                String eventType = str(params, "event_type");
                if (eventType.isBlank()) eventType = "manual";
                LinkedHashMap<String, String> payload = stringMap(params.get("payload"));
                String actorUserUuid = str(params, "actor_user_uuid");
                String source = str(params, "source");
                if (source.isBlank()) source = "api.bpm.events.trigger";

                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                if (processUuid.isBlank()) {
                    List<business_process_manager.RunResult> rows = bpm.triggerEvent(
                            tenantUuid,
                            eventType,
                            payload,
                            actorUserUuid,
                            source
                    );
                    for (business_process_manager.RunResult row : rows) {
                        if (row == null) continue;
                        items.add(bpmRunMap(row));
                    }
                } else {
                    business_process_manager.RunResult row = bpm.triggerProcess(
                            tenantUuid,
                            processUuid,
                            eventType,
                            payload,
                            actorUserUuid,
                            source
                    );
                    items.add(bpmRunMap(row));
                }

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "bpm.runs.list": {
                List<business_process_manager.RunResult> rows = business_process_manager.defaultService().listRuns(
                        tenantUuid,
                        clampInt(intVal(params, "limit", 100), 1, 500)
                );
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (business_process_manager.RunResult row : rows) {
                    if (row == null) continue;
                    items.add(bpmRunMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "bpm.runs.get": {
                business_process_manager.RunResult row = business_process_manager.defaultService().getRunResult(
                        tenantUuid,
                        str(params, "run_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("run", bpmRunMap(row));
                return out;
            }

            case "bpm.runs.undo": {
                business_process_manager.RunResult row = business_process_manager.defaultService().undoRun(
                        tenantUuid,
                        str(params, "run_uuid"),
                        str(params, "actor_user_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("run", bpmRunMap(row));
                return out;
            }

            case "bpm.runs.redo": {
                business_process_manager.RunResult row = business_process_manager.defaultService().redoRun(
                        tenantUuid,
                        str(params, "run_uuid"),
                        str(params, "actor_user_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("run", bpmRunMap(row));
                return out;
            }

            case "bpm.reviews.list": {
                List<business_process_manager.HumanReviewTask> rows = business_process_manager.defaultService().listReviews(
                        tenantUuid,
                        boolVal(params, "pending_only", true),
                        clampInt(intVal(params, "limit", 100), 1, 500)
                );
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (business_process_manager.HumanReviewTask row : rows) {
                    if (row == null) continue;
                    items.add(bpmReviewMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "bpm.reviews.complete": {
                business_process_manager.HumanReviewTask row = business_process_manager.defaultService().completeReview(
                        tenantUuid,
                        str(params, "review_uuid"),
                        boolVal(params, "approved", true),
                        str(params, "reviewed_by_user_uuid"),
                        stringMap(params.get("input")),
                        str(params, "comment")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("review", bpmReviewMap(row));
                return out;
            }

            case "texas_law.status": {
                texas_law_sync sync = texas_law_sync.defaultService();
                Properties status = sync.readStatusSnapshot();
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("running", "true".equalsIgnoreCase(safe(status.getProperty("running"))));
                out.put("last_status", safe(status.getProperty("last_status")));
                out.put("last_error", safe(status.getProperty("last_error")));
                out.put("last_started_at", safe(status.getProperty("last_started_at")));
                out.put("last_completed_at", safe(status.getProperty("last_completed_at")));
                out.put("last_duration_ms", safe(status.getProperty("last_duration_ms")));
                out.put("next_scheduled_at", safe(status.getProperty("next_scheduled_at")));
                out.put("schedule_zone", safe(status.getProperty("schedule_zone")));
                out.put("schedule_time_local", safe(status.getProperty("schedule_time_local")));
                out.put("rules_data_dir", safe(String.valueOf(texas_law_sync.rulesDataDir())));
                out.put("codes_data_dir", safe(String.valueOf(texas_law_sync.codesDataDir())));
                return out;
            }

            case "texas_law.sync_now": {
                boolean started = texas_law_sync.defaultService().triggerManualRun();
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("started", started);
                return out;
            }

            case "texas_law.list_dir": {
                String library = normalizeLibrary(str(params, "library"));
                Path root = texasRoot(library);
                Path current = resolveUnderRoot(root, str(params, "path"));
                if (!Files.isDirectory(current)) current = root;

                ArrayList<LinkedHashMap<String, Object>> dirs = new ArrayList<LinkedHashMap<String, Object>>();
                ArrayList<LinkedHashMap<String, Object>> files = new ArrayList<LinkedHashMap<String, Object>>();

                try (DirectoryStream<Path> ds = Files.newDirectoryStream(current)) {
                    for (Path p : ds) {
                        if (p == null) continue;
                        if (Files.isDirectory(p)) {
                            LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
                            item.put("name", fileName(p));
                            item.put("path", relativize(root, p));
                            dirs.add(item);
                        } else if (Files.isRegularFile(p)) {
                            LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
                            item.put("name", fileName(p));
                            item.put("path", relativize(root, p));
                            long size = 0L;
                            try { size = Files.size(p); } catch (Exception ignored) {}
                            item.put("size_bytes", size);
                            item.put("extension", texas_law_library.extension(p));
                            item.put("renderable", texas_law_library.isRenderable(p));
                            files.add(item);
                        }
                    }
                }

                dirs.sort(Comparator.comparing(a -> safe(asString(a.get("name"))).toLowerCase(Locale.ROOT)));
                files.sort(Comparator.comparing(a -> safe(asString(a.get("name"))).toLowerCase(Locale.ROOT)));

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("library", library);
                out.put("path", relativize(root, current));
                out.put("directories", dirs);
                out.put("files", files);
                return out;
            }

            case "texas_law.search": {
                String library = normalizeLibrary(str(params, "library"));
                Path root = texasRoot(library);
                String q = str(params, "query");
                String extFilter = str(params, "ext_filter");
                String pathFilter = str(params, "path_filter");
                String mode = str(params, "mode").toLowerCase(Locale.ROOT);
                if (!"content".equals(mode) && !"both".equals(mode) && !"filename".equals(mode)) mode = "filename";
                boolean includeFileName = "filename".equals(mode) || "both".equals(mode);
                boolean includeContent = "content".equals(mode) || "both".equals(mode);
                int max = clampInt(intVal(params, "max_results", 200), 1, 500);

                List<texas_law_library.SearchResult> rows = texas_law_library.search(
                        root,
                        q,
                        extFilter,
                        pathFilter,
                        includeFileName,
                        includeContent,
                        max
                );

                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (texas_law_library.SearchResult r : rows) {
                    if (r == null) continue;
                    LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("relative_path", r.relativePath);
                    m.put("file_name", r.fileName);
                    m.put("extension", r.ext);
                    m.put("size_bytes", r.sizeBytes);
                    m.put("match_type", r.matchType);
                    m.put("snippet", r.snippet);
                    items.add(m);
                }

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("library", library);
                out.put("query", q);
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "texas_law.render_page": {
                String library = normalizeLibrary(str(params, "library"));
                Path root = texasRoot(library);
                String rel = str(params, "path");
                int page = Math.max(0, intVal(params, "page", 0));

                Path target = resolveUnderRoot(root, rel);
                if (!Files.isRegularFile(target)) throw new IllegalArgumentException("File not found.");
                if (!texas_law_library.isRenderable(target)) throw new IllegalArgumentException("Viewer supports PDF and DOCX only.");

                texas_law_library.RenderedPage rendered = texas_law_library.renderPage(target, page);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("library", library);
                out.put("path", relativize(root, target));
                out.put("page_index", rendered.pageIndex);
                out.put("total_pages", rendered.totalPages);
                out.put("total_known", rendered.totalKnown);
                out.put("has_prev", rendered.hasPrev);
                out.put("has_next", rendered.hasNext);
                out.put("base64_png", rendered.base64Png);
                out.put("warning", rendered.warning);
                out.put("engine", rendered.engine);
                out.put("page_text", rendered.pageText);

                ArrayList<LinkedHashMap<String, Object>> nav = new ArrayList<LinkedHashMap<String, Object>>();
                for (texas_law_library.NavigationEntry n : rendered.navigation) {
                    if (n == null) continue;
                    LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("type", n.type);
                    m.put("label", n.label);
                    m.put("page_index", n.pageIndex);
                    m.put("target", n.target);
                    m.put("external", n.external);
                    nav.add(m);
                }
                out.put("navigation", nav);
                return out;
            }

            default:
                throw new UnsupportedOperationException("Unknown operation: " + op);
        }
    }

    private static LinkedHashMap<String, Object> executeOmnichannelOperation(String operation,
                                                                              Map<String, Object> params,
                                                                              String tenantUuid) throws Exception {
        String op = safe(operation).trim().toLowerCase(Locale.ROOT);
        if (!op.startsWith("omnichannel.")) return null;

        omnichannel_tickets store = omnichannel_tickets.defaultStore();

        switch (op) {
            case "omnichannel.threads.list": {
                boolean includeArchived = boolVal(params, "include_archived", false);
                String matterFilter = str(params, "matter_uuid");
                String channelFilter = str(params, "channel");
                String statusFilter = str(params, "status");
                String assignedFilter = str(params, "assigned_user_uuid");

                List<omnichannel_tickets.TicketRec> rows = store.listTickets(tenantUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (omnichannel_tickets.TicketRec r : rows) {
                    if (r == null) continue;
                    if (!includeArchived && r.archived) continue;
                    if (!matterFilter.isBlank() && !matterFilter.equalsIgnoreCase(safe(r.matterUuid).trim())) continue;
                    if (!channelFilter.isBlank() && !channelFilter.equalsIgnoreCase(safe(r.channel).trim())) continue;
                    if (!statusFilter.isBlank() && !statusFilter.equalsIgnoreCase(safe(r.status).trim())) continue;
                    if (!assignedFilter.isBlank() && !csvContains(safe(r.assignedUserUuid), assignedFilter)) continue;
                    items.add(threadMap(r));
                }

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "omnichannel.threads.get": {
                String threadUuid = threadUuidParam(params);
                if (threadUuid.isBlank()) throw new IllegalArgumentException("thread_uuid is required.");

                omnichannel_tickets.TicketRec row = store.getTicket(tenantUuid, threadUuid);
                if (row == null) throw new IllegalArgumentException("Thread not found.");

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("thread", threadMap(row));

                if (boolVal(params, "include_details", true)) {
                    ArrayList<LinkedHashMap<String, Object>> messages = new ArrayList<LinkedHashMap<String, Object>>();
                    for (omnichannel_tickets.MessageRec m : store.listMessages(tenantUuid, threadUuid)) {
                        if (m == null) continue;
                        messages.add(threadMessageMap(m));
                    }
                    ArrayList<LinkedHashMap<String, Object>> attachments = new ArrayList<LinkedHashMap<String, Object>>();
                    for (omnichannel_tickets.AttachmentRec a : store.listAttachments(tenantUuid, threadUuid)) {
                        if (a == null) continue;
                        attachments.add(threadAttachmentMap(a));
                    }
                    ArrayList<LinkedHashMap<String, Object>> assignments = new ArrayList<LinkedHashMap<String, Object>>();
                    for (omnichannel_tickets.AssignmentRec a : store.listAssignments(tenantUuid, threadUuid)) {
                        if (a == null) continue;
                        assignments.add(threadAssignmentMap(a));
                    }
                    out.put("messages", messages);
                    out.put("attachments", attachments);
                    out.put("assignments", assignments);
                }
                return out;
            }

            case "omnichannel.threads.create": {
                String assignmentMode = str(params, "assignment_mode");
                String assignedUserUuid = str(params, "assigned_user_uuid");
                if (assignedUserUuid.isBlank()) assignedUserUuid = str(params, "assigned_user_uuids");
                String initialDirection = str(params, "initial_direction");
                if (initialDirection.isBlank()) initialDirection = "inbound";

                if ("round_robin".equalsIgnoreCase(assignmentMode) && assignedUserUuid.isBlank()) {
                    List<String> candidates = csvOrList(params.get("candidate_user_uuids"));
                    if (candidates.isEmpty()) candidates = csvOrList(params.get("round_robin_candidates"));
                    if (!candidates.isEmpty()) {
                        String queueKey = str(params, "round_robin_queue_key");
                        if (queueKey.isBlank()) queueKey = str(params, "round_robin_queue");
                        if (queueKey.isBlank()) queueKey = str(params, "channel") + ":" + str(params, "mailbox_address");
                        assignedUserUuid = store.chooseRoundRobinAssignee(tenantUuid, queueKey, candidates);
                    }
                }

                omnichannel_tickets.TicketRec created = store.createTicket(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "channel"),
                        str(params, "subject"),
                        str(params, "status"),
                        str(params, "priority"),
                        assignmentMode,
                        assignedUserUuid,
                        str(params, "reminder_at"),
                        str(params, "due_at"),
                        str(params, "customer_display"),
                        str(params, "customer_address"),
                        str(params, "mailbox_address"),
                        str(params, "thread_key"),
                        str(params, "external_conversation_id"),
                        initialDirection,
                        str(params, "initial_body"),
                        str(params, "initial_from_address"),
                        str(params, "initial_to_address"),
                        boolVal(params, "initial_mms", false),
                        str(params, "actor_user_uuid"),
                        str(params, "assignment_reason")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("thread", threadMap(created));
                return out;
            }

            case "omnichannel.threads.update": {
                String threadUuid = threadUuidParam(params);
                if (threadUuid.isBlank()) throw new IllegalArgumentException("thread_uuid is required.");

                omnichannel_tickets.TicketRec current = store.getTicket(tenantUuid, threadUuid);
                if (current == null) throw new IllegalArgumentException("Thread not found.");

                omnichannel_tickets.TicketRec update = copyThread(current);
                update.uuid = threadUuid;

                if (hasParam(params, "matter_uuid")) update.matterUuid = str(params, "matter_uuid");
                if (hasParam(params, "channel")) update.channel = str(params, "channel");
                if (hasParam(params, "subject")) update.subject = str(params, "subject");
                if (hasParam(params, "status")) update.status = str(params, "status");
                if (hasParam(params, "priority")) update.priority = str(params, "priority");
                if (hasParam(params, "assignment_mode")) update.assignmentMode = str(params, "assignment_mode");
                if (hasParam(params, "assigned_user_uuid")) update.assignedUserUuid = str(params, "assigned_user_uuid");
                if (hasParam(params, "assigned_user_uuids")) update.assignedUserUuid = str(params, "assigned_user_uuids");
                if (hasParam(params, "reminder_at")) update.reminderAt = str(params, "reminder_at");
                if (hasParam(params, "due_at")) update.dueAt = str(params, "due_at");
                if (hasParam(params, "customer_display")) update.customerDisplay = str(params, "customer_display");
                if (hasParam(params, "customer_address")) update.customerAddress = str(params, "customer_address");
                if (hasParam(params, "mailbox_address")) update.mailboxAddress = str(params, "mailbox_address");
                if (hasParam(params, "thread_key")) update.threadKey = str(params, "thread_key");
                if (hasParam(params, "external_conversation_id")) update.externalConversationId = str(params, "external_conversation_id");
                if (hasParam(params, "mms_enabled")) update.mmsEnabled = boolVal(params, "mms_enabled", update.mmsEnabled);
                if (hasParam(params, "archived")) update.archived = boolVal(params, "archived", update.archived);

                boolean changed = store.updateTicket(
                        tenantUuid,
                        update,
                        str(params, "actor_user_uuid"),
                        str(params, "assignment_reason")
                );
                omnichannel_tickets.TicketRec refreshed = store.getTicket(tenantUuid, threadUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("thread", threadMap(refreshed));
                return out;
            }

            case "omnichannel.threads.set_archived": {
                String threadUuid = threadUuidParam(params);
                if (threadUuid.isBlank()) throw new IllegalArgumentException("thread_uuid is required.");
                boolean archived = boolVal(params, "archived", true);
                boolean changed = store.setArchived(tenantUuid, threadUuid, archived, str(params, "actor_user_uuid"));
                omnichannel_tickets.TicketRec refreshed = store.getTicket(tenantUuid, threadUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("thread", threadMap(refreshed));
                return out;
            }

            case "omnichannel.threads.refresh_report": {
                String threadUuid = threadUuidParam(params);
                if (threadUuid.isBlank()) throw new IllegalArgumentException("thread_uuid is required.");
                omnichannel_tickets.TicketRec refreshed = store.refreshMatterReport(
                        tenantUuid,
                        threadUuid,
                        str(params, "actor_user_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("thread", threadMap(refreshed));
                return out;
            }

            case "omnichannel.messages.list": {
                String threadUuid = threadUuidParam(params);
                if (threadUuid.isBlank()) throw new IllegalArgumentException("thread_uuid is required.");
                List<omnichannel_tickets.MessageRec> rows = store.listMessages(tenantUuid, threadUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (omnichannel_tickets.MessageRec row : rows) {
                    if (row == null) continue;
                    items.add(threadMessageMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "omnichannel.messages.add": {
                String threadUuid = threadUuidParam(params);
                if (threadUuid.isBlank()) throw new IllegalArgumentException("thread_uuid is required.");
                String direction = str(params, "direction");
                if (direction.isBlank()) direction = "inbound";
                omnichannel_tickets.MessageRec row = store.addMessage(
                        tenantUuid,
                        threadUuid,
                        direction,
                        str(params, "body"),
                        boolVal(params, "mms", false),
                        str(params, "from_address"),
                        str(params, "to_address"),
                        str(params, "provider_message_id"),
                        str(params, "email_message_id"),
                        str(params, "email_in_reply_to"),
                        str(params, "email_references"),
                        str(params, "actor_user_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("message", threadMessageMap(row));
                return out;
            }

            case "omnichannel.notes.add": {
                String threadUuid = threadUuidParam(params);
                if (threadUuid.isBlank()) throw new IllegalArgumentException("thread_uuid is required.");
                omnichannel_tickets.MessageRec row = store.addMessage(
                        tenantUuid,
                        threadUuid,
                        "internal",
                        str(params, "body"),
                        false,
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        str(params, "actor_user_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("note", threadMessageMap(row));
                return out;
            }

            case "omnichannel.attachments.list": {
                String threadUuid = threadUuidParam(params);
                if (threadUuid.isBlank()) throw new IllegalArgumentException("thread_uuid is required.");
                List<omnichannel_tickets.AttachmentRec> rows = store.listAttachments(tenantUuid, threadUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (omnichannel_tickets.AttachmentRec row : rows) {
                    if (row == null) continue;
                    items.add(threadAttachmentMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "omnichannel.attachments.add": {
                String threadUuid = threadUuidParam(params);
                if (threadUuid.isBlank()) throw new IllegalArgumentException("thread_uuid is required.");
                String contentBase64 = str(params, "content_base64");
                if (contentBase64.isBlank()) contentBase64 = str(params, "bytes_base64");
                byte[] bytes = decodeBase64(contentBase64);
                if (bytes.length == 0) throw new IllegalArgumentException("content_base64 is required.");

                omnichannel_tickets.AttachmentRec row = store.saveAttachment(
                        tenantUuid,
                        threadUuid,
                        str(params, "message_uuid"),
                        str(params, "file_name"),
                        str(params, "mime_type"),
                        bytes,
                        boolVal(params, "inline_media", false),
                        str(params, "uploaded_by")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("attachment", threadAttachmentMap(row));
                return out;
            }

            case "omnichannel.attachments.get": {
                String threadUuid = threadUuidParam(params);
                String attachmentUuid = str(params, "attachment_uuid");
                if (threadUuid.isBlank() || attachmentUuid.isBlank()) {
                    throw new IllegalArgumentException("thread_uuid and attachment_uuid are required.");
                }
                omnichannel_tickets.AttachmentBlob blob = store.getAttachmentBlob(tenantUuid, threadUuid, attachmentUuid);
                if (blob == null || blob.path == null || !Files.exists(blob.path) || !Files.isRegularFile(blob.path)) {
                    throw new IllegalArgumentException("Attachment not found.");
                }
                byte[] bytes = Files.readAllBytes(blob.path);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("attachment", threadAttachmentMap(blob.attachment));
                out.put("content_base64", encodeBase64(bytes));
                out.put("content_size_bytes", bytes.length);
                return out;
            }

            case "omnichannel.assignments.list": {
                String threadUuid = threadUuidParam(params);
                if (threadUuid.isBlank()) throw new IllegalArgumentException("thread_uuid is required.");
                List<omnichannel_tickets.AssignmentRec> rows = store.listAssignments(tenantUuid, threadUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (omnichannel_tickets.AssignmentRec row : rows) {
                    if (row == null) continue;
                    items.add(threadAssignmentMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "omnichannel.round_robin.next_assignee": {
                String queueKey = str(params, "queue_key");
                List<String> candidates = csvOrList(params.get("candidate_user_uuids"));
                if (candidates.isEmpty()) candidates = csvOrList(params.get("candidates"));
                if (candidates.isEmpty()) throw new IllegalArgumentException("candidate_user_uuids are required.");
                String assignee = store.chooseRoundRobinAssignee(tenantUuid, queueKey, candidates);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("assigned_user_uuid", assignee);
                return out;
            }

            default:
                throw new UnsupportedOperationException("Unknown operation: " + op);
        }
    }

    private static AssemblerInputs resolveAssemblerInputs(String tenantUuid, Map<String, Object> params) throws Exception {
        String templateUuid = str(params, "template_uuid");
        String templateExtOrName = str(params, "template_ext_or_name");
        String matterUuid = str(params, "matter_uuid");

        byte[] templateBytes = new byte[0];
        String templateLabel = str(params, "template_label");

        if (!templateUuid.isBlank()) {
            form_templates store = form_templates.defaultStore();
            form_templates.TemplateRec rec = store.get(tenantUuid, templateUuid);
            if (rec == null) throw new IllegalArgumentException("Template not found.");
            templateBytes = store.readBytes(tenantUuid, templateUuid);
            if (templateBytes.length == 0) throw new IllegalArgumentException("Template body is empty.");
            templateLabel = safe(rec.label);
            if (templateExtOrName.isBlank()) templateExtOrName = safe(rec.fileExt);
        } else {
            templateBytes = decodeBase64(str(params, "template_base64"));
            if (templateBytes.length == 0) throw new IllegalArgumentException("template_base64 is required when template_uuid is not supplied.");
            if (templateExtOrName.isBlank()) templateExtOrName = str(params, "template_file_name");
        }

        LinkedHashMap<String, String> mergedValues = new LinkedHashMap<String, String>();
        if (!matterUuid.isBlank()) {
            mergedValues.putAll(buildMatterMergeValues(tenantUuid, matterUuid));
        }

        LinkedHashMap<String, String> values = stringMap(params.get("values"));
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e == null) continue;
            putToken(mergedValues, safe(e.getKey()), safe(e.getValue()));
        }

        LinkedHashMap<String, String> overrides = stringMap(params.get("overrides"));
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            if (e == null) continue;
            applyLiteralOverride(mergedValues, safe(e.getKey()), safe(e.getValue()));
        }

        if (templateExtOrName.isBlank()) templateExtOrName = "txt";
        return new AssemblerInputs(templateBytes, templateExtOrName, templateLabel, mergedValues, overrides);
    }

    private static LinkedHashMap<String, String> buildMatterMergeValues(String tenantUuid, String matterUuid) throws Exception {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();

        String tenantLabel = "";
        tenants.Tenant t = tenants.defaultStore().getByUuid(tenantUuid);
        if (t != null) tenantLabel = safe(t.label);

        matters.MatterRec m = matters.defaultStore().getByUuid(tenantUuid, matterUuid);

        LinkedHashMap<String, String> tenantKv = new LinkedHashMap<String, String>(tenant_fields.defaultStore().read(tenantUuid));
        LinkedHashMap<String, String> caseKv = new LinkedHashMap<String, String>(case_fields.defaultStore().read(tenantUuid, matterUuid));
        LinkedHashMap<String, String> listKv = new LinkedHashMap<String, String>(case_list_items.defaultStore().read(tenantUuid, matterUuid));
        LinkedHashMap<String, String> settingsKv = new LinkedHashMap<String, String>(tenant_settings.defaultStore().read(tenantUuid));

        putToken(out, "tenant.uuid", tenantUuid);
        putToken(out, "tenant.label", tenantLabel);

        if (m != null) {
            String cause = safe(caseKv.get("cause_docket_number"));
            if (cause.isBlank()) cause = safe(m.causeDocketNumber);
            String county = safe(caseKv.get("county"));
            if (county.isBlank()) county = safe(m.county);

            putToken(out, "case.uuid", safe(m.uuid));
            putToken(out, "case.label", safe(m.label));
            putToken(out, "case.cause_docket_number", cause);
            putToken(out, "case.county", county);
        }

        tenant_fields tenantStore = tenant_fields.defaultStore();
        for (Map.Entry<String, String> e : tenantKv.entrySet()) {
            if (e == null) continue;
            String nk = tenantStore.normalizeKey(e.getKey());
            if (nk.isBlank()) continue;
            String v = safe(e.getValue());
            putToken(out, "tenant." + nk, v);
            if (!out.containsKey("kv." + nk)) putToken(out, "kv." + nk, v);
            if (!out.containsKey(nk)) putToken(out, nk, v);
        }

        case_fields caseStore = case_fields.defaultStore();
        for (Map.Entry<String, String> e : caseKv.entrySet()) {
            if (e == null) continue;
            String nk = caseStore.normalizeKey(e.getKey());
            if (nk.isBlank()) continue;
            String v = safe(e.getValue());
            putToken(out, "case." + nk, v);
            putToken(out, "kv." + nk, v);
            putToken(out, nk, v);
        }

        case_list_items listStore = case_list_items.defaultStore();
        for (Map.Entry<String, String> e : listKv.entrySet()) {
            if (e == null) continue;
            String nk = listStore.normalizeKey(e.getKey());
            if (nk.isBlank()) continue;
            String v = safe(e.getValue());
            putToken(out, "case." + nk, v);
            putToken(out, "kv." + nk, v);
            putToken(out, nk, v);
        }

        boolean advanced = "true".equalsIgnoreCase(safe(settingsKv.get("feature_advanced_assembly")));
        putToken(out, "tenant.advanced_assembly_enabled", advanced ? "true" : "false");
        putToken(out, "advanced_assembly_enabled", advanced ? "true" : "false");
        putToken(out, "kv.advanced_assembly_enabled", advanced ? "true" : "false");

        boolean async = "true".equalsIgnoreCase(safe(settingsKv.get("feature_async_sync")));
        putToken(out, "tenant.async_sync_enabled", async ? "true" : "false");
        putToken(out, "async_sync_enabled", async ? "true" : "false");
        putToken(out, "kv.async_sync_enabled", async ? "true" : "false");

        return out;
    }

    private static List<case_attributes.AttributeRec> parseCaseAttributeRows(Object raw) {
        ArrayList<case_attributes.AttributeRec> out = new ArrayList<case_attributes.AttributeRec>();
        List<Map<String, Object>> rows = objectList(raw);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            if (r == null) continue;
            out.add(new case_attributes.AttributeRec(
                    safe(asString(r.get("uuid"))),
                    safe(asString(r.get("key"))),
                    safe(asString(r.get("label"))),
                    safe(asString(r.get("data_type"))),
                    safe(asString(r.get("options"))),
                    boolVal(r, "required", false),
                    boolVal(r, "enabled", true),
                    boolVal(r, "built_in", false),
                    intVal(r, "sort_order", (i + 1) * 10),
                    safe(asString(r.get("updated_at")))
            ));
        }
        return out;
    }

    private static List<document_attributes.AttributeRec> parseDocumentAttributeRows(Object raw) {
        ArrayList<document_attributes.AttributeRec> out = new ArrayList<document_attributes.AttributeRec>();
        List<Map<String, Object>> rows = objectList(raw);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            if (r == null) continue;
            out.add(new document_attributes.AttributeRec(
                    safe(asString(r.get("uuid"))),
                    safe(asString(r.get("key"))),
                    safe(asString(r.get("label"))),
                    safe(asString(r.get("data_type"))),
                    safe(asString(r.get("options"))),
                    boolVal(r, "required", false),
                    boolVal(r, "enabled", true),
                    intVal(r, "sort_order", (i + 1) * 10),
                    safe(asString(r.get("updated_at")))
            ));
        }
        return out;
    }

    private static List<custom_objects.ObjectRec> parseCustomObjectRows(Object raw) {
        ArrayList<custom_objects.ObjectRec> out = new ArrayList<custom_objects.ObjectRec>();
        List<Map<String, Object>> rows = objectList(raw);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            if (r == null) continue;
            out.add(new custom_objects.ObjectRec(
                    safe(asString(r.get("uuid"))),
                    safe(asString(r.get("key"))),
                    safe(asString(r.get("label"))),
                    safe(asString(r.get("plural_label"))),
                    boolVal(r, "enabled", true),
                    boolVal(r, "published", false),
                    intVal(r, "sort_order", (i + 1) * 10),
                    safe(asString(r.get("updated_at")))
            ));
        }
        return out;
    }

    private static List<custom_object_attributes.AttributeRec> parseCustomObjectAttributeRows(Object raw) {
        ArrayList<custom_object_attributes.AttributeRec> out = new ArrayList<custom_object_attributes.AttributeRec>();
        List<Map<String, Object>> rows = objectList(raw);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            if (r == null) continue;
            out.add(new custom_object_attributes.AttributeRec(
                    safe(asString(r.get("uuid"))),
                    safe(asString(r.get("key"))),
                    safe(asString(r.get("label"))),
                    safe(asString(r.get("data_type"))),
                    safe(asString(r.get("options"))),
                    boolVal(r, "required", false),
                    boolVal(r, "enabled", true),
                    intVal(r, "sort_order", (i + 1) * 10),
                    safe(asString(r.get("updated_at")))
            ));
        }
        return out;
    }

    private static Map<String, Object> mapFrom(Object preferred, Map<String, Object> fallback) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (preferred instanceof Map<?, ?> pm) {
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                if (e == null) continue;
                String k = safe(asString(e.getKey())).trim();
                if (k.isBlank()) continue;
                out.put(k, e.getValue());
            }
            return out;
        }
        if (fallback != null) out.putAll(fallback);
        return out;
    }

    private static business_process_manager.ProcessDefinition parseBpmProcess(Map<String, Object> raw) {
        business_process_manager.ProcessDefinition out = new business_process_manager.ProcessDefinition();
        if (raw == null) return out;

        out.processUuid = safe(asString(raw.get("process_uuid"))).trim();
        if (out.processUuid.isBlank()) out.processUuid = safe(asString(raw.get("uuid"))).trim();
        out.name = safe(asString(raw.get("name"))).trim();
        out.description = safe(asString(raw.get("description"))).trim();
        out.enabled = boolVal(raw, "enabled", true);
        out.updatedAt = safe(asString(raw.get("updated_at"))).trim();

        List<Map<String, Object>> triggerRows = objectList(raw.get("triggers"));
        for (Map<String, Object> row : triggerRows) {
            if (row == null) continue;
            business_process_manager.ProcessTrigger t = new business_process_manager.ProcessTrigger();
            t.type = safe(asString(row.get("type"))).trim();
            t.key = safe(asString(row.get("key"))).trim();
            t.op = safe(asString(row.get("op"))).trim();
            t.value = safe(asString(row.get("value")));
            t.enabled = boolVal(row, "enabled", true);
            if (!t.type.isBlank()) out.triggers.add(t);
        }

        List<Map<String, Object>> stepRows = objectList(raw.get("steps"));
        for (int i = 0; i < stepRows.size(); i++) {
            Map<String, Object> row = stepRows.get(i);
            if (row == null) continue;
            business_process_manager.ProcessStep s = new business_process_manager.ProcessStep();
            s.stepId = safe(asString(row.get("step_id"))).trim();
            if (s.stepId.isBlank()) s.stepId = safe(asString(row.get("id"))).trim();
            if (s.stepId.isBlank()) s.stepId = "step_" + (i + 1);
            s.order = intVal(row, "order", (i + 1) * 10);
            s.action = safe(asString(row.get("action"))).trim();
            s.label = safe(asString(row.get("label"))).trim();
            s.enabled = boolVal(row, "enabled", true);
            s.settings = stringMap(row.get("settings"));

            List<Map<String, Object>> condRows = objectList(row.get("conditions"));
            for (Map<String, Object> cr : condRows) {
                if (cr == null) continue;
                business_process_manager.ProcessCondition c = new business_process_manager.ProcessCondition();
                c.source = safe(asString(cr.get("source"))).trim();
                c.key = safe(asString(cr.get("key"))).trim();
                c.op = safe(asString(cr.get("op"))).trim();
                c.value = safe(asString(cr.get("value")));
                if (!c.key.isBlank()) s.conditions.add(c);
            }

            if (!s.action.isBlank()) out.steps.add(s);
        }

        return out;
    }

    private static LinkedHashMap<String, String> redactSecrets(Map<String, String> in) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (in == null) return out;

        LinkedHashSet<String> secretKeys = new LinkedHashSet<String>();
        secretKeys.add("storage_access_key");
        secretKeys.add("storage_secret");
        secretKeys.add("storage_encryption_key");
        secretKeys.add("clio_client_secret");
        secretKeys.add("clio_access_token");
        secretKeys.add("clio_refresh_token");
        secretKeys.add("email_smtp_password");
        secretKeys.add("email_graph_client_secret");

        for (Map.Entry<String, String> e : in.entrySet()) {
            if (e == null) continue;
            String k = safe(e.getKey());
            if (k.isBlank()) continue;
            String v = safe(e.getValue());
            if (secretKeys.contains(k) && !v.isBlank()) out.put(k, "********");
            else out.put(k, v);
        }
        return out;
    }

    private static LinkedHashMap<String, Object> helpJson(HttpServletRequest req) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("service", "controversies-api");
        out.put("version", "v1");
        out.put("readme", helpReadme(req));
        out.put("operations", capabilityItems());
        return out;
    }

    private static ArrayList<LinkedHashMap<String, Object>> capabilityItems() {
        LinkedHashMap<String, String> ops = capabilityMap();
        ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
        for (Map.Entry<String, String> e : ops.entrySet()) {
            if (e == null) continue;
            LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("operation", e.getKey());
            row.put("summary", e.getValue());
            row.put("endpoint", "/api/v1/op/" + e.getKey().replace('.', '/'));
            items.add(row);
        }
        return items;
    }

    private static LinkedHashMap<String, String> capabilityMap() {
        LinkedHashMap<String, String> ops = new LinkedHashMap<String, String>();

        ops.put("auth.whoami", "Credential identity and tenant scope");
        ops.put("activity.recent", "Recent tenant activity log events");

        ops.put("api.credentials.list", "List API credentials for tenant");
        ops.put("api.credentials.create", "Create API credential (returns key + one-time secret)");
        ops.put("api.credentials.revoke", "Revoke API credential");

        ops.put("tenant.settings.get", "Read tenant settings (secrets redacted)");
        ops.put("tenant.settings.update", "Update tenant settings");
        ops.put("tenant.fields.get", "Read tenant merge fields");
        ops.put("tenant.fields.update", "Update tenant merge fields");

        ops.put("roles.list", "List roles with permissions");
        ops.put("roles.create", "Create role");
        ops.put("roles.update", "Update role label/enabled");
        ops.put("roles.permission.set", "Set one role permission key");
        ops.put("roles.permission.remove", "Remove one role permission key");
        ops.put("roles.permissions.replace", "Replace role permissions map");

        ops.put("users.list", "List users");
        ops.put("users.create", "Create user");
        ops.put("users.update", "Update user fields/password");
        ops.put("users.disable", "Disable user");

        ops.put("matters.list", "List matters/cases");
        ops.put("matters.get", "Get one matter/case");
        ops.put("matters.create", "Create matter/case");
        ops.put("matters.update", "Update matter/case");
        ops.put("matters.trash", "Trash matter/case");
        ops.put("matters.restore", "Restore matter/case");
        ops.put("matters.update_source_metadata", "Update source metadata (Clio, external sync)");

        ops.put("case.attributes.list", "List case attribute definitions");
        ops.put("case.attributes.save", "Save case attribute definitions");
        ops.put("case.fields.get", "Read case fields");
        ops.put("case.fields.update", "Update case fields");
        ops.put("case.list_items.get", "Read case list/grid XML datasets");
        ops.put("case.list_items.update", "Update case list/grid XML datasets");

        ops.put("document.taxonomy.get", "Read document taxonomy values");
        ops.put("document.taxonomy.add", "Add document taxonomy values");
        ops.put("document.attributes.list", "List document attribute definitions");
        ops.put("document.attributes.save", "Save document attribute definitions");
        ops.put("documents.list", "List documents in matter");
        ops.put("documents.get", "Get document");
        ops.put("documents.create", "Create document");
        ops.put("documents.update", "Update document");
        ops.put("documents.trash", "Trash document");
        ops.put("documents.restore", "Restore document");
        ops.put("document.fields.get", "Read document fields");
        ops.put("document.fields.update", "Update document fields");
        ops.put("document.parts.list", "List document parts");
        ops.put("document.parts.get", "Get document part");
        ops.put("document.parts.create", "Create document part (Lead/Attachment)");
        ops.put("document.parts.trash", "Trash document part");
        ops.put("document.parts.restore", "Restore document part");
        ops.put("document.versions.list", "List part versions");
        ops.put("document.versions.create", "Create part version metadata");
        ops.put("document.versions.render_page", "Render source PDF version page as PNG base64");
        ops.put("document.versions.redact", "Redact source PDF version and create new redacted version");

        ops.put("templates.list", "List templates");
        ops.put("templates.get", "Get template metadata");
        ops.put("templates.content.get", "Get template file content (base64)");
        ops.put("templates.create", "Create template from base64");
        ops.put("templates.update_meta", "Update template metadata");
        ops.put("templates.replace_file", "Replace template file");
        ops.put("templates.delete", "Delete template");

        ops.put("template.tools.delete_text_and_above", "Template editor: delete anchor text and above (DOCX)");
        ops.put("template.tools.delete_text_and_below", "Template editor: delete anchor text and below (DOCX)");
        ops.put("template.tools.normalize_font_family", "Template editor: normalize DOCX font family");

        ops.put("assembler.preview", "Preview assembly output");
        ops.put("assembler.assemble", "Assemble file and return bytes");
        ops.put("assembly.run", "Assemble template and record assembled form entry");
        ops.put("assembled_forms.list", "List assembled forms for matter");
        ops.put("assembled_forms.get", "Get one assembled form");
        ops.put("assembled_forms.sync_state", "Read assembled form sync state");
        ops.put("assembled_forms.retry_sync", "Retry assembled form sync now");

        ops.put("custom_objects.list", "List custom objects");
        ops.put("custom_objects.get", "Get custom object");
        ops.put("custom_objects.save", "Save custom objects list");
        ops.put("custom_objects.set_published", "Toggle custom object published flag");
        ops.put("custom_object_attributes.list", "List custom object attributes");
        ops.put("custom_object_attributes.save", "Save custom object attributes");
        ops.put("custom_object_records.list", "List custom object records");
        ops.put("custom_object_records.get", "Get custom object record");
        ops.put("custom_object_records.create", "Create custom object record");
        ops.put("custom_object_records.update", "Update custom object record");
        ops.put("custom_object_records.set_trashed", "Trash/restore custom object record");

        ops.put("omnichannel.threads.list", "List omnichannel threads");
        ops.put("omnichannel.threads.get", "Get one thread with optional timeline/details");
        ops.put("omnichannel.threads.create", "Create thread");
        ops.put("omnichannel.threads.update", "Update thread fields");
        ops.put("omnichannel.threads.set_archived", "Archive/restore thread");
        ops.put("omnichannel.threads.refresh_report", "Regenerate matter-linked thread PDF report");
        ops.put("omnichannel.messages.list", "List thread messages");
        ops.put("omnichannel.messages.add", "Add thread message");
        ops.put("omnichannel.notes.add", "Add internal note to thread");
        ops.put("omnichannel.attachments.list", "List thread attachments");
        ops.put("omnichannel.attachments.add", "Add attachment/media to thread");
        ops.put("omnichannel.attachments.get", "Get attachment metadata + base64 content");
        ops.put("omnichannel.assignments.list", "List thread assignment history");
        ops.put("omnichannel.round_robin.next_assignee", "Choose next assignee from round-robin queue");

        ops.put("bpm.processes.list", "List business process definitions");
        ops.put("bpm.actions.catalog", "List built-in BPM step actions and settings");
        ops.put("bpm.processes.get", "Get one business process definition");
        ops.put("bpm.processes.save", "Create/update business process definition");
        ops.put("bpm.processes.delete", "Delete business process definition");
        ops.put("bpm.events.trigger", "Trigger one or more business process runs");
        ops.put("bpm.runs.list", "List business process run summaries");
        ops.put("bpm.runs.get", "Get one business process run summary");
        ops.put("bpm.runs.undo", "Undo an executed business process run");
        ops.put("bpm.runs.redo", "Redo a previously undone process run");
        ops.put("bpm.reviews.list", "List human-review tasks");
        ops.put("bpm.reviews.complete", "Approve/reject and resume a human-review task");

        ops.put("texas_law.status", "Texas law sync status");
        ops.put("texas_law.sync_now", "Trigger Texas law sync now");
        ops.put("texas_law.list_dir", "Browse Texas law data directory");
        ops.put("texas_law.search", "Search Texas law corpus");
        ops.put("texas_law.render_page", "Render PDF/DOCX page as PNG base64 + text + navigation");

        return ops;
    }

    private static String helpReadme(HttpServletRequest req) {
        String base = apiBase(req);
        return """
# Controversies API (v1)

This API is tenant-scoped and designed for automation clients such as n8n and OpenClaw.

## Base URL
%s

## Authentication
- Required for all endpoints except `/help`, `/help/readme`, `/ping`, and `/capabilities`.
- Headers:
  - `X-Tenant-UUID`: tenant UUID
  - `X-API-Key`: API key
  - `X-API-Secret`: API secret
- API credentials are managed by tenant admins in Tenant Settings.

## Endpoint Patterns
- `GET /api/v1/help`
- `GET /api/v1/help/readme`
- `GET /api/v1/ping`
- `GET /api/v1/capabilities`
- `POST /api/v1/execute` with JSON `{ "operation": "...", "params": {...} }`
- `POST /api/v1/op/<operation/with/slashes>`
- `POST /api/v1/<operation/with/slashes>`

## Response Envelope
- Success:
  - `{ "ok": true, "operation": "...", "tenant_uuid": "...", "result": {...} }`
- Error:
  - `{ "ok": false, "error_code": "...", "error": "..." }`

## Minimal Example
```bash
curl -k -X POST "%s/execute" \\
  -H "Content-Type: application/json" \\
  -H "X-Tenant-UUID: <tenant_uuid>" \\
  -H "X-API-Key: <api_key>" \\
  -H "X-API-Secret: <api_secret>" \\
  -d '{"operation":"matters.list","params":{"include_trashed":false}}'
```

## OpenClaw / n8n Discovery Flow
1. Call `/help`.
2. Call `/capabilities`.
3. Use operation endpoints from capabilities metadata.

## Current Coverage
- Tenant settings/fields
- Users, roles, permissions
- Matters/cases + fields + list datasets
- Document taxonomy, attributes, documents, parts, versions
- PDF version rendering and redaction
- Templates, template tools, assembly, assembled forms
- Custom objects, attributes, records
- Omnichannel threads/messages/attachments/assignments + report refresh
- Business process manager (action catalog, definitions, runs, human review, undo/redo)
- Texas law sync/browse/search/render
- Activity logs

## Compatibility Policy
When features are added or changed in the application, matching API operations should be added/updated in the same change.
""".formatted(base, base);
    }

    private static String apiBase(HttpServletRequest req) {
        if (req == null) return "/api/v1";
        String ctx = safe(req.getContextPath());
        return ctx + "/api/v1";
    }

    private static LinkedHashMap<String, Object> roleMap(users_roles.RoleRec r) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        if (r == null) return m;
        m.put("uuid", r.uuid);
        m.put("enabled", r.enabled);
        m.put("label", r.label);
        m.put("permissions", new LinkedHashMap<String, String>(r.permissions));
        return m;
    }

    private static LinkedHashMap<String, Object> userMap(users_roles.UserRec r) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        if (r == null) return m;
        m.put("uuid", r.uuid);
        m.put("enabled", r.enabled);
        m.put("role_uuid", r.roleUuid);
        m.put("email_address", r.emailAddress);
        m.put("two_factor_enabled", r.twoFactorEnabled);
        m.put("two_factor_engine", r.twoFactorEngine);
        m.put("two_factor_phone", r.twoFactorPhone);
        return m;
    }

    private static LinkedHashMap<String, Object> matterMap(matters.MatterRec m) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (m == null) return out;
        out.put("uuid", m.uuid);
        out.put("enabled", m.enabled);
        out.put("trashed", m.trashed);
        out.put("label", m.label);
        out.put("cause_docket_number", safe(m.causeDocketNumber));
        out.put("county", safe(m.county));
        out.put("source", safe(m.source));
        out.put("source_matter_id", safe(m.sourceMatterId));
        out.put("clio_canonical_label", safe(m.clioCanonicalLabel));
        out.put("clio_updated_at", safe(m.clioUpdatedAt));
        out.put("jurisdiction_uuid", m.jurisdictionUuid);
        out.put("matter_category_uuid", m.matterCategoryUuid);
        out.put("matter_subcategory_uuid", m.matterSubcategoryUuid);
        out.put("matter_status_uuid", m.matterStatusUuid);
        out.put("matter_substatus_uuid", m.matterSubstatusUuid);
        return out;
    }

    private static LinkedHashMap<String, Object> documentMap(documents.DocumentRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("uuid", r.uuid);
        out.put("title", r.title);
        out.put("category", r.category);
        out.put("subcategory", r.subcategory);
        out.put("status", r.status);
        out.put("owner", r.owner);
        out.put("privilege_level", r.privilegeLevel);
        out.put("filed_on", r.filedOn);
        out.put("external_reference", r.externalReference);
        out.put("notes", r.notes);
        out.put("created_at", r.createdAt);
        out.put("updated_at", r.updatedAt);
        out.put("trashed", r.trashed);
        return out;
    }

    private static LinkedHashMap<String, Object> partMap(document_parts.PartRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("uuid", r.uuid);
        out.put("label", r.label);
        out.put("category", r.partType);
        out.put("sequence", r.sequence);
        out.put("confidentiality", r.confidentiality);
        out.put("author", r.author);
        out.put("notes", r.notes);
        out.put("created_at", r.createdAt);
        out.put("updated_at", r.updatedAt);
        out.put("trashed", r.trashed);
        return out;
    }

    private static LinkedHashMap<String, Object> versionMap(part_versions.VersionRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("uuid", r.uuid);
        out.put("version_label", r.versionLabel);
        out.put("source", r.source);
        out.put("mime_type", r.mimeType);
        out.put("checksum", r.checksum);
        out.put("file_size_bytes", r.fileSizeBytes);
        out.put("storage_path", r.storagePath);
        out.put("created_by", r.createdBy);
        out.put("notes", r.notes);
        out.put("created_at", r.createdAt);
        out.put("current", r.current);
        return out;
    }

    private static LinkedHashMap<String, Object> templateMap(form_templates.TemplateRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("uuid", r.uuid);
        out.put("label", r.label);
        out.put("folder_path", r.folderPath);
        out.put("enabled", r.enabled);
        out.put("updated_at", r.updatedAt);
        out.put("file_name", r.fileName);
        out.put("file_ext", r.fileExt);
        out.put("size_bytes", r.sizeBytes);
        return out;
    }

    private static LinkedHashMap<String, Object> assemblyMap(assembled_forms.AssemblyRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("uuid", r.uuid);
        out.put("matter_uuid", r.matterUuid);
        out.put("template_uuid", r.templateUuid);
        out.put("template_label", r.templateLabel);
        out.put("template_ext", r.templateExt);
        out.put("status", r.status);
        out.put("created_at", r.createdAt);
        out.put("updated_at", r.updatedAt);
        out.put("user_uuid", r.userUuid);
        out.put("user_email", r.userEmail);
        out.put("override_count", r.overrideCount);
        out.put("output_file_name", r.outputFileName);
        out.put("output_file_ext", r.outputFileExt);
        out.put("output_size_bytes", r.outputSizeBytes);
        out.put("storage_backend_type", r.storageBackendType);
        out.put("storage_object_key", r.storageObjectKey);
        out.put("storage_checksum_sha256", r.storageChecksumSha256);
        out.put("overrides", new LinkedHashMap<String, String>(r.overrides));
        return out;
    }

    private static LinkedHashMap<String, Object> customObjectMap(custom_objects.ObjectRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("uuid", r.uuid);
        out.put("key", r.key);
        out.put("label", r.label);
        out.put("plural_label", r.pluralLabel);
        out.put("enabled", r.enabled);
        out.put("published", r.published);
        out.put("sort_order", r.sortOrder);
        out.put("updated_at", r.updatedAt);
        return out;
    }

    private static LinkedHashMap<String, Object> customObjectRecordMap(custom_object_records.RecordRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("uuid", r.uuid);
        out.put("label", r.label);
        out.put("enabled", r.enabled);
        out.put("trashed", r.trashed);
        out.put("created_at", r.createdAt);
        out.put("updated_at", r.updatedAt);
        out.put("values", new LinkedHashMap<String, String>(r.values));
        return out;
    }

    private static LinkedHashMap<String, Object> bpmProcessMap(business_process_manager.ProcessDefinition p) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (p == null) return out;
        out.put("uuid", p.processUuid);
        out.put("process_uuid", p.processUuid);
        out.put("name", p.name);
        out.put("description", p.description);
        out.put("enabled", p.enabled);
        out.put("updated_at", p.updatedAt);

        ArrayList<LinkedHashMap<String, Object>> triggers = new ArrayList<LinkedHashMap<String, Object>>();
        for (business_process_manager.ProcessTrigger t : p.triggers) {
            if (t == null) continue;
            LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("type", t.type);
            row.put("key", t.key);
            row.put("op", t.op);
            row.put("value", t.value);
            row.put("enabled", t.enabled);
            triggers.add(row);
        }
        out.put("triggers", triggers);

        ArrayList<LinkedHashMap<String, Object>> steps = new ArrayList<LinkedHashMap<String, Object>>();
        for (business_process_manager.ProcessStep s : p.steps) {
            if (s == null) continue;
            LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("step_id", s.stepId);
            row.put("order", s.order);
            row.put("action", s.action);
            row.put("label", s.label);
            row.put("enabled", s.enabled);
            row.put("settings", new LinkedHashMap<String, String>(s.settings));

            ArrayList<LinkedHashMap<String, Object>> conds = new ArrayList<LinkedHashMap<String, Object>>();
            for (business_process_manager.ProcessCondition c : s.conditions) {
                if (c == null) continue;
                LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("source", c.source);
                m.put("key", c.key);
                m.put("op", c.op);
                m.put("value", c.value);
                conds.add(m);
            }
            row.put("conditions", conds);
            steps.add(row);
        }
        out.put("steps", steps);
        return out;
    }

    private static LinkedHashMap<String, Object> bpmRunMap(business_process_manager.RunResult r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("run_uuid", r.runUuid);
        out.put("process_uuid", r.processUuid);
        out.put("process_name", r.processName);
        out.put("event_type", r.eventType);
        out.put("status", r.status);
        out.put("started_at", r.startedAt);
        out.put("completed_at", r.completedAt);
        out.put("message", r.message);
        out.put("human_review_uuid", r.humanReviewUuid);
        out.put("step_count", r.stepCount);
        out.put("steps_completed", r.stepsCompleted);
        out.put("steps_skipped", r.stepsSkipped);
        out.put("errors", r.errors);
        out.put("undo_state", r.undoState);
        return out;
    }

    private static LinkedHashMap<String, Object> bpmReviewMap(business_process_manager.HumanReviewTask r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("review_uuid", r.reviewUuid);
        out.put("process_uuid", r.processUuid);
        out.put("process_name", r.processName);
        out.put("request_run_uuid", r.requestRunUuid);
        out.put("resumed_run_uuid", r.resumedRunUuid);
        out.put("event_type", r.eventType);
        out.put("status", r.status);
        out.put("title", r.title);
        out.put("instructions", r.instructions);
        out.put("comment", r.comment);
        out.put("requested_by_user_uuid", r.requestedByUserUuid);
        out.put("reviewed_by_user_uuid", r.reviewedByUserUuid);
        out.put("created_at", r.createdAt);
        out.put("resolved_at", r.resolvedAt);
        out.put("next_step_order", r.nextStepOrder);
        out.put("resume_status", r.resumeStatus);
        out.put("resume_message", r.resumeMessage);
        out.put("required_input_keys", new ArrayList<String>(r.requiredInputKeys));
        out.put("input", new LinkedHashMap<String, String>(r.input));
        out.put("context", new LinkedHashMap<String, String>(r.context));
        return out;
    }

    private static LinkedHashMap<String, Object> threadMap(omnichannel_tickets.TicketRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("thread_uuid", safe(r.uuid));
        out.put("matter_uuid", safe(r.matterUuid));
        out.put("channel", safe(r.channel));
        out.put("subject", safe(r.subject));
        out.put("status", safe(r.status));
        out.put("priority", safe(r.priority));
        out.put("assignment_mode", safe(r.assignmentMode));
        out.put("assigned_user_uuid", safe(r.assignedUserUuid));
        out.put("assigned_user_uuids", csvOrList(safe(r.assignedUserUuid)));
        out.put("reminder_at", safe(r.reminderAt));
        out.put("due_at", safe(r.dueAt));
        out.put("customer_display", safe(r.customerDisplay));
        out.put("customer_address", safe(r.customerAddress));
        out.put("mailbox_address", safe(r.mailboxAddress));
        out.put("thread_key", safe(r.threadKey));
        out.put("external_conversation_id", safe(r.externalConversationId));
        out.put("created_at", safe(r.createdAt));
        out.put("updated_at", safe(r.updatedAt));
        out.put("last_inbound_at", safe(r.lastInboundAt));
        out.put("last_outbound_at", safe(r.lastOutboundAt));
        out.put("inbound_count", r.inboundCount);
        out.put("outbound_count", r.outboundCount);
        out.put("mms_enabled", r.mmsEnabled);
        out.put("archived", r.archived);
        out.put("report_document_uuid", safe(r.reportDocumentUuid));
        out.put("report_part_uuid", safe(r.reportPartUuid));
        out.put("last_report_version_uuid", safe(r.lastReportVersionUuid));
        return out;
    }

    private static LinkedHashMap<String, Object> threadMessageMap(omnichannel_tickets.MessageRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("message_uuid", safe(r.uuid));
        out.put("thread_uuid", safe(r.ticketUuid));
        out.put("direction", safe(r.direction));
        out.put("channel", safe(r.channel));
        out.put("body", safe(r.body));
        out.put("mms", r.mms);
        out.put("from_address", safe(r.fromAddress));
        out.put("to_address", safe(r.toAddress));
        out.put("provider_message_id", safe(r.providerMessageId));
        out.put("email_message_id", safe(r.emailMessageId));
        out.put("email_in_reply_to", safe(r.emailInReplyTo));
        out.put("email_references", safe(r.emailReferences));
        out.put("created_by", safe(r.createdBy));
        out.put("created_at", safe(r.createdAt));
        return out;
    }

    private static LinkedHashMap<String, Object> threadAttachmentMap(omnichannel_tickets.AttachmentRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("attachment_uuid", safe(r.uuid));
        out.put("thread_uuid", safe(r.ticketUuid));
        out.put("message_uuid", safe(r.messageUuid));
        out.put("file_name", safe(r.fileName));
        out.put("mime_type", safe(r.mimeType));
        out.put("file_size_bytes", safe(r.fileSizeBytes));
        out.put("checksum_sha256", safe(r.checksumSha256));
        out.put("storage_file", safe(r.storageFile));
        out.put("inline_media", r.inlineMedia);
        out.put("uploaded_by", safe(r.uploadedBy));
        out.put("uploaded_at", safe(r.uploadedAt));
        return out;
    }

    private static LinkedHashMap<String, Object> threadAssignmentMap(omnichannel_tickets.AssignmentRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("assignment_uuid", safe(r.uuid));
        out.put("thread_uuid", safe(r.ticketUuid));
        out.put("mode", safe(r.mode));
        out.put("from_user_uuid", safe(r.fromUserUuid));
        out.put("from_user_uuids", csvOrList(safe(r.fromUserUuid)));
        out.put("to_user_uuid", safe(r.toUserUuid));
        out.put("to_user_uuids", csvOrList(safe(r.toUserUuid)));
        out.put("reason", safe(r.reason));
        out.put("changed_by", safe(r.changedBy));
        out.put("changed_at", safe(r.changedAt));
        return out;
    }

    private static omnichannel_tickets.TicketRec copyThread(omnichannel_tickets.TicketRec in) {
        omnichannel_tickets.TicketRec out = new omnichannel_tickets.TicketRec();
        if (in == null) return out;
        out.uuid = safe(in.uuid);
        out.matterUuid = safe(in.matterUuid);
        out.channel = safe(in.channel);
        out.subject = safe(in.subject);
        out.status = safe(in.status);
        out.priority = safe(in.priority);
        out.assignmentMode = safe(in.assignmentMode);
        out.assignedUserUuid = safe(in.assignedUserUuid);
        out.reminderAt = safe(in.reminderAt);
        out.dueAt = safe(in.dueAt);
        out.customerDisplay = safe(in.customerDisplay);
        out.customerAddress = safe(in.customerAddress);
        out.mailboxAddress = safe(in.mailboxAddress);
        out.threadKey = safe(in.threadKey);
        out.externalConversationId = safe(in.externalConversationId);
        out.createdAt = safe(in.createdAt);
        out.updatedAt = safe(in.updatedAt);
        out.lastInboundAt = safe(in.lastInboundAt);
        out.lastOutboundAt = safe(in.lastOutboundAt);
        out.inboundCount = in.inboundCount;
        out.outboundCount = in.outboundCount;
        out.mmsEnabled = in.mmsEnabled;
        out.archived = in.archived;
        out.reportDocumentUuid = safe(in.reportDocumentUuid);
        out.reportPartUuid = safe(in.reportPartUuid);
        out.lastReportVersionUuid = safe(in.lastReportVersionUuid);
        return out;
    }

    private static part_versions.VersionRec findPartVersion(List<part_versions.VersionRec> rows, String versionUuid) {
        if (rows == null || rows.isEmpty()) return null;
        String wanted = safe(versionUuid).trim();
        if (wanted.isBlank()) return null;
        for (part_versions.VersionRec r : rows) {
            if (r == null) continue;
            if (wanted.equals(safe(r.uuid).trim())) return r;
        }
        return null;
    }

    private static String normalizeLibrary(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if ("codes".equals(v)) return "codes";
        return "rules";
    }

    private static Path texasRoot(String library) throws Exception {
        Path root = "codes".equals(normalizeLibrary(library))
                ? texas_law_sync.codesDataDir()
                : texas_law_sync.rulesDataDir();
        root = root.toAbsolutePath().normalize();
        Files.createDirectories(root);
        return root;
    }

    private static String normalizeRelativePath(String raw) {
        String s = safe(raw).trim().replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        if (s.equals(".") || s.equals("./")) return "";
        if (s.contains("..")) return "";
        return s;
    }

    private static Path resolveUnderRoot(Path root, String rel) {
        Path normalizedRoot = root == null ? null : root.toAbsolutePath().normalize();
        if (normalizedRoot == null) return null;
        Path candidate = normalizedRoot;
        String r = normalizeRelativePath(rel);
        if (!r.isBlank()) candidate = normalizedRoot.resolve(r).normalize();
        if (!candidate.startsWith(normalizedRoot)) return normalizedRoot;
        return candidate;
    }

    private static String relativize(Path root, Path p) {
        if (root == null || p == null) return "";
        try {
            String out = root.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize()).toString();
            return out.replace('\\', '/');
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String fileName(Path p) {
        if (p == null || p.getFileName() == null) return "";
        return safe(p.getFileName().toString());
    }

    private static String suggestOutputFileName(String requested, String templateLabel, String ext) {
        String v = safe(requested).trim();
        if (v.isBlank()) {
            String base = safe(templateLabel).trim();
            if (base.isBlank()) base = "assembled";
            base = base.replaceAll("[^A-Za-z0-9._-]", "_");
            v = base;
        }
        if (!v.contains(".")) {
            String e = safe(ext).trim().toLowerCase(Locale.ROOT);
            if (!e.isBlank()) v = v + "." + e;
        }
        return v;
    }

    private static String normalizePath(String pathInfo) {
        String p = safe(pathInfo).trim();
        if (p.isBlank()) return "/";
        if (!p.startsWith("/")) p = "/" + p;
        while (p.contains("//")) p = p.replace("//", "/");
        return p;
    }

    private static boolean hasParam(Map<String, Object> params, String key) {
        if (params == null || key == null) return false;
        return params.containsKey(key);
    }

    private static String threadUuidParam(Map<String, Object> params) {
        String id = str(params, "thread_uuid");
        if (id.isBlank()) id = str(params, "ticket_uuid");
        return id;
    }

    private static boolean csvContains(String csv, String wanted) {
        String needle = safe(wanted).trim();
        if (needle.isBlank()) return false;
        String[] parts = safe(csv).split(",");
        for (int i = 0; i < parts.length; i++) {
            String id = safe(parts[i]).trim();
            if (id.isBlank()) continue;
            if (needle.equalsIgnoreCase(id)) return true;
        }
        return false;
    }

    private static int clampInt(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static int intVal(Map<String, Object> params, String key, int def) {
        if (params == null || key == null) return def;
        return intVal(params.get(key), def);
    }

    private static int intVal(Object raw, int def) {
        if (raw == null) return def;
        if (raw instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(safe(asString(raw)).trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private static boolean boolVal(Map<String, Object> params, String key, boolean def) {
        if (params == null || key == null) return def;
        return boolVal(params.get(key), def);
    }

    private static boolean boolVal(Object raw, boolean def) {
        if (raw == null) return def;
        if (raw instanceof Boolean b) return b;
        if (raw instanceof Number n) return n.intValue() != 0;
        String v = safe(asString(raw)).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return def;
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v);
    }

    private static String str(Map<String, Object> params, String key) {
        if (params == null || key == null) return "";
        return safe(asString(params.get(key))).trim();
    }

    private static LinkedHashMap<String, String> stringMap(Object raw) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (!(raw instanceof Map<?, ?> m)) return out;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e == null) continue;
            String k = safe(asString(e.getKey())).trim();
            if (k.isBlank()) continue;
            out.put(k, safe(asString(e.getValue())));
        }
        return out;
    }

    private static List<String> stringList(Object raw) {
        ArrayList<String> out = new ArrayList<String>();
        if (raw instanceof List<?> xs) {
            for (Object v : xs) {
                String s = safe(asString(v)).trim();
                if (!s.isBlank()) out.add(s);
            }
            return out;
        }
        String s = safe(asString(raw)).trim();
        if (s.isBlank()) return out;
        out.add(s);
        return out;
    }

    private static List<String> csvOrList(Object raw) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (raw instanceof List<?> xs) {
            for (Object v : xs) {
                String s = safe(asString(v)).trim();
                if (s.isBlank()) continue;
                for (String part : s.split(",")) {
                    String item = safe(part).trim();
                    if (!item.isBlank()) out.add(item);
                }
            }
            return new ArrayList<String>(out);
        }
        String s = safe(asString(raw)).trim();
        if (s.isBlank()) return new ArrayList<String>();
        for (String part : s.split(",")) {
            String item = safe(part).trim();
            if (!item.isBlank()) out.add(item);
        }
        return new ArrayList<String>(out);
    }

    private static List<Map<String, Object>> objectList(Object raw) {
        ArrayList<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (!(raw instanceof List<?> xs)) return out;
        for (Object v : xs) {
            if (!(v instanceof Map<?, ?> m)) continue;
            LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e == null) continue;
                String k = safe(asString(e.getKey())).trim();
                if (k.isBlank()) continue;
                row.put(k, e.getValue());
            }
            out.add(row);
        }
        return out;
    }

    private static byte[] decodeBase64(String raw) {
        String s = safe(raw).trim();
        if (s.isBlank()) return new byte[0];
        int comma = s.indexOf(',');
        if (comma > 0 && s.substring(0, comma).toLowerCase(Locale.ROOT).contains("base64")) {
            s = s.substring(comma + 1).trim();
        }
        try {
            return Base64.getDecoder().decode(s);
        } catch (Exception ignored) {
            return new byte[0];
        }
    }

    private static String encodeBase64(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String asString(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void auditApiInvocation(String tenantUuid,
                                           String operation,
                                           String credentialId,
                                           String clientIp,
                                           String method,
                                           boolean ok,
                                           int status,
                                           String errorCode,
                                           String errorMessage) {
        try {
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("operation", clampText(safe(operation), 180));
            details.put("credential_id", clampText(safe(credentialId), 120));
            details.put("client_ip", clampText(safe(clientIp), 120));
            details.put("method", clampText(safe(method), 20));
            details.put("http_status", String.valueOf(status));
            details.put("ok", ok ? "true" : "false");
            if (!safe(errorCode).isBlank()) details.put("error_code", clampText(errorCode, 80));
            if (!safe(errorMessage).isBlank()) details.put("error", clampText(errorMessage, 220));

            String actor = safe(credentialId).trim();
            if (actor.isBlank()) actor = "api";
            else actor = "api:" + actor;

            activity_log logs = activity_log.defaultStore();
            if (ok) {
                logs.logVerbose("api.operation.success", safe(tenantUuid), actor, "", "", details);
            } else if (status >= 500) {
                logs.logError("api.operation.failure", safe(tenantUuid), actor, "", "", details);
            } else {
                logs.logWarning("api.operation.failure", safe(tenantUuid), actor, "", "", details);
            }
        } catch (Exception ex) {
            LOG.log(Level.FINE, "API audit log failure: " + safe(ex.getMessage()), ex);
        }
    }

    private static String clampText(String raw, int max) {
        String s = safe(raw);
        int lim = Math.max(1, max);
        if (s.length() <= lim) return s;
        return s.substring(0, lim);
    }

    private static void writeJson(HttpServletResponse resp, int status, Object payload) throws IOException {
        resp.setStatus(status);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("application/json; charset=UTF-8");
        JSON.writerWithDefaultPrettyPrinter().writeValue(resp.getOutputStream(), payload);
    }

    private static void writeText(HttpServletResponse resp, int status, String text) throws IOException {
        resp.setStatus(status);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("text/plain; charset=UTF-8");
        resp.getWriter().write(safe(text));
    }

    private static void writeError(HttpServletResponse resp, int status, String code, String message) throws IOException {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", false);
        out.put("error_code", safe(code));
        out.put("error", safe(message));
        writeJson(resp, status, out);
    }

    private static final class AssemblerInputs {
        final byte[] templateBytes;
        final String templateExtOrName;
        final String templateLabel;
        final LinkedHashMap<String, String> values;
        final LinkedHashMap<String, String> overrides;

        AssemblerInputs(byte[] templateBytes,
                        String templateExtOrName,
                        String templateLabel,
                        LinkedHashMap<String, String> values,
                        LinkedHashMap<String, String> overrides) {
            this.templateBytes = templateBytes == null ? new byte[0] : templateBytes;
            this.templateExtOrName = safe(templateExtOrName);
            this.templateLabel = safe(templateLabel);
            this.values = values == null ? new LinkedHashMap<String, String>() : values;
            this.overrides = overrides == null ? new LinkedHashMap<String, String>() : overrides;
        }
    }

    private static void putToken(Map<String, String> values, String key, String value) {
        if (values == null) return;
        String k = safe(key).trim();
        if (k.isBlank()) return;
        values.put(k, safe(value));
    }

    private static void applyLiteralOverride(Map<String, String> mergeValues, String tokenLiteral, String value) {
        if (mergeValues == null) return;
        String key = safe(tokenLiteral).trim();
        if (key.isBlank()) return;

        String v = safe(value);
        mergeValues.put(key, v);

        int dot = key.indexOf('.');
        if (dot <= 0 || dot + 1 >= key.length()) return;

        String prefix = safe(key.substring(0, dot)).toLowerCase(Locale.ROOT);
        String tail = safe(key.substring(dot + 1)).trim();
        if (tail.isBlank()) return;

        if ("case".equals(prefix) || "kv".equals(prefix)) {
            mergeValues.put("case." + tail, v);
            mergeValues.put("kv." + tail, v);
            mergeValues.put(tail, v);
        } else if ("tenant".equals(prefix)) {
            mergeValues.put("tenant." + tail, v);
            mergeValues.put("kv." + tail, v);
            mergeValues.put(tail, v);
        }
    }
}
