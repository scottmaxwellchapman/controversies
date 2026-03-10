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
        String credentialScope = safe((String) req.getAttribute(REQ_CREDENTIAL_SCOPE)).trim();
        try {
            LinkedHashMap<String, Object> result = executeOperation(
                    operation,
                    params,
                    tenantUuid,
                    safe((String) req.getAttribute(REQ_CREDENTIAL_ID)),
                    safe((String) req.getAttribute(REQ_CREDENTIAL_LABEL)),
                    credentialScope,
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
        } catch (IllegalStateException ex) {
            auditApiInvocation(
                    tenantUuid,
                    operation,
                    safe((String) req.getAttribute(REQ_CREDENTIAL_ID)),
                    safe(req.getRemoteAddr()),
                    method,
                    false,
                    409,
                    "conflict",
                    safe(ex.getMessage())
            );
            writeError(resp, 409, "conflict", safe(ex.getMessage()));
        } catch (SecurityException ex) {
            auditApiInvocation(
                    tenantUuid,
                    operation,
                    safe((String) req.getAttribute(REQ_CREDENTIAL_ID)),
                    safe(req.getRemoteAddr()),
                    method,
                    false,
                    403,
                    "forbidden",
                    safe(ex.getMessage())
            );
            writeError(resp, 403, "forbidden", safe(ex.getMessage()));
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
        return executeOperation(op, params, tenantUuid, credentialId, credentialLabel, "full_access", clientIp, method);
    }

    private static LinkedHashMap<String, Object> executeOperation(String op,
                                                                   Map<String, Object> params,
                                                                   String tenantUuid,
                                                                   String credentialId,
                                                                   String credentialLabel,
                                                                   String credentialScope,
                                                                   String clientIp,
                                                                   String method) throws Exception {

        String operation = safe(op).trim().toLowerCase(Locale.ROOT);
        requireOperationPermission(operation, credentialScope);

        LinkedHashMap<String, Object> omnichannel = executeOmnichannelOperation(operation, params, tenantUuid);
        if (omnichannel != null) return omnichannel;

        LinkedHashMap<String, Object> facts = executeFactsOperation(operation, params, tenantUuid);
        if (facts != null) return facts;

        LinkedHashMap<String, Object> tasks = executeTasksOperation(operation, params, tenantUuid);
        if (tasks != null) return tasks;

        LinkedHashMap<String, Object> mail = executeMailOperation(operation, params, tenantUuid);
        if (mail != null) return mail;

        LinkedHashMap<String, Object> leads = executeLeadsOperation(operation, params, tenantUuid);
        if (leads != null) return leads;

        LinkedHashMap<String, Object> billing = executeBillingOperation(operation, params, tenantUuid);
        if (billing != null) return billing;

        LinkedHashMap<String, Object> esign = executeEsignOperation(operation, params, tenantUuid);
        if (esign != null) return esign;

        LinkedHashMap<String, Object> integrations = executeIntegrationsOperation(operation, params, tenantUuid);
        if (integrations != null) return integrations;

        LinkedHashMap<String, Object> analytics = executeAnalyticsOperation(operation, params, tenantUuid);
        if (analytics != null) return analytics;

        switch (operation) {
            case "auth.whoami": {
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("tenant_uuid", tenantUuid);
                out.put("credential_id", credentialId);
                out.put("credential_label", credentialLabel);
                out.put("scope", normalizeCredentialScope(credentialScope));
                out.put("required_permissions", requiredPermissionKeys(operation));
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
                    m.put("detail_map", new LinkedHashMap<String, String>(r.detailMap));
                    items.add(m);
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "permissions.catalog": {
                permission_layers store = permission_layers.defaultStore();
                ArrayList<LinkedHashMap<String, Object>> defs = new ArrayList<LinkedHashMap<String, Object>>();
                for (permission_layers.PermissionDef def : store.permissionCatalog()) {
                    if (def == null) continue;
                    LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("key", def.key);
                    m.put("label", def.label);
                    m.put("description", def.description);
                    m.put("category", def.category);
                    m.put("admin_only", def.adminOnly);
                    defs.add(m);
                }

                ArrayList<LinkedHashMap<String, Object>> profiles = new ArrayList<LinkedHashMap<String, Object>>();
                for (permission_layers.PermissionProfile profile : store.permissionProfiles()) {
                    if (profile == null) continue;
                    LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("key", profile.key);
                    m.put("label", profile.label);
                    m.put("description", profile.description);
                    m.put("permissions", new LinkedHashMap<String, String>(profile.permissions));
                    profiles.add(m);
                }

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("permissions", defs);
                out.put("profiles", profiles);
                out.put("permission_count", defs.size());
                out.put("profile_count", profiles.size());
                return out;
            }

            case "api.credentials.list": {
                List<api_credentials.CredentialRec> rows = api_credentials.defaultStore().list(tenantUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (api_credentials.CredentialRec r : rows) {
                    if (r == null) continue;
                    items.add(credentialMap(r));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "api.credentials.get": {
                String targetCredentialId = str(params, "credential_id");
                api_credentials.CredentialRec rec = api_credentials.defaultStore().get(tenantUuid, targetCredentialId);
                if (rec == null) throw new IllegalArgumentException("Credential not found.");
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("credential", credentialMap(rec));
                return out;
            }

            case "api.credentials.create": {
                String label = str(params, "label");
                String userUuid = str(params, "created_by_user_uuid");
                String scope = hasParam(params, "scope") ? str(params, "scope") : "full_access";
                api_credentials.GeneratedCredential created = api_credentials.defaultStore().create(tenantUuid, label, userUuid, scope);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("credential", credentialMap(created.credential));
                out.put("api_key", created.apiKey);
                out.put("api_secret", created.apiSecret);
                out.put("note", "Store the API secret now. It is not retrievable later.");
                return out;
            }

            case "api.credentials.update": {
                String targetCredentialId = str(params, "credential_id");
                boolean changed = api_credentials.defaultStore().update(
                        tenantUuid,
                        targetCredentialId,
                        hasParam(params, "label") ? str(params, "label") : null,
                        hasParam(params, "scope") ? str(params, "scope") : null
                );
                api_credentials.CredentialRec rec = api_credentials.defaultStore().get(tenantUuid, targetCredentialId);
                if (rec == null) throw new IllegalArgumentException("Credential not found.");
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("credential", credentialMap(rec));
                return out;
            }

            case "api.credentials.rotate_secret": {
                String targetCredentialId = str(params, "credential_id");
                api_credentials.GeneratedCredential rotated = api_credentials.defaultStore().rotateSecret(tenantUuid, targetCredentialId);
                if (rotated == null) throw new IllegalArgumentException("Credential not found or revoked.");
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("credential", credentialMap(rotated.credential));
                out.put("api_key", rotated.apiKey);
                out.put("api_secret", rotated.apiSecret);
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

            case "contacts.list": {
                boolean includeTrashed = boolVal(params, "include_trashed", false);
                String sourceFilter = str(params, "source").trim().toLowerCase(Locale.ROOT);
                if (!"clio".equals(sourceFilter) && !"native".equals(sourceFilter)
                        && !"office365".equals(sourceFilter) && !"external".equals(sourceFilter)) sourceFilter = "";

                contacts contactStore = contacts.defaultStore();
                matter_contacts linkStore = matter_contacts.defaultStore();
                List<contacts.ContactRec> rows = contactStore.listAll(tenantUuid);
                List<matter_contacts.LinkRec> links = linkStore.listAll(tenantUuid);
                LinkedHashMap<String, List<matter_contacts.LinkRec>> linksByContact = linksByContactUuid(links);

                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (contacts.ContactRec r : rows) {
                    if (r == null) continue;
                    if (!includeTrashed && r.trashed) continue;
                    boolean external = contacts.isExternalReadOnly(r);
                    String sourceType = contacts.sourceType(r);
                    if ("clio".equals(sourceFilter) && !"clio".equals(sourceType)) continue;
                    if ("native".equals(sourceFilter) && !"native".equals(sourceType)) continue;
                    if ("office365".equals(sourceFilter) && !sourceType.startsWith("office365")) continue;
                    if ("external".equals(sourceFilter) && !external) continue;
                    items.add(contactMap(r, linksByContact.getOrDefault(safe(r.uuid), List.of())));
                }

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "contacts.get": {
                String contactUuid = str(params, "contact_uuid");
                contacts.ContactRec rec = contacts.defaultStore().getByUuid(tenantUuid, contactUuid);
                List<matter_contacts.LinkRec> links = matter_contacts.defaultStore().listByContact(tenantUuid, contactUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("contact", contactMap(rec, links));
                return out;
            }

            case "contacts.create": {
                contacts.ContactRec rec = contacts.defaultStore().createNative(tenantUuid, contactInputFromParams(params));
                String matterUuid = str(params, "matter_uuid");
                if (!matterUuid.isBlank()) {
                    matter_contacts.defaultStore().replaceNativeLinksForContact(tenantUuid, rec.uuid, List.of(matterUuid));
                }
                List<matter_contacts.LinkRec> links = matter_contacts.defaultStore().listByContact(tenantUuid, rec.uuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("contact", contactMap(rec, links));
                return out;
            }

            case "contacts.update": {
                String contactUuid = str(params, "contact_uuid");
                boolean changed = contacts.defaultStore().updateNative(tenantUuid, contactUuid, contactInputFromParams(params));
                if (hasParam(params, "matter_uuid")) {
                    String matterUuid = str(params, "matter_uuid");
                    if (matterUuid.isBlank()) matter_contacts.defaultStore().replaceNativeLinksForContact(tenantUuid, contactUuid, List.of());
                    else matter_contacts.defaultStore().replaceNativeLinksForContact(tenantUuid, contactUuid, List.of(matterUuid));
                }
                contacts.ContactRec rec = contacts.defaultStore().getByUuid(tenantUuid, contactUuid);
                List<matter_contacts.LinkRec> links = matter_contacts.defaultStore().listByContact(tenantUuid, contactUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("contact", contactMap(rec, links));
                return out;
            }

            case "contacts.trash": {
                boolean changed = contacts.defaultStore().trash(tenantUuid, str(params, "contact_uuid"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                return out;
            }

            case "contacts.restore": {
                boolean changed = contacts.defaultStore().restore(tenantUuid, str(params, "contact_uuid"));
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
                if (matters.defaultStore().isClioManaged(tenantUuid, matterUuid)) {
                    throw new IllegalArgumentException(matters.clioReadOnlyMessage());
                }
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
                if (matters.defaultStore().isClioManaged(tenantUuid, matterUuid)) {
                    throw new IllegalArgumentException(matters.clioReadOnlyMessage());
                }
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

            case "conflicts.list": {
                String matterUuid = str(params, "matter_uuid");
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                requireMatterExists(tenantUuid, matterUuid);
                boolean refresh = boolVal(params, "refresh", false);
                if (refresh) {
                    requireCredentialPermission(credentialScope, "conflicts.manage", operation);
                    conflicts_scan_service.defaultService().scanMatter(tenantUuid, matterUuid, true);
                } else {
                    matter_conflicts.defaultStore().ensure(tenantUuid, matterUuid);
                }

                matter_conflicts.FileRec file = matter_conflicts.defaultStore().read(tenantUuid, matterUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (matter_conflicts.ConflictEntry row : file.entries) {
                    if (row == null) continue;
                    items.add(conflictEntryMap(row));
                }

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("matter_uuid", matterUuid);
                out.put("items", items);
                out.put("count", items.size());
                out.put("updated_at", safe(file.updatedAt));
                out.put("last_scanned_at", safe(file.lastScannedAt));
                out.put("scan_state_count", file.versionScanState == null ? 0 : file.versionScanState.size());
                return out;
            }

            case "conflicts.get": {
                String matterUuid = str(params, "matter_uuid");
                String entryUuid = str(params, "entry_uuid");
                if (matterUuid.isBlank() || entryUuid.isBlank()) {
                    throw new IllegalArgumentException("matter_uuid and entry_uuid are required.");
                }
                requireMatterExists(tenantUuid, matterUuid);
                matter_conflicts.FileRec file = matter_conflicts.defaultStore().read(tenantUuid, matterUuid);
                matter_conflicts.ConflictEntry found = null;
                for (matter_conflicts.ConflictEntry row : file.entries) {
                    if (row == null) continue;
                    if (entryUuid.equals(safe(row.uuid).trim())) {
                        found = row;
                        break;
                    }
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("entry", conflictEntryMap(found));
                return out;
            }

            case "conflicts.upsert": {
                String matterUuid = str(params, "matter_uuid");
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                requireMatterExists(tenantUuid, matterUuid);
                matter_conflicts.ConflictEntry entry = conflictEntryFromParams(params);
                entry = matter_conflicts.defaultStore().upsertEntry(tenantUuid, matterUuid, entry);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("matter_uuid", matterUuid);
                out.put("entry", conflictEntryMap(entry));
                return out;
            }

            case "conflicts.delete": {
                String matterUuid = str(params, "matter_uuid");
                String entryUuid = str(params, "entry_uuid");
                if (matterUuid.isBlank() || entryUuid.isBlank()) {
                    throw new IllegalArgumentException("matter_uuid and entry_uuid are required.");
                }
                requireMatterExists(tenantUuid, matterUuid);
                boolean changed = matter_conflicts.defaultStore().deleteEntry(tenantUuid, matterUuid, entryUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("matter_uuid", matterUuid);
                out.put("deleted", changed);
                return out;
            }

            case "conflicts.scan": {
                String matterUuid = str(params, "matter_uuid");
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                if (!matterUuid.isBlank()) {
                    requireMatterExists(tenantUuid, matterUuid);
                    conflicts_scan_service.ScanSummary row = conflicts_scan_service.defaultService()
                            .scanMatter(tenantUuid, matterUuid, true);
                    out.put("scan", conflictScanSummaryMap(row));
                    return out;
                }

                ArrayList<conflicts_scan_service.ScanSummary> rows = conflicts_scan_service.defaultService()
                        .scanAllMatters(tenantUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (conflicts_scan_service.ScanSummary row : rows) {
                    if (row == null) continue;
                    items.add(conflictScanSummaryMap(row));
                }
                out.put("items", items);
                out.put("count", items.size());
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
                store.requireEditable(tenantUuid, str(params, "matter_uuid"), str(params, "doc_uuid"));
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
                documents.defaultStore().requireEditable(tenantUuid, str(params, "matter_uuid"), str(params, "doc_uuid"));
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
                documents.defaultStore().requireEditable(tenantUuid, str(params, "matter_uuid"), str(params, "doc_uuid"));
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
                documents.defaultStore().requireEditable(tenantUuid, matterUuid, docUuid);
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
                documents.defaultStore().requireEditable(tenantUuid, str(params, "matter_uuid"), str(params, "doc_uuid"));
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
                documents.defaultStore().requireEditable(tenantUuid, str(params, "matter_uuid"), str(params, "doc_uuid"));
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
                documents.defaultStore().requireEditable(tenantUuid, str(params, "matter_uuid"), str(params, "doc_uuid"));
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
                documents.defaultStore().requireEditable(tenantUuid, str(params, "matter_uuid"), str(params, "doc_uuid"));
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

                Path sourcePath = pdf_redaction_service.resolveStoragePath(source.storagePath);
                pdf_redaction_service.requirePathWithinTenant(sourcePath, tenantUuid, "Source version path");
                if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
                    throw new IllegalArgumentException("Source version file not found.");
                }
                if (!document_page_preview.isRenderable(sourcePath)) {
                    throw new IllegalArgumentException("Preview supports PDF, DOCX, DOC, RTF, TXT, and ODT source versions.");
                }

                document_page_preview.RenderedPage rendered = document_page_preview.renderPage(sourcePath, page);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("source_version", versionMap(source));
                out.put("source_extension", document_page_preview.extension(sourcePath));
                out.put("page_index", rendered.pageIndex);
                out.put("page_number", rendered.pageIndex + 1);
                out.put("total_pages", rendered.totalPages);
                out.put("total_known", rendered.totalKnown);
                out.put("has_prev", rendered.hasPrev);
                out.put("has_next", rendered.hasNext);
                out.put("image_width_px", rendered.imageWidthPx);
                out.put("image_height_px", rendered.imageHeightPx);
                out.put("image_png_base64", rendered.base64Png);
                try {
                    image_hash_tools.HashRec hash = image_hash_tools.compute(image_hash_tools.decodePngBase64(rendered.base64Png));
                    out.put("image_hash_sha256_rgb", safe(hash == null ? "" : hash.sha256Rgb));
                    out.put("image_hash_ahash64", safe(hash == null ? "" : hash.averageHash64));
                    out.put("image_hash_dhash64", safe(hash == null ? "" : hash.differenceHash64));
                } catch (Exception ignored) {
                    out.put("image_hash_sha256_rgb", "");
                    out.put("image_hash_ahash64", "");
                    out.put("image_hash_dhash64", "");
                }
                out.put("warning", rendered.warning);
                out.put("engine", rendered.engine);
                out.put("page_text", rendered.pageText);

                ArrayList<LinkedHashMap<String, Object>> nav = new ArrayList<LinkedHashMap<String, Object>>();
                for (document_page_preview.NavigationEntry n : rendered.navigation) {
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

            case "document.versions.find_similar": {
                String matterUuid = str(params, "matter_uuid");
                String docUuid = str(params, "doc_uuid");
                String partUuid = str(params, "part_uuid");
                String sourceVersionUuid = str(params, "source_version_uuid");
                String scope = str(params, "scope");
                if (!"tenant".equalsIgnoreCase(scope)) scope = "matter";
                int maxResults = clampInt(intVal(params, "max_results", 20), 1, 200);
                int maxHammingDistance = clampInt(intVal(params, "max_hamming_distance", 8), 0, 64);
                boolean duplicatesOnly = boolVal(params, "duplicates_only", false);

                List<part_versions.VersionRec> rows = part_versions.defaultStore().listAll(
                        tenantUuid,
                        matterUuid,
                        docUuid,
                        partUuid
                );
                part_versions.VersionRec source = findPartVersion(rows, sourceVersionUuid);
                if (source == null) throw new IllegalArgumentException("Source version not found.");

                ArrayList<version_image_similarity_service.SimilarityRec> similar = version_image_similarity_service.defaultService().findSimilarVersions(
                        tenantUuid,
                        matterUuid,
                        docUuid,
                        partUuid,
                        source,
                        scope,
                        maxResults,
                        maxHammingDistance
                );

                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (version_image_similarity_service.SimilarityRec row : similar) {
                    if (row == null) continue;
                    if (duplicatesOnly && !row.duplicateCandidate) continue;
                    LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("matter_uuid", row.matterUuid);
                    m.put("matter_label", row.matterLabel);
                    m.put("doc_uuid", row.documentUuid);
                    m.put("document_title", row.documentTitle);
                    m.put("part_uuid", row.partUuid);
                    m.put("part_label", row.partLabel);
                    m.put("version_uuid", row.versionUuid);
                    m.put("version_label", row.versionLabel);
                    m.put("source", row.source);
                    m.put("mime_type", row.mimeType);
                    m.put("created_at", row.createdAt);
                    m.put("created_by", row.createdBy);
                    m.put("source_pages", row.sourcePages);
                    m.put("candidate_pages", row.candidatePages);
                    m.put("exact_page_matches", row.exactPageMatches);
                    m.put("near_page_matches", row.nearPageMatches);
                    m.put("best_hamming_distance", row.bestHammingDistance);
                    m.put("average_hamming_distance", row.averageHammingDistance);
                    m.put("similarity_percent", row.similarityPercent);
                    m.put("duplicate_candidate", row.duplicateCandidate);
                    items.add(m);
                }

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("source_version", versionMap(source));
                out.put("scope", scope);
                out.put("max_hamming_distance", maxHammingDistance);
                out.put("duplicates_only", duplicatesOnly);
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "document.versions.redact": {
                String matterUuid = str(params, "matter_uuid");
                String docUuid = str(params, "doc_uuid");
                String partUuid = str(params, "part_uuid");
                String sourceVersionUuid = str(params, "source_version_uuid");
                documents.defaultStore().requireEditable(tenantUuid, matterUuid, docUuid);

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
                Path outputPath = outputDir.resolve(outputVersionUuid.replace("-", "_") + "__" + outputName);

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

            case "custom_objects.create": {
                custom_objects store = custom_objects.defaultStore();
                String key = str(params, "key");
                if (key.isBlank()) throw new IllegalArgumentException("key is required.");
                if (store.getByKey(tenantUuid, key) != null) {
                    throw new IllegalArgumentException("Custom object key already exists.");
                }

                List<custom_objects.ObjectRec> all = store.listAll(tenantUuid);
                int defaultSort = (all.size() + 1) * 10;
                custom_objects.ObjectRec in = new custom_objects.ObjectRec(
                        "",
                        key,
                        str(params, "label"),
                        str(params, "plural_label"),
                        hasParam(params, "enabled") ? boolVal(params, "enabled", true) : true,
                        hasParam(params, "published") ? boolVal(params, "published", false) : false,
                        intVal(params, "sort_order", defaultSort),
                        ""
                );

                ArrayList<custom_objects.ObjectRec> next = new ArrayList<custom_objects.ObjectRec>(all);
                next.add(in);
                store.saveAll(tenantUuid, next);

                custom_objects.ObjectRec created = store.getByKey(tenantUuid, key);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("object", customObjectMap(created));
                return out;
            }

            case "custom_objects.update": {
                custom_objects store = custom_objects.defaultStore();
                String objectUuid = str(params, "object_uuid");
                if (objectUuid.isBlank()) throw new IllegalArgumentException("object_uuid is required.");

                List<custom_objects.ObjectRec> all = store.listAll(tenantUuid);
                custom_objects.ObjectRec current = null;
                for (custom_objects.ObjectRec r : all) {
                    if (r == null) continue;
                    if (objectUuid.equals(safe(r.uuid).trim())) {
                        current = r;
                        break;
                    }
                }

                if (current == null) {
                    LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                    out.put("updated", false);
                    out.put("object", customObjectMap(null));
                    return out;
                }

                String nextKey = hasParam(params, "key") ? str(params, "key") : current.key;
                String normalizedNextKey = store.normalizeKey(nextKey);
                if (normalizedNextKey.isBlank()) throw new IllegalArgumentException("key is required.");

                for (custom_objects.ObjectRec r : all) {
                    if (r == null) continue;
                    if (objectUuid.equals(safe(r.uuid).trim())) continue;
                    if (normalizedNextKey.equals(store.normalizeKey(r.key))) {
                        throw new IllegalArgumentException("Custom object key already exists.");
                    }
                }

                ArrayList<custom_objects.ObjectRec> next = new ArrayList<custom_objects.ObjectRec>(all.size());
                boolean changed = false;
                String now = Instant.now().toString();

                for (custom_objects.ObjectRec r : all) {
                    if (r == null) continue;
                    if (objectUuid.equals(safe(r.uuid).trim())) {
                        custom_objects.ObjectRec updated = new custom_objects.ObjectRec(
                                r.uuid,
                                nextKey,
                                hasParam(params, "label") ? str(params, "label") : r.label,
                                hasParam(params, "plural_label") ? str(params, "plural_label") : r.pluralLabel,
                                hasParam(params, "enabled") ? boolVal(params, "enabled", r.enabled) : r.enabled,
                                hasParam(params, "published") ? boolVal(params, "published", r.published) : r.published,
                                hasParam(params, "sort_order") ? intVal(params, "sort_order", r.sortOrder) : r.sortOrder,
                                now
                        );
                        next.add(updated);
                        changed = true;
                    } else {
                        next.add(r);
                    }
                }

                if (changed) store.saveAll(tenantUuid, next);
                custom_objects.ObjectRec refreshed = store.getByUuid(tenantUuid, objectUuid);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("object", customObjectMap(refreshed));
                return out;
            }

            case "custom_objects.delete": {
                custom_objects store = custom_objects.defaultStore();
                String objectUuid = str(params, "object_uuid");
                if (objectUuid.isBlank()) throw new IllegalArgumentException("object_uuid is required.");

                List<custom_objects.ObjectRec> all = store.listAll(tenantUuid);
                ArrayList<custom_objects.ObjectRec> next = new ArrayList<custom_objects.ObjectRec>(all.size());
                boolean changed = false;
                for (custom_objects.ObjectRec r : all) {
                    if (r == null) continue;
                    if (objectUuid.equals(safe(r.uuid).trim())) {
                        changed = true;
                        continue;
                    }
                    next.add(r);
                }

                if (changed) store.saveAll(tenantUuid, next);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("deleted", changed);
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

            case "bpm.webhooks.receive": {
                business_process_manager bpm = business_process_manager.defaultService();
                String processUuid = str(params, "process_uuid");
                String webhookKey = str(params, "webhook_key");
                if (webhookKey.isBlank()) webhookKey = str(params, "key");

                String normalizedKey = safe(webhookKey).trim().toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9._-]", "_")
                        .replaceAll("_+", "_");
                while (normalizedKey.startsWith("_")) normalizedKey = normalizedKey.substring(1);
                while (normalizedKey.endsWith("_")) normalizedKey = normalizedKey.substring(0, normalizedKey.length() - 1);

                String eventType = str(params, "event_type");
                if (eventType.isBlank()) {
                    eventType = normalizedKey.isBlank() ? "webhook.received" : ("webhook." + normalizedKey);
                }

                LinkedHashMap<String, String> payload = stringMap(params.get("payload"));
                payload.put("webhook_key", webhookKey);
                payload.put("webhook_key_normalized", normalizedKey);
                payload.put("webhook_event_type", eventType);

                String actorUserUuid = str(params, "actor_user_uuid");
                String source = str(params, "source");
                if (source.isBlank()) source = "api.bpm.webhooks.receive";

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
                out.put("event_type", eventType);
                out.put("webhook_key", webhookKey);
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
                business_process_manager bpm = business_process_manager.defaultService();
                String viewerUserUuid = str(params, "viewer_user_uuid");
                if (viewerUserUuid.isBlank()) viewerUserUuid = str(params, "user_uuid");
                List<business_process_manager.HumanReviewTask> rows = viewerUserUuid.isBlank()
                        ? bpm.listReviews(
                                tenantUuid,
                                boolVal(params, "pending_only", true),
                                clampInt(intVal(params, "limit", 100), 1, 500)
                        )
                        : bpm.listReviewsForUser(
                                tenantUuid,
                                viewerUserUuid,
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
                String reviewer = str(params, "reviewed_by_user_uuid");
                if (reviewer.isBlank()) reviewer = str(params, "actor_user_uuid");
                business_process_manager.HumanReviewTask row = business_process_manager.defaultService().completeReview(
                        tenantUuid,
                        str(params, "review_uuid"),
                        boolVal(params, "approved", true),
                        reviewer,
                        stringMap(params.get("input")),
                        str(params, "comment")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("review", bpmReviewMap(row));
                return out;
            }

            case "search.types": {
                ArrayList<search_jobs_service.SearchTypeInfo> rows = search_jobs_service.defaultService().listSearchTypes();
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (search_jobs_service.SearchTypeInfo row : rows) {
                    if (row == null) continue;
                    if (!credentialHasPermission(credentialScope, safe(row.permissionKey))) continue;
                    items.add(searchTypeMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                out.put("tesseract_available", search_jobs_service.defaultService().isTesseractAvailable());
                return out;
            }

            case "search.jobs.enqueue": {
                String requestedBy = resolveSearchRequestedBy(
                        params,
                        credentialId,
                        credentialLabel,
                        credentialScope,
                        operation
                );

                search_jobs_service.SearchJobRequest in = new search_jobs_service.SearchJobRequest();
                in.tenantUuid = tenantUuid;
                in.requestedBy = requestedBy;
                in.searchType = str(params, "search_type");
                in.logic = str(params, "logic");
                in.query = str(params, "query");
                in.operator = str(params, "operator");
                in.caseSensitive = boolVal(params, "case_sensitive", false);
                in.includeMetadata = boolVal(params, "include_metadata", true);
                in.includeOcr = boolVal(params, "include_ocr", true);
                in.maxResults = clampInt(intVal(params, "max_results", 200), 1, 500);
                in.criteria = searchCriteriaList(params);

                search_jobs_service.SearchTypeInfo type = search_jobs_service.defaultService().getSearchType(in.searchType);
                if (type == null) throw new IllegalArgumentException("Unknown search type.");
                requireCredentialPermission(credentialScope, safe(type.permissionKey), operation);

                String jobId = search_jobs_service.defaultService().enqueue(in);
                search_jobs_service.SearchJobSnapshot snapshot = search_jobs_service.defaultService().getJob(
                        tenantUuid,
                        requestedBy,
                        jobId,
                        false
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("job_id", jobId);
                out.put("job", searchJobMap(snapshot, false));
                return out;
            }

            case "search.jobs.list": {
                String requestedBy = resolveSearchRequestedBy(
                        params,
                        credentialId,
                        credentialLabel,
                        credentialScope,
                        operation
                );

                int limit = clampInt(intVal(params, "limit", 20), 1, 100);
                boolean includeResults = boolVal(params, "include_results", false);
                ArrayList<search_jobs_service.SearchJobSnapshot> rows = search_jobs_service.defaultService().listJobs(
                        tenantUuid,
                        requestedBy,
                        limit,
                        includeResults
                );
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (search_jobs_service.SearchJobSnapshot row : rows) {
                    if (row == null) continue;
                    if (!searchJobVisibleToCredential(row, credentialScope)) continue;
                    items.add(searchJobMap(row, includeResults));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "search.jobs.get":
            case "search.jobs.status": {
                String requestedBy = resolveSearchRequestedBy(
                        params,
                        credentialId,
                        credentialLabel,
                        credentialScope,
                        operation
                );

                String jobId = str(params, "job_id");
                boolean includeResults = boolVal(params, "include_results", true);
                search_jobs_service.SearchJobSnapshot row = search_jobs_service.defaultService().getJob(
                        tenantUuid,
                        requestedBy,
                        jobId,
                        includeResults
                );
                if (row == null) throw new IllegalArgumentException("Search job not found.");
                requireSearchJobVisibleToCredential(row, credentialScope, operation);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("job", searchJobMap(row, includeResults));
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
                if (!texas_law_library.isRenderable(target)) {
                    throw new IllegalArgumentException("Viewer supports PDF, DOCX, DOC, RTF, TXT, and ODT.");
                }

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

    private static LinkedHashMap<String, Object> executeLeadsOperation(String operation,
                                                                        Map<String, Object> params,
                                                                        String tenantUuid) throws Exception {
        String op = safe(operation).trim().toLowerCase(Locale.ROOT);
        if (!op.startsWith("leads.")) return null;

        leads_crm store = leads_crm.defaultStore();

        switch (op) {
            case "leads.list": {
                boolean includeArchived = boolVal(params, "include_archived", false);
                String status = str(params, "status").toLowerCase(Locale.ROOT).trim();
                String assignedUser = str(params, "assigned_user_uuid");
                String source = str(params, "source").toLowerCase(Locale.ROOT).trim();
                String q = str(params, "q").toLowerCase(Locale.ROOT).trim();

                List<leads_crm.LeadRec> rows = store.listLeads(tenantUuid, includeArchived);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (leads_crm.LeadRec row : rows) {
                    if (row == null) continue;
                    if (!status.isBlank() && !status.equalsIgnoreCase(safe(row.status))) continue;
                    if (!assignedUser.isBlank() && !assignedUser.equalsIgnoreCase(safe(row.assignedUserUuid))) continue;
                    if (!source.isBlank() && !source.equalsIgnoreCase(safe(row.source))) continue;
                    if (!q.isBlank()) {
                        String hay = (safe(row.displayName) + " " + safe(row.company) + " "
                                + safe(row.email) + " " + safe(row.phone) + " " + safe(row.notes)).toLowerCase(Locale.ROOT);
                        if (!hay.contains(q)) continue;
                    }
                    items.add(leadMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                out.put("status_counts", store.statusCounts(tenantUuid, includeArchived));
                return out;
            }

            case "leads.get": {
                String leadUuid = str(params, "lead_uuid");
                if (leadUuid.isBlank()) throw new IllegalArgumentException("lead_uuid is required.");
                leads_crm.LeadRec row = store.getLead(tenantUuid, leadUuid);
                if (row == null) throw new IllegalArgumentException("Lead not found.");
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("lead", leadMap(row));
                if (boolVal(params, "include_notes", true)) {
                    ArrayList<LinkedHashMap<String, Object>> notes = new ArrayList<LinkedHashMap<String, Object>>();
                    for (leads_crm.LeadNoteRec note : store.listNotes(tenantUuid, leadUuid)) {
                        if (note == null) continue;
                        notes.add(leadNoteMap(note));
                    }
                    out.put("notes", notes);
                }
                return out;
            }

            case "leads.create": {
                Map<String, Object> raw = mapFrom(params.get("lead"), params);
                leads_crm.LeadInput input = leadInputFromMap(raw);
                leads_crm.LeadRec row = store.createLead(tenantUuid, input, str(params, "actor_user_uuid"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("lead", leadMap(row));
                return out;
            }

            case "leads.update": {
                String leadUuid = str(params, "lead_uuid");
                if (leadUuid.isBlank()) throw new IllegalArgumentException("lead_uuid is required.");
                leads_crm.LeadRec current = store.getLead(tenantUuid, leadUuid);
                if (current == null) throw new IllegalArgumentException("Lead not found.");

                Map<String, Object> patch = mapFrom(params.get("lead"), params);
                leads_crm.LeadInput input = leadInputFromMap(patch);
                if (!hasParam(patch, "status")) input.status = current.status;
                if (!hasParam(patch, "source")) input.source = current.source;
                if (!hasParam(patch, "intake_channel")) input.intakeChannel = current.intakeChannel;
                if (!hasParam(patch, "referred_by")) input.referredBy = current.referredBy;
                if (!hasParam(patch, "first_name")) input.firstName = current.firstName;
                if (!hasParam(patch, "last_name")) input.lastName = current.lastName;
                if (!hasParam(patch, "display_name")) input.displayName = current.displayName;
                if (!hasParam(patch, "company")) input.company = current.company;
                if (!hasParam(patch, "email")) input.email = current.email;
                if (!hasParam(patch, "phone")) input.phone = current.phone;
                if (!hasParam(patch, "notes")) input.notes = current.notes;
                if (!hasParam(patch, "tags_csv") && !hasParam(patch, "tags")) input.tagsCsv = current.tagsCsv;
                if (!hasParam(patch, "assigned_user_uuid")) input.assignedUserUuid = current.assignedUserUuid;
                if (!hasParam(patch, "matter_uuid")) input.matterUuid = current.matterUuid;
                if (!hasParam(patch, "archived")) input.archived = current.archived;

                leads_crm.LeadRec row = store.updateLead(tenantUuid, leadUuid, input, str(params, "actor_user_uuid"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("lead", leadMap(row));
                return out;
            }

            case "leads.set_archived": {
                String leadUuid = str(params, "lead_uuid");
                if (leadUuid.isBlank()) throw new IllegalArgumentException("lead_uuid is required.");
                boolean archived = boolVal(params, "archived", true);
                boolean changed = store.setArchived(tenantUuid, leadUuid, archived);
                leads_crm.LeadRec row = store.getLead(tenantUuid, leadUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("lead", leadMap(row));
                return out;
            }

            case "leads.notes.list": {
                String leadUuid = str(params, "lead_uuid");
                if (leadUuid.isBlank()) throw new IllegalArgumentException("lead_uuid is required.");
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (leads_crm.LeadNoteRec note : store.listNotes(tenantUuid, leadUuid)) {
                    if (note == null) continue;
                    items.add(leadNoteMap(note));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "leads.notes.add": {
                String leadUuid = str(params, "lead_uuid");
                if (leadUuid.isBlank()) throw new IllegalArgumentException("lead_uuid is required.");
                leads_crm.LeadNoteRec note = store.addNote(
                        tenantUuid,
                        leadUuid,
                        str(params, "body"),
                        str(params, "actor_user_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("note", leadNoteMap(note));
                return out;
            }

            case "leads.convert_to_matter": {
                String leadUuid = str(params, "lead_uuid");
                if (leadUuid.isBlank()) throw new IllegalArgumentException("lead_uuid is required.");
                leads_crm.LeadRec lead = store.getLead(tenantUuid, leadUuid);
                if (lead == null) throw new IllegalArgumentException("Lead not found.");

                String matterUuid = str(params, "matter_uuid");
                matters.MatterRec matter;
                if (!matterUuid.isBlank()) {
                    matter = matters.defaultStore().getByUuid(tenantUuid, matterUuid);
                    if (matter == null) throw new IllegalArgumentException("matter_uuid not found.");
                } else {
                    String label = str(params, "matter_label");
                    if (label.isBlank()) label = safe(lead.displayName).trim();
                    if (label.isBlank()) label = safe(lead.company).trim();
                    if (label.isBlank()) label = "New Matter";
                    matter = matters.defaultStore().create(tenantUuid, label, "", "", "", "", "");
                }

                leads_crm.LeadRec converted = store.convertToMatter(
                        tenantUuid,
                        leadUuid,
                        safe(matter == null ? "" : matter.uuid).trim(),
                        str(params, "actor_user_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("lead", leadMap(converted));
                out.put("matter", matterMap(matter));
                return out;
            }

            default:
                throw new UnsupportedOperationException("Unknown operation: " + op);
        }
    }

    private static LinkedHashMap<String, Object> executeBillingOperation(String operation,
                                                                          Map<String, Object> params,
                                                                          String tenantUuid) throws Exception {
        String op = safe(operation).trim().toLowerCase(Locale.ROOT);
        if (!(op.startsWith("billing.")
                || op.startsWith("invoices.")
                || op.startsWith("payments.")
                || op.startsWith("payment.")
                || op.startsWith("trust.")
                || op.startsWith("reconciliation."))) {
            return null;
        }

        billing_accounting ledger = billing_runtime_registry.tenantLedger(tenantUuid);

        switch (op) {
            case "billing.overview": {
                String matterUuid = str(params, "matter_uuid");
                ArrayList<LinkedHashMap<String, Object>> matterSummaries = new ArrayList<LinkedHashMap<String, Object>>();
                long invoiceOutstandingCents = 0L;
                long invoicePaidCents = 0L;
                long trustBalanceCents = 0L;
                long paymentReceivedCents = 0L;
                int invoiceCount = 0;

                List<matters.MatterRec> matterRows;
                if (!matterUuid.isBlank()) {
                    matters.MatterRec selected = matters.defaultStore().getByUuid(tenantUuid, matterUuid);
                    matterRows = selected == null ? List.of() : List.of(selected);
                } else {
                    matterRows = matters.defaultStore().listAll(tenantUuid);
                }

                for (matters.MatterRec matter : matterRows) {
                    if (matter == null) continue;
                    String mu = safe(matter.uuid).trim();
                    if (mu.isBlank()) continue;

                    List<billing_accounting.InvoiceRec> invoices = ledger.listInvoicesForMatter(mu);
                    List<billing_accounting.PaymentRec> payments = ledger.listPaymentsForMatter(mu);
                    long outstanding = 0L;
                    long paid = 0L;
                    for (billing_accounting.InvoiceRec inv : invoices) {
                        if (inv == null) continue;
                        outstanding += Math.max(0L, inv.outstandingCents);
                        paid += Math.max(0L, inv.paidCents);
                    }
                    long received = 0L;
                    for (billing_accounting.PaymentRec p : payments) {
                        if (p == null) continue;
                        received += Math.max(0L, p.amountCents);
                    }
                    long trust = Math.max(0L, ledger.matterTrustBalance(mu));

                    LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("matter_uuid", mu);
                    row.put("matter_label", safe(matter.label));
                    row.put("invoice_count", invoices.size());
                    row.put("invoice_outstanding_cents", outstanding);
                    row.put("invoice_paid_cents", paid);
                    row.put("payment_received_cents", received);
                    row.put("trust_balance_cents", trust);
                    matterSummaries.add(row);

                    invoiceOutstandingCents += outstanding;
                    invoicePaidCents += paid;
                    paymentReceivedCents += received;
                    trustBalanceCents += trust;
                    invoiceCount += invoices.size();
                }

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("matters", matterSummaries);
                out.put("matter_count", matterSummaries.size());
                out.put("invoice_count", invoiceCount);
                out.put("invoice_outstanding_cents", invoiceOutstandingCents);
                out.put("invoice_paid_cents", invoicePaidCents);
                out.put("payment_received_cents", paymentReceivedCents);
                out.put("trust_balance_cents", trustBalanceCents);
                return out;
            }

            case "billing.time_entries.create": {
                String matterUuid = str(params, "matter_uuid");
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                int minutes = clampInt(intVal(params, "minutes", intVal(params, "duration_minutes", 0)), 0, 24 * 60 * 31);
                long rateCents = moneyCents(params, "rate", "rate_cents");
                if (minutes <= 0) throw new IllegalArgumentException("minutes must be > 0.");
                if (rateCents < 0L) throw new IllegalArgumentException("rate must be >= 0.");

                billing_accounting.TimeEntryRec row = ledger.createTimeEntry(
                        matterUuid,
                        str(params, "user_uuid"),
                        str(params, "activity_code"),
                        str(params, "note"),
                        minutes,
                        rateCents,
                        str(params, "currency").isBlank() ? "USD" : str(params, "currency"),
                        !boolVal(params, "non_billable", false),
                        str(params, "worked_at")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("time_entry", billingTimeEntryMap(row));
                return out;
            }

            case "billing.time_entries.list": {
                String matterUuid = str(params, "matter_uuid");
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                List<billing_accounting.TimeEntryRec> rows = ledger.listTimeEntriesForMatter(matterUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (billing_accounting.TimeEntryRec row : rows) {
                    if (row == null) continue;
                    items.add(billingTimeEntryMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "billing.expense_entries.create": {
                String matterUuid = str(params, "matter_uuid");
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                long amountCents = moneyCents(params, "amount", "amount_cents");
                long taxCents = moneyCents(params, "tax", "tax_cents");
                if (amountCents < 0L || taxCents < 0L) throw new IllegalArgumentException("amount/tax must be >= 0.");

                billing_accounting.ExpenseEntryRec row = ledger.createExpenseEntry(
                        matterUuid,
                        str(params, "description"),
                        amountCents,
                        taxCents,
                        str(params, "currency").isBlank() ? "USD" : str(params, "currency"),
                        !boolVal(params, "non_billable", false),
                        str(params, "incurred_at")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("expense_entry", billingExpenseEntryMap(row));
                return out;
            }

            case "billing.expense_entries.list": {
                String matterUuid = str(params, "matter_uuid");
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                List<billing_accounting.ExpenseEntryRec> rows = ledger.listExpenseEntriesForMatter(matterUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (billing_accounting.ExpenseEntryRec row : rows) {
                    if (row == null) continue;
                    items.add(billingExpenseEntryMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "invoices.list": {
                String matterUuid = str(params, "matter_uuid");
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                List<billing_accounting.InvoiceRec> rows = ledger.listInvoicesForMatter(matterUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (billing_accounting.InvoiceRec row : rows) {
                    if (row == null) continue;
                    items.add(billingInvoiceMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "invoices.get": {
                String invoiceUuid = str(params, "invoice_uuid");
                if (invoiceUuid.isBlank()) throw new IllegalArgumentException("invoice_uuid is required.");
                billing_accounting.InvoiceRec row = ledger.getInvoice(invoiceUuid);
                if (row == null) throw new IllegalArgumentException("Invoice not found.");
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("invoice", billingInvoiceMap(row));
                if (boolVal(params, "include_details", true)) {
                    ArrayList<LinkedHashMap<String, Object>> paymentRows = new ArrayList<LinkedHashMap<String, Object>>();
                    for (billing_accounting.PaymentRec p : ledger.listPaymentsForInvoice(invoiceUuid)) {
                        if (p == null) continue;
                        paymentRows.add(billingPaymentMap(p));
                    }
                    ArrayList<LinkedHashMap<String, Object>> trustRows = new ArrayList<LinkedHashMap<String, Object>>();
                    for (billing_accounting.TrustTxnRec t : ledger.listTrustTransactionsForInvoice(invoiceUuid)) {
                        if (t == null) continue;
                        trustRows.add(billingTrustTxnMap(t));
                    }
                    out.put("payments", paymentRows);
                    out.put("trust_transactions", trustRows);
                }
                return out;
            }

            case "invoices.draft": {
                String matterUuid = str(params, "matter_uuid");
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                billing_accounting.InvoiceRec row = ledger.draftInvoiceForMatter(
                        matterUuid,
                        str(params, "issue_date"),
                        str(params, "due_at"),
                        str(params, "currency").isBlank() ? "USD" : str(params, "currency")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("invoice", billingInvoiceMap(row));
                return out;
            }

            case "invoices.finalize": {
                String invoiceUuid = str(params, "invoice_uuid");
                if (invoiceUuid.isBlank()) throw new IllegalArgumentException("invoice_uuid is required.");
                billing_accounting.InvoiceRec row = ledger.finalizeInvoice(invoiceUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("invoice", billingInvoiceMap(row));
                return out;
            }

            case "invoices.link_external": {
                String invoiceUuid = str(params, "invoice_uuid");
                String clioBillId = str(params, "clio_bill_id");
                if (invoiceUuid.isBlank() || clioBillId.isBlank()) {
                    throw new IllegalArgumentException("invoice_uuid and clio_bill_id are required.");
                }
                billing_accounting.InvoiceRec row = ledger.linkInvoiceToClioBill(invoiceUuid, clioBillId);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("invoice", billingInvoiceMap(row));
                return out;
            }

            case "payments.list": {
                String matterUuid = str(params, "matter_uuid");
                String invoiceUuid = str(params, "invoice_uuid");
                List<billing_accounting.PaymentRec> rows = !invoiceUuid.isBlank()
                        ? ledger.listPaymentsForInvoice(invoiceUuid)
                        : ledger.listPaymentsForMatter(matterUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (billing_accounting.PaymentRec row : rows) {
                    if (row == null) continue;
                    items.add(billingPaymentMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "payments.record": {
                String invoiceUuid = str(params, "invoice_uuid");
                if (invoiceUuid.isBlank()) throw new IllegalArgumentException("invoice_uuid is required.");
                long amountCents = moneyCents(params, "amount", "amount_cents");
                if (amountCents <= 0L) throw new IllegalArgumentException("amount must be > 0.");
                billing_accounting.PaymentRec row = ledger.recordOperatingPayment(
                        invoiceUuid,
                        amountCents,
                        str(params, "posted_at"),
                        str(params, "reference")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("payment", billingPaymentMap(row));
                return out;
            }

            case "trust.deposits.record": {
                String matterUuid = str(params, "matter_uuid");
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                long amountCents = moneyCents(params, "amount", "amount_cents");
                if (amountCents <= 0L) throw new IllegalArgumentException("amount must be > 0.");
                billing_accounting.TrustTxnRec row = ledger.recordTrustDeposit(
                        matterUuid,
                        amountCents,
                        str(params, "currency").isBlank() ? "USD" : str(params, "currency"),
                        str(params, "posted_at"),
                        str(params, "reference")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("trust_transaction", billingTrustTxnMap(row));
                return out;
            }

            case "trust.apply.record": {
                String invoiceUuid = str(params, "invoice_uuid");
                if (invoiceUuid.isBlank()) throw new IllegalArgumentException("invoice_uuid is required.");
                long amountCents = moneyCents(params, "amount", "amount_cents");
                if (amountCents <= 0L) throw new IllegalArgumentException("amount must be > 0.");
                billing_accounting.TrustTxnRec row = ledger.applyTrustToInvoice(
                        invoiceUuid,
                        amountCents,
                        str(params, "posted_at"),
                        str(params, "reference")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("trust_transaction", billingTrustTxnMap(row));
                return out;
            }

            case "trust.refunds.record": {
                String matterUuid = str(params, "matter_uuid");
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                long amountCents = moneyCents(params, "amount", "amount_cents");
                if (amountCents <= 0L) throw new IllegalArgumentException("amount must be > 0.");
                billing_accounting.TrustTxnRec row = ledger.recordTrustRefund(
                        matterUuid,
                        amountCents,
                        str(params, "currency").isBlank() ? "USD" : str(params, "currency"),
                        str(params, "posted_at"),
                        str(params, "reference")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("trust_transaction", billingTrustTxnMap(row));
                return out;
            }

            case "trust.transactions.list": {
                String matterUuid = str(params, "matter_uuid");
                String invoiceUuid = str(params, "invoice_uuid");
                List<billing_accounting.TrustTxnRec> rows = !invoiceUuid.isBlank()
                        ? ledger.listTrustTransactionsForInvoice(invoiceUuid)
                        : ledger.listTrustTransactionsForMatter(matterUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (billing_accounting.TrustTxnRec row : rows) {
                    if (row == null) continue;
                    items.add(billingTrustTxnMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "reconciliation.snapshot": {
                long statementEndingBalanceCents = moneyCents(params, "statement_ending_balance", "statement_ending_balance_cents");
                billing_accounting.TrustReconciliationRec rec = ledger.trustReconciliation(
                        str(params, "statement_date"),
                        statementEndingBalanceCents
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("snapshot", billingTrustReconciliationMap(rec));
                return out;
            }

            case "reconciliation.compliance": {
                long statementEndingBalanceCents = moneyCents(params, "statement_ending_balance", "statement_ending_balance_cents");
                billing_accounting.ComplianceSnapshot rec = ledger.complianceSnapshot(
                        str(params, "statement_date"),
                        statementEndingBalanceCents
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("compliance", billingComplianceMap(rec));
                return out;
            }

            case "payment.processors.list": {
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (online_payments.ProcessorInfo p : online_payments.defaultStore().listProcessors()) {
                    if (p == null) continue;
                    LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("processor_key", safe(p.processorKey));
                    row.put("label", safe(p.label));
                    row.put("mode", safe(p.mode));
                    items.add(row);
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "payment.checkout.create": {
                Map<String, Object> raw = mapFrom(params.get("checkout"), params);
                online_payments.CheckoutInput input = checkoutInputFromMap(raw);
                online_payments.CheckoutResult created = online_payments.defaultStore().createCheckout(tenantUuid, input);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("transaction", paymentTransactionMap(created == null ? null : created.transaction));
                out.put("checkout_url", safe(created == null ? "" : created.checkoutUrl));
                return out;
            }

            case "payment.transactions.list": {
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (online_payments.PaymentTransactionRec tx : online_payments.defaultStore().listTransactions(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "invoice_uuid"),
                        str(params, "status")
                )) {
                    if (tx == null) continue;
                    items.add(paymentTransactionMap(tx));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "payment.transactions.get": {
                String transactionUuid = str(params, "transaction_uuid");
                if (transactionUuid.isBlank()) throw new IllegalArgumentException("transaction_uuid is required.");
                online_payments.PaymentTransactionRec tx = online_payments.defaultStore().getTransaction(tenantUuid, transactionUuid);
                if (tx == null) throw new IllegalArgumentException("Transaction not found.");
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("transaction", paymentTransactionMap(tx));
                return out;
            }

            case "payment.transactions.mark_paid": {
                String transactionUuid = str(params, "transaction_uuid");
                if (transactionUuid.isBlank()) throw new IllegalArgumentException("transaction_uuid is required.");
                online_payments.PaymentTransactionRec tx = online_payments.defaultStore().markPaid(
                        tenantUuid,
                        transactionUuid,
                        str(params, "provider_payment_id"),
                        str(params, "reference"),
                        str(params, "posted_at")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("transaction", paymentTransactionMap(tx));
                return out;
            }

            case "payment.transactions.mark_failed": {
                String transactionUuid = str(params, "transaction_uuid");
                if (transactionUuid.isBlank()) throw new IllegalArgumentException("transaction_uuid is required.");
                online_payments.PaymentTransactionRec tx = online_payments.defaultStore().markFailed(
                        tenantUuid,
                        transactionUuid,
                        str(params, "error_message")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("transaction", paymentTransactionMap(tx));
                return out;
            }

            case "payment.transactions.cancel": {
                String transactionUuid = str(params, "transaction_uuid");
                if (transactionUuid.isBlank()) throw new IllegalArgumentException("transaction_uuid is required.");
                online_payments.PaymentTransactionRec tx = online_payments.defaultStore().setCancelled(
                        tenantUuid,
                        transactionUuid,
                        str(params, "reason")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("transaction", paymentTransactionMap(tx));
                return out;
            }

            default:
                throw new UnsupportedOperationException("Unknown operation: " + op);
        }
    }

    private static LinkedHashMap<String, Object> executeEsignOperation(String operation,
                                                                        Map<String, Object> params,
                                                                        String tenantUuid) throws Exception {
        String op = safe(operation).trim().toLowerCase(Locale.ROOT);
        if (!op.startsWith("esign.")) return null;

        esign_requests store = esign_requests.defaultStore();

        switch (op) {
            case "esign.providers.list": {
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                items.add(esignProviderRow("manual_notice", "Manual Notice"));
                items.add(esignProviderRow("docusign_stub", "DocuSign (Stub)"));
                items.add(esignProviderRow("adobesign_stub", "Adobe Sign (Stub)"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "esign.requests.list": {
                String matterUuid = str(params, "matter_uuid");
                String status = str(params, "status").toLowerCase(Locale.ROOT).trim();
                String provider = str(params, "provider_key").toLowerCase(Locale.ROOT).trim();
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (esign_requests.SignatureRequestRec row : store.listRequests(tenantUuid)) {
                    if (row == null) continue;
                    if (!matterUuid.isBlank() && !matterUuid.equalsIgnoreCase(safe(row.matterUuid))) continue;
                    if (!status.isBlank() && !status.equalsIgnoreCase(safe(row.status))) continue;
                    if (!provider.isBlank() && !provider.equalsIgnoreCase(safe(row.providerKey))) continue;
                    items.add(signatureRequestMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "esign.requests.get": {
                String requestUuid = str(params, "request_uuid");
                if (requestUuid.isBlank()) throw new IllegalArgumentException("request_uuid is required.");
                esign_requests.SignatureRequestRec row = store.getRequest(tenantUuid, requestUuid);
                if (row == null) throw new IllegalArgumentException("Signature request not found.");
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("request", signatureRequestMap(row));
                if (boolVal(params, "include_events", true)) {
                    ArrayList<LinkedHashMap<String, Object>> events = new ArrayList<LinkedHashMap<String, Object>>();
                    for (esign_requests.SignatureEventRec e : store.listEvents(tenantUuid, requestUuid)) {
                        if (e == null) continue;
                        events.add(signatureEventMap(e));
                    }
                    out.put("events", events);
                }
                return out;
            }

            case "esign.requests.create": {
                Map<String, Object> raw = mapFrom(params.get("request"), params);
                esign_requests.CreateInput input = new esign_requests.CreateInput();
                input.providerKey = str(raw, "provider_key");
                input.providerRequestId = str(raw, "provider_request_id");
                input.matterUuid = str(raw, "matter_uuid");
                input.documentUuid = str(raw, "document_uuid");
                input.partUuid = str(raw, "part_uuid");
                input.versionUuid = str(raw, "version_uuid");
                input.subject = str(raw, "subject");
                input.toCsv = str(raw, "to");
                input.ccCsv = str(raw, "cc");
                input.bccCsv = str(raw, "bcc");
                input.signatureLink = str(raw, "signature_link");
                input.deliveryMode = str(raw, "delivery_mode");
                input.requestedByUserUuid = str(raw, "actor_user_uuid");
                input.status = str(raw, "status");

                esign_requests.SignatureRequestRec created = store.createRequest(tenantUuid, input);
                try {
                    LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
                    payload.put("request_uuid", safe(created == null ? "" : created.requestUuid));
                    payload.put("provider_key", safe(created == null ? "" : created.providerKey));
                    payload.put("status", safe(created == null ? "" : created.status));
                    payload.put("matter_uuid", safe(created == null ? "" : created.matterUuid));
                    integration_webhooks.defaultStore().dispatchEvent(tenantUuid, "esign.request.created", payload);
                } catch (Exception ignored) {
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("request", signatureRequestMap(created));
                return out;
            }

            case "esign.requests.update_status": {
                String requestUuid = str(params, "request_uuid");
                if (requestUuid.isBlank()) throw new IllegalArgumentException("request_uuid is required.");
                esign_requests.SignatureRequestRec updated = store.updateStatus(
                        tenantUuid,
                        requestUuid,
                        str(params, "status"),
                        str(params, "event_type"),
                        str(params, "note"),
                        str(params, "actor_user_uuid"),
                        str(params, "provider_request_id")
                );
                try {
                    LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
                    payload.put("request_uuid", safe(updated == null ? "" : updated.requestUuid));
                    payload.put("provider_key", safe(updated == null ? "" : updated.providerKey));
                    payload.put("status", safe(updated == null ? "" : updated.status));
                    payload.put("matter_uuid", safe(updated == null ? "" : updated.matterUuid));
                    integration_webhooks.defaultStore().dispatchEvent(tenantUuid, "esign.request.status_changed", payload);
                } catch (Exception ignored) {
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("request", signatureRequestMap(updated));
                return out;
            }

            case "esign.requests.events.list": {
                String requestUuid = str(params, "request_uuid");
                if (requestUuid.isBlank()) throw new IllegalArgumentException("request_uuid is required.");
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (esign_requests.SignatureEventRec e : store.listEvents(tenantUuid, requestUuid)) {
                    if (e == null) continue;
                    items.add(signatureEventMap(e));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            default:
                throw new UnsupportedOperationException("Unknown operation: " + op);
        }
    }

    private static LinkedHashMap<String, Object> executeIntegrationsOperation(String operation,
                                                                               Map<String, Object> params,
                                                                               String tenantUuid) throws Exception {
        String op = safe(operation).trim().toLowerCase(Locale.ROOT);
        if (!(op.startsWith("integrations.webhooks.") || "integrations.events.emit".equals(op))) return null;

        integration_webhooks store = integration_webhooks.defaultStore();

        switch (op) {
            case "integrations.webhooks.list": {
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (integration_webhooks.EndpointRec row : store.listEndpoints(tenantUuid)) {
                    if (row == null) continue;
                    items.add(webhookEndpointMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "integrations.webhooks.get": {
                String webhookUuid = str(params, "webhook_uuid");
                if (webhookUuid.isBlank()) throw new IllegalArgumentException("webhook_uuid is required.");
                integration_webhooks.EndpointRec row = store.getEndpoint(tenantUuid, webhookUuid);
                if (row == null) throw new IllegalArgumentException("Webhook endpoint not found.");
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("webhook", webhookEndpointMap(row));
                return out;
            }

            case "integrations.webhooks.create": {
                Map<String, Object> raw = mapFrom(params.get("webhook"), params);
                integration_webhooks.EndpointInput input = integrationWebhookInputFromMap(raw);
                integration_webhooks.EndpointRec row = store.createEndpoint(tenantUuid, input);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("webhook", webhookEndpointMap(row));
                return out;
            }

            case "integrations.webhooks.update": {
                String webhookUuid = str(params, "webhook_uuid");
                if (webhookUuid.isBlank()) throw new IllegalArgumentException("webhook_uuid is required.");
                Map<String, Object> raw = mapFrom(params.get("webhook"), params);
                integration_webhooks.EndpointInput input = integrationWebhookInputFromMap(raw);
                integration_webhooks.EndpointRec row = store.updateEndpoint(tenantUuid, webhookUuid, input);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("webhook", webhookEndpointMap(row));
                return out;
            }

            case "integrations.webhooks.delete": {
                String webhookUuid = str(params, "webhook_uuid");
                if (webhookUuid.isBlank()) throw new IllegalArgumentException("webhook_uuid is required.");
                boolean deleted = store.deleteEndpoint(tenantUuid, webhookUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("deleted", deleted);
                return out;
            }

            case "integrations.webhooks.deliveries.list": {
                int limit = clampInt(intVal(params, "limit", 100), 1, 1000);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (integration_webhooks.DeliveryRec row : store.listDeliveries(tenantUuid, limit)) {
                    if (row == null) continue;
                    items.add(webhookDeliveryMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "integrations.events.emit": {
                String eventType = str(params, "event_type").toLowerCase(Locale.ROOT).trim();
                if (eventType.isBlank()) throw new IllegalArgumentException("event_type is required.");
                Map<String, Object> payload = mapFrom(params.get("payload"), new LinkedHashMap<String, Object>());
                integration_webhooks.DispatchResult result = store.dispatchEvent(tenantUuid, eventType, payload);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("event_type", eventType);
                out.put("endpoint_count", result.endpointCount);
                out.put("attempted_count", result.attemptedCount);
                out.put("success_count", result.successCount);
                out.put("failure_count", result.failureCount);
                ArrayList<LinkedHashMap<String, Object>> deliveries = new ArrayList<LinkedHashMap<String, Object>>();
                for (integration_webhooks.DeliveryRec row : result.deliveries) {
                    if (row == null) continue;
                    deliveries.add(webhookDeliveryMap(row));
                }
                out.put("deliveries", deliveries);
                return out;
            }

            default:
                throw new UnsupportedOperationException("Unknown operation: " + op);
        }
    }

    private static LinkedHashMap<String, Object> executeAnalyticsOperation(String operation,
                                                                            Map<String, Object> params,
                                                                            String tenantUuid) throws Exception {
        String op = safe(operation).trim().toLowerCase(Locale.ROOT);
        if (!op.startsWith("analytics.")) return null;

        kpi_analytics service = kpi_analytics.defaultService();
        switch (op) {
            case "analytics.kpis.summary": {
                kpi_analytics.SummaryRec rec = service.summary(tenantUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("summary", analyticsSummaryMap(rec));
                return out;
            }

            case "analytics.kpis.daily": {
                int days = clampInt(intVal(params, "days", 30), 1, 366);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (kpi_analytics.DailyRec row : service.dailySeries(tenantUuid, days)) {
                    if (row == null) continue;
                    items.add(analyticsDailyMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("days", days);
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            default:
                throw new UnsupportedOperationException("Unknown operation: " + op);
        }
    }

    private static LinkedHashMap<String, Object> executeMailOperation(String operation,
                                                                       Map<String, Object> params,
                                                                       String tenantUuid) throws Exception {
        String op = safe(operation).trim().toLowerCase(Locale.ROOT);
        if (!op.startsWith("mail.")) return null;

        postal_mail store = postal_mail.defaultStore();

        switch (op) {
            case "mail.items.list": {
                boolean includeArchived = boolVal(params, "include_archived", false);
                String matterFilter = str(params, "matter_uuid");
                String directionFilter = str(params, "direction");
                String statusFilter = str(params, "status");
                String workflowFilter = str(params, "workflow");
                String serviceFilter = str(params, "service");
                String trackingFilter = str(params, "tracking_number");
                String q = str(params, "q").toLowerCase(Locale.ROOT).trim();

                List<postal_mail.MailItemRec> rows = store.listItems(tenantUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (postal_mail.MailItemRec row : rows) {
                    if (row == null) continue;
                    if (!includeArchived && row.archived) continue;
                    if (!matterFilter.isBlank() && !matterFilter.equalsIgnoreCase(safe(row.matterUuid).trim())) continue;
                    if (!directionFilter.isBlank() && !directionFilter.equalsIgnoreCase(safe(row.direction).trim())) continue;
                    if (!statusFilter.isBlank() && !statusFilter.equalsIgnoreCase(safe(row.status).trim())) continue;
                    if (!workflowFilter.isBlank() && !workflowFilter.equalsIgnoreCase(safe(row.workflow).trim())) continue;
                    if (!serviceFilter.isBlank() && !serviceFilter.equalsIgnoreCase(safe(row.service).trim())) continue;
                    if (!trackingFilter.isBlank() && !trackingFilter.equalsIgnoreCase(safe(row.trackingNumber).trim())) continue;
                    if (!q.isBlank()) {
                        String hay = (safe(row.subject) + " " + safe(row.notes) + " "
                                + safe(row.providerReference) + " " + safe(row.trackingNumber)).toLowerCase(Locale.ROOT);
                        if (!hay.contains(q)) continue;
                    }
                    items.add(mailItemMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "mail.items.get": {
                String mailUuid = mailUuidParam(params);
                if (mailUuid.isBlank()) throw new IllegalArgumentException("mail_uuid is required.");
                postal_mail.MailItemRec row = store.getItem(tenantUuid, mailUuid);
                if (row == null) throw new IllegalArgumentException("Mail item not found.");

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("mail", mailItemMap(row));

                if (boolVal(params, "include_details", true)) {
                    ArrayList<LinkedHashMap<String, Object>> parts = new ArrayList<LinkedHashMap<String, Object>>();
                    for (postal_mail.MailPartRec p : store.listParts(tenantUuid, mailUuid)) {
                        if (p == null) continue;
                        parts.add(mailPartMap(p));
                    }

                    ArrayList<LinkedHashMap<String, Object>> recipients = new ArrayList<LinkedHashMap<String, Object>>();
                    for (postal_mail.RecipientRec r : store.listRecipients(tenantUuid, mailUuid)) {
                        if (r == null) continue;
                        recipients.add(mailRecipientMap(r));
                    }

                    ArrayList<LinkedHashMap<String, Object>> trackingEvents = new ArrayList<LinkedHashMap<String, Object>>();
                    for (postal_mail.TrackingEventRec e : store.listTrackingEvents(tenantUuid, mailUuid)) {
                        if (e == null) continue;
                        trackingEvents.add(mailTrackingEventMap(e));
                    }

                    out.put("parts", parts);
                    out.put("recipients", recipients);
                    out.put("tracking_events", trackingEvents);
                }
                return out;
            }

            case "mail.items.create": {
                postal_mail.MailItemRec created = store.createItem(
                        tenantUuid,
                        str(params, "matter_uuid"),
                        str(params, "direction"),
                        str(params, "workflow"),
                        str(params, "service"),
                        str(params, "status"),
                        str(params, "subject"),
                        str(params, "notes"),
                        str(params, "source_email_address"),
                        str(params, "source_document_uuid"),
                        str(params, "source_part_uuid"),
                        str(params, "source_version_uuid"),
                        str(params, "actor_user_uuid")
                );

                String mailUuid = safe(created == null ? "" : created.uuid).trim();
                boolean allowInvalidAddress = boolVal(params, "allow_invalid_address", false);

                List<Map<String, Object>> recipientRows = objectList(params.get("recipients"));
                if (!recipientRows.isEmpty()) {
                    ArrayList<postal_mail.RecipientRec> replacements = new ArrayList<postal_mail.RecipientRec>();
                    for (Map<String, Object> rr : recipientRows) {
                        if (rr == null) continue;
                        replacements.add(mailRecipientInputFromMap(rr, tenantUuid));
                    }
                    store.replaceRecipients(tenantUuid, mailUuid, replacements, allowInvalidAddress);
                }

                List<Map<String, Object>> partRows = objectList(params.get("parts"));
                for (Map<String, Object> pr : partRows) {
                    if (pr == null) continue;
                    store.addPart(
                            tenantUuid,
                            mailUuid,
                            str(pr, "part_type"),
                            str(pr, "label"),
                            str(pr, "document_uuid"),
                            str(pr, "part_uuid"),
                            str(pr, "version_uuid"),
                            str(pr, "notes"),
                            str(params, "actor_user_uuid")
                    );
                }

                if (hasParam(params, "tracking_number")
                        || hasParam(params, "tracking_carrier")
                        || hasParam(params, "tracking_status")) {
                    store.updateTrackingSummary(
                            tenantUuid,
                            mailUuid,
                            str(params, "tracking_carrier"),
                            str(params, "tracking_number"),
                            str(params, "tracking_status"),
                            boolVal(params, "mark_sent_if_missing", true)
                    );
                }

                List<Map<String, Object>> trackingRows = objectList(params.get("tracking_events"));
                for (Map<String, Object> tr : trackingRows) {
                    if (tr == null) continue;
                    store.addTrackingEvent(
                            tenantUuid,
                            mailUuid,
                            str(tr, "carrier"),
                            str(tr, "tracking_number"),
                            str(tr, "status"),
                            str(tr, "location"),
                            str(tr, "event_at"),
                            str(tr, "notes"),
                            str(tr, "source")
                    );
                }

                if (hasParam(params, "archived")) {
                    store.setArchived(tenantUuid, mailUuid, boolVal(params, "archived", false));
                }

                postal_mail.MailItemRec refreshed = store.getItem(tenantUuid, mailUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("mail", mailItemMap(refreshed));
                return out;
            }

            case "mail.items.update": {
                String mailUuid = mailUuidParam(params);
                if (mailUuid.isBlank()) throw new IllegalArgumentException("mail_uuid is required.");
                postal_mail.MailItemRec current = store.getItem(tenantUuid, mailUuid);
                if (current == null) throw new IllegalArgumentException("Mail item not found.");

                postal_mail.MailItemRec update = copyMailItem(current);
                update.uuid = mailUuid;

                if (hasParam(params, "matter_uuid")) update.matterUuid = str(params, "matter_uuid");
                if (hasParam(params, "direction")) update.direction = str(params, "direction");
                if (hasParam(params, "workflow")) update.workflow = str(params, "workflow");
                if (hasParam(params, "service")) update.service = str(params, "service");
                if (hasParam(params, "status")) update.status = str(params, "status");
                if (hasParam(params, "subject")) update.subject = str(params, "subject");
                if (hasParam(params, "notes")) update.notes = str(params, "notes");
                if (hasParam(params, "source_email_address")) update.sourceEmailAddress = str(params, "source_email_address");
                if (hasParam(params, "source_document_uuid")) update.sourceDocumentUuid = str(params, "source_document_uuid");
                if (hasParam(params, "source_part_uuid")) update.sourcePartUuid = str(params, "source_part_uuid");
                if (hasParam(params, "source_version_uuid")) update.sourceVersionUuid = str(params, "source_version_uuid");
                if (hasParam(params, "filed_document_uuid")) update.filedDocumentUuid = str(params, "filed_document_uuid");
                if (hasParam(params, "filed_part_uuid")) update.filedPartUuid = str(params, "filed_part_uuid");
                if (hasParam(params, "filed_version_uuid")) update.filedVersionUuid = str(params, "filed_version_uuid");
                if (hasParam(params, "tracking_carrier")) update.trackingCarrier = str(params, "tracking_carrier");
                if (hasParam(params, "tracking_number")) update.trackingNumber = str(params, "tracking_number");
                if (hasParam(params, "tracking_status")) update.trackingStatus = str(params, "tracking_status");
                if (hasParam(params, "provider_reference")) update.providerReference = str(params, "provider_reference");
                if (hasParam(params, "provider_message")) update.providerMessage = str(params, "provider_message");
                if (hasParam(params, "provider_request_json")) update.providerRequestJson = str(params, "provider_request_json");
                if (hasParam(params, "provider_response_json")) update.providerResponseJson = str(params, "provider_response_json");
                if (hasParam(params, "address_validation_status")) update.addressValidationStatus = str(params, "address_validation_status");
                if (hasParam(params, "received_at")) update.receivedAt = str(params, "received_at");
                if (hasParam(params, "sent_at")) update.sentAt = str(params, "sent_at");
                if (hasParam(params, "reviewed_by")) update.reviewedBy = str(params, "reviewed_by");
                if (hasParam(params, "reviewed_at")) update.reviewedAt = str(params, "reviewed_at");
                if (hasParam(params, "archived")) update.archived = boolVal(params, "archived", update.archived);

                boolean changed = store.updateItem(tenantUuid, update);
                postal_mail.MailItemRec refreshed = store.getItem(tenantUuid, mailUuid);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("mail", mailItemMap(refreshed));
                return out;
            }

            case "mail.items.set_archived": {
                String mailUuid = mailUuidParam(params);
                if (mailUuid.isBlank()) throw new IllegalArgumentException("mail_uuid is required.");
                boolean archived = boolVal(params, "archived", true);
                boolean changed = store.setArchived(tenantUuid, mailUuid, archived);
                postal_mail.MailItemRec refreshed = store.getItem(tenantUuid, mailUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("mail", mailItemMap(refreshed));
                return out;
            }

            case "mail.items.mark_reviewed": {
                String mailUuid = mailUuidParam(params);
                if (mailUuid.isBlank()) throw new IllegalArgumentException("mail_uuid is required.");
                boolean changed = store.markReviewed(
                        tenantUuid,
                        mailUuid,
                        str(params, "reviewed_by"),
                        str(params, "review_notes")
                );
                postal_mail.MailItemRec refreshed = store.getItem(tenantUuid, mailUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("mail", mailItemMap(refreshed));
                return out;
            }

            case "mail.items.link_document": {
                String mailUuid = mailUuidParam(params);
                if (mailUuid.isBlank()) throw new IllegalArgumentException("mail_uuid is required.");
                boolean changed = store.linkFiledDocument(
                        tenantUuid,
                        mailUuid,
                        str(params, "filed_document_uuid"),
                        str(params, "filed_part_uuid"),
                        str(params, "filed_version_uuid")
                );
                postal_mail.MailItemRec refreshed = store.getItem(tenantUuid, mailUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("mail", mailItemMap(refreshed));
                return out;
            }

            case "mail.parts.list": {
                String mailUuid = mailUuidParam(params);
                if (mailUuid.isBlank()) throw new IllegalArgumentException("mail_uuid is required.");
                boolean includeTrashed = boolVal(params, "include_trashed", false);
                List<postal_mail.MailPartRec> rows = store.listParts(tenantUuid, mailUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (postal_mail.MailPartRec row : rows) {
                    if (row == null) continue;
                    if (!includeTrashed && row.trashed) continue;
                    items.add(mailPartMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "mail.parts.add": {
                String mailUuid = mailUuidParam(params);
                if (mailUuid.isBlank()) throw new IllegalArgumentException("mail_uuid is required.");
                Map<String, Object> nested = mapFrom(params.get("part"), params);
                postal_mail.MailPartRec row = store.addPart(
                        tenantUuid,
                        mailUuid,
                        str(nested, "part_type"),
                        str(nested, "label"),
                        str(nested, "document_uuid"),
                        str(nested, "part_uuid"),
                        str(nested, "version_uuid"),
                        str(nested, "notes"),
                        str(params, "actor_user_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("part", mailPartMap(row));
                return out;
            }

            case "mail.parts.set_trashed": {
                String mailUuid = mailUuidParam(params);
                String partUuid = str(params, "part_uuid");
                if (mailUuid.isBlank() || partUuid.isBlank()) {
                    throw new IllegalArgumentException("mail_uuid and part_uuid are required.");
                }
                boolean trashed = boolVal(params, "trashed", true);
                boolean changed = store.setPartTrashed(tenantUuid, mailUuid, partUuid, trashed);
                postal_mail.MailPartRec selected = null;
                for (postal_mail.MailPartRec row : store.listParts(tenantUuid, mailUuid)) {
                    if (row == null) continue;
                    if (partUuid.equals(safe(row.uuid).trim())) {
                        selected = row;
                        break;
                    }
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("part", mailPartMap(selected));
                return out;
            }

            case "mail.recipients.list": {
                String mailUuid = mailUuidParam(params);
                if (mailUuid.isBlank()) throw new IllegalArgumentException("mail_uuid is required.");
                List<postal_mail.RecipientRec> rows = store.listRecipients(tenantUuid, mailUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (postal_mail.RecipientRec row : rows) {
                    if (row == null) continue;
                    items.add(mailRecipientMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "mail.recipients.add": {
                String mailUuid = mailUuidParam(params);
                if (mailUuid.isBlank()) throw new IllegalArgumentException("mail_uuid is required.");
                Map<String, Object> nested = mapFrom(params.get("recipient"), params);
                postal_mail.RecipientRec input = mailRecipientInputFromMap(nested, tenantUuid);
                postal_mail.RecipientRec row = store.addRecipient(
                        tenantUuid,
                        mailUuid,
                        input,
                        boolVal(params, "allow_invalid_address", false)
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("recipient", mailRecipientMap(row));
                return out;
            }

            case "mail.recipients.replace": {
                String mailUuid = mailUuidParam(params);
                if (mailUuid.isBlank()) throw new IllegalArgumentException("mail_uuid is required.");
                List<Map<String, Object>> rows = objectList(params.get("recipients"));
                ArrayList<postal_mail.RecipientRec> replacements = new ArrayList<postal_mail.RecipientRec>();
                for (Map<String, Object> row : rows) {
                    if (row == null) continue;
                    replacements.add(mailRecipientInputFromMap(row, tenantUuid));
                }
                boolean changed = store.replaceRecipients(
                        tenantUuid,
                        mailUuid,
                        replacements,
                        boolVal(params, "allow_invalid_address", false)
                );
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (postal_mail.RecipientRec row : store.listRecipients(tenantUuid, mailUuid)) {
                    if (row == null) continue;
                    items.add(mailRecipientMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "mail.addresses.validate": {
                Map<String, Object> nested = mapFrom(params.get("address"), params);
                postal_mail.RecipientRec input = mailRecipientInputFromMap(nested, tenantUuid);
                postal_mail.AddressInput address = mailAddressInputFromRecipient(input);
                postal_mail.AddressValidationResult result = store.validateAddress(address);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("validation", mailAddressValidationMap(result));
                out.put("valid", result.valid);
                return out;
            }

            case "mail.tracking.validate": {
                postal_mail.TrackingValidationResult result = postal_mail.validateTrackingNumber(str(params, "tracking_number"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("tracking", mailTrackingValidationMap(result));
                out.put("valid", result.valid);
                return out;
            }

            case "mail.tracking.list": {
                String mailUuid = mailUuidParam(params);
                if (mailUuid.isBlank()) throw new IllegalArgumentException("mail_uuid is required.");
                List<postal_mail.TrackingEventRec> rows = store.listTrackingEvents(tenantUuid, mailUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (postal_mail.TrackingEventRec row : rows) {
                    if (row == null) continue;
                    items.add(mailTrackingEventMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "mail.tracking.add_event": {
                String mailUuid = mailUuidParam(params);
                if (mailUuid.isBlank()) throw new IllegalArgumentException("mail_uuid is required.");
                Map<String, Object> nested = mapFrom(params.get("event"), params);
                postal_mail.TrackingEventRec event = store.addTrackingEvent(
                        tenantUuid,
                        mailUuid,
                        str(nested, "carrier"),
                        str(nested, "tracking_number"),
                        str(nested, "status"),
                        str(nested, "location"),
                        str(nested, "event_at"),
                        str(nested, "notes"),
                        str(nested, "source")
                );
                postal_mail.MailItemRec mail = store.getItem(tenantUuid, mailUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("event", mailTrackingEventMap(event));
                out.put("mail", mailItemMap(mail));
                return out;
            }

            case "mail.tracking.update_summary": {
                String mailUuid = mailUuidParam(params);
                if (mailUuid.isBlank()) throw new IllegalArgumentException("mail_uuid is required.");
                boolean changed = store.updateTrackingSummary(
                        tenantUuid,
                        mailUuid,
                        str(params, "tracking_carrier"),
                        str(params, "tracking_number"),
                        str(params, "tracking_status"),
                        boolVal(params, "mark_sent_if_missing", true)
                );
                postal_mail.MailItemRec mail = store.getItem(tenantUuid, mailUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("mail", mailItemMap(mail));
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

    private static LinkedHashMap<String, Object> executeFactsOperation(String operation,
                                                                        Map<String, Object> params,
                                                                        String tenantUuid) throws Exception {
        String op = safe(operation).trim().toLowerCase(Locale.ROOT);
        if (!op.startsWith("facts.")) return null;

        matter_facts store = matter_facts.defaultStore();
        String matterUuid = str(params, "matter_uuid");

        switch (op) {
            case "facts.tree.get": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                boolean includeTrashed = boolVal(params, "include_trashed", false);
                List<matter_facts.ClaimRec> claims = store.listClaims(tenantUuid, matterUuid);
                List<matter_facts.ElementRec> elements = store.listElements(tenantUuid, matterUuid, "");
                List<matter_facts.FactRec> facts = store.listFacts(tenantUuid, matterUuid, "", "");

                ArrayList<LinkedHashMap<String, Object>> claimItems = new ArrayList<LinkedHashMap<String, Object>>();
                for (matter_facts.ClaimRec row : claims) {
                    if (row == null) continue;
                    if (!includeTrashed && row.trashed) continue;
                    claimItems.add(factsClaimMap(row));
                }

                ArrayList<LinkedHashMap<String, Object>> elementItems = new ArrayList<LinkedHashMap<String, Object>>();
                for (matter_facts.ElementRec row : elements) {
                    if (row == null) continue;
                    if (!includeTrashed && row.trashed) continue;
                    elementItems.add(factsElementMap(row));
                }

                ArrayList<LinkedHashMap<String, Object>> factItems = new ArrayList<LinkedHashMap<String, Object>>();
                for (matter_facts.FactRec row : facts) {
                    if (row == null) continue;
                    if (!includeTrashed && row.trashed) continue;
                    factItems.add(factsFactMap(row));
                }

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("matter_uuid", matterUuid);
                out.put("claims", claimItems);
                out.put("elements", elementItems);
                out.put("facts", factItems);
                out.put("claim_count", claimItems.size());
                out.put("element_count", elementItems.size());
                out.put("fact_count", factItems.size());
                out.put("report", factsReportMap(store.reportRefs(tenantUuid, matterUuid)));
                return out;
            }

            case "facts.claims.list": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                boolean includeTrashed = boolVal(params, "include_trashed", false);
                List<matter_facts.ClaimRec> rows = store.listClaims(tenantUuid, matterUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (matter_facts.ClaimRec row : rows) {
                    if (row == null) continue;
                    if (!includeTrashed && row.trashed) continue;
                    items.add(factsClaimMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                out.put("report", factsReportMap(store.reportRefs(tenantUuid, matterUuid)));
                return out;
            }

            case "facts.claims.get": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                matter_facts.ClaimRec row = store.getClaim(tenantUuid, matterUuid, str(params, "claim_uuid"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("claim", factsClaimMap(row));
                out.put("report", factsReportMap(store.reportRefs(tenantUuid, matterUuid)));
                return out;
            }

            case "facts.claims.create": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                matter_facts.ClaimRec row = store.createClaim(
                        tenantUuid,
                        matterUuid,
                        str(params, "title"),
                        str(params, "summary"),
                        intVal(params, "sort_order", 0),
                        str(params, "actor_user_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("claim", factsClaimMap(row));
                out.put("report", factsReportMap(store.reportRefs(tenantUuid, matterUuid)));
                return out;
            }

            case "facts.claims.update": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                matter_facts.ClaimRec current = store.getClaim(tenantUuid, matterUuid, str(params, "claim_uuid"));
                if (current == null) throw new IllegalArgumentException("Claim not found.");
                matter_facts.ClaimRec in = new matter_facts.ClaimRec();
                in.uuid = safe(current.uuid);
                in.title = hasParam(params, "title") ? str(params, "title") : safe(current.title);
                in.summary = hasParam(params, "summary") ? str(params, "summary") : safe(current.summary);
                in.sortOrder = hasParam(params, "sort_order") ? intVal(params, "sort_order", current.sortOrder) : current.sortOrder;
                boolean changed = store.updateClaim(tenantUuid, matterUuid, in, str(params, "actor_user_uuid"));
                matter_facts.ClaimRec row = store.getClaim(tenantUuid, matterUuid, in.uuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("claim", factsClaimMap(row));
                out.put("report", factsReportMap(store.reportRefs(tenantUuid, matterUuid)));
                return out;
            }

            case "facts.claims.set_trashed": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                String claimUuid = str(params, "claim_uuid");
                boolean trashed = boolVal(params, "trashed", true);
                boolean changed = store.setClaimTrashed(
                        tenantUuid,
                        matterUuid,
                        claimUuid,
                        trashed,
                        str(params, "actor_user_uuid")
                );
                matter_facts.ClaimRec row = store.getClaim(tenantUuid, matterUuid, claimUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("claim", factsClaimMap(row));
                out.put("report", factsReportMap(store.reportRefs(tenantUuid, matterUuid)));
                return out;
            }

            case "facts.elements.list": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                boolean includeTrashed = boolVal(params, "include_trashed", false);
                List<matter_facts.ElementRec> rows = store.listElements(tenantUuid, matterUuid, str(params, "claim_uuid"));
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (matter_facts.ElementRec row : rows) {
                    if (row == null) continue;
                    if (!includeTrashed && row.trashed) continue;
                    items.add(factsElementMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                out.put("report", factsReportMap(store.reportRefs(tenantUuid, matterUuid)));
                return out;
            }

            case "facts.elements.get": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                matter_facts.ElementRec row = store.getElement(tenantUuid, matterUuid, str(params, "element_uuid"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("element", factsElementMap(row));
                out.put("report", factsReportMap(store.reportRefs(tenantUuid, matterUuid)));
                return out;
            }

            case "facts.elements.create": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                matter_facts.ElementRec row = store.createElement(
                        tenantUuid,
                        matterUuid,
                        str(params, "claim_uuid"),
                        str(params, "title"),
                        str(params, "notes"),
                        intVal(params, "sort_order", 0),
                        str(params, "actor_user_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("element", factsElementMap(row));
                out.put("report", factsReportMap(store.reportRefs(tenantUuid, matterUuid)));
                return out;
            }

            case "facts.elements.update": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                matter_facts.ElementRec current = store.getElement(tenantUuid, matterUuid, str(params, "element_uuid"));
                if (current == null) throw new IllegalArgumentException("Element not found.");
                matter_facts.ElementRec in = new matter_facts.ElementRec();
                in.uuid = safe(current.uuid);
                in.claimUuid = hasParam(params, "claim_uuid") ? str(params, "claim_uuid") : safe(current.claimUuid);
                in.title = hasParam(params, "title") ? str(params, "title") : safe(current.title);
                in.notes = hasParam(params, "notes") ? str(params, "notes") : safe(current.notes);
                in.sortOrder = hasParam(params, "sort_order") ? intVal(params, "sort_order", current.sortOrder) : current.sortOrder;
                boolean changed = store.updateElement(tenantUuid, matterUuid, in, str(params, "actor_user_uuid"));
                matter_facts.ElementRec row = store.getElement(tenantUuid, matterUuid, in.uuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("element", factsElementMap(row));
                out.put("report", factsReportMap(store.reportRefs(tenantUuid, matterUuid)));
                return out;
            }

            case "facts.elements.set_trashed": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                String elementUuid = str(params, "element_uuid");
                boolean trashed = boolVal(params, "trashed", true);
                boolean changed = store.setElementTrashed(
                        tenantUuid,
                        matterUuid,
                        elementUuid,
                        trashed,
                        str(params, "actor_user_uuid")
                );
                matter_facts.ElementRec row = store.getElement(tenantUuid, matterUuid, elementUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("element", factsElementMap(row));
                out.put("report", factsReportMap(store.reportRefs(tenantUuid, matterUuid)));
                return out;
            }

            case "facts.facts.list": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                boolean includeTrashed = boolVal(params, "include_trashed", false);
                List<matter_facts.FactRec> rows = store.listFacts(
                        tenantUuid,
                        matterUuid,
                        str(params, "claim_uuid"),
                        str(params, "element_uuid")
                );
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (matter_facts.FactRec row : rows) {
                    if (row == null) continue;
                    if (!includeTrashed && row.trashed) continue;
                    items.add(factsFactMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                out.put("report", factsReportMap(store.reportRefs(tenantUuid, matterUuid)));
                return out;
            }

            case "facts.facts.get": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                matter_facts.FactRec row = store.getFact(tenantUuid, matterUuid, str(params, "fact_uuid"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("fact", factsFactMap(row));
                out.put("report", factsReportMap(store.reportRefs(tenantUuid, matterUuid)));
                return out;
            }

            case "facts.facts.create": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                matter_facts.FactRec row = store.createFact(
                        tenantUuid,
                        matterUuid,
                        str(params, "claim_uuid"),
                        str(params, "element_uuid"),
                        str(params, "summary"),
                        str(params, "detail"),
                        str(params, "internal_notes"),
                        str(params, "status"),
                        str(params, "strength"),
                        str(params, "document_uuid"),
                        str(params, "part_uuid"),
                        str(params, "version_uuid"),
                        intVal(params, "page_number", 0),
                        intVal(params, "sort_order", 0),
                        str(params, "actor_user_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("fact", factsFactMap(row));
                out.put("report", factsReportMap(store.reportRefs(tenantUuid, matterUuid)));
                return out;
            }

            case "facts.facts.update":
            case "facts.facts.link_document": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                String factUuid = str(params, "fact_uuid");
                matter_facts.FactRec current = store.getFact(tenantUuid, matterUuid, factUuid);
                if (current == null) throw new IllegalArgumentException("Fact not found.");
                matter_facts.FactRec in = new matter_facts.FactRec();
                in.uuid = safe(current.uuid);
                in.claimUuid = hasParam(params, "claim_uuid") ? str(params, "claim_uuid") : safe(current.claimUuid);
                in.elementUuid = hasParam(params, "element_uuid") ? str(params, "element_uuid") : safe(current.elementUuid);
                in.summary = hasParam(params, "summary") ? str(params, "summary") : safe(current.summary);
                in.detail = hasParam(params, "detail") ? str(params, "detail") : safe(current.detail);
                in.internalNotes = hasParam(params, "internal_notes") ? str(params, "internal_notes") : safe(current.internalNotes);
                in.status = hasParam(params, "status") ? str(params, "status") : safe(current.status);
                in.strength = hasParam(params, "strength") ? str(params, "strength") : safe(current.strength);
                in.documentUuid = hasParam(params, "document_uuid") ? str(params, "document_uuid") : safe(current.documentUuid);
                in.partUuid = hasParam(params, "part_uuid") ? str(params, "part_uuid") : safe(current.partUuid);
                in.versionUuid = hasParam(params, "version_uuid") ? str(params, "version_uuid") : safe(current.versionUuid);
                in.pageNumber = hasParam(params, "page_number") ? intVal(params, "page_number", current.pageNumber) : current.pageNumber;
                in.sortOrder = hasParam(params, "sort_order") ? intVal(params, "sort_order", current.sortOrder) : current.sortOrder;
                boolean changed = store.updateFact(tenantUuid, matterUuid, in, str(params, "actor_user_uuid"));
                matter_facts.FactRec row = store.getFact(tenantUuid, matterUuid, factUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("fact", factsFactMap(row));
                out.put("report", factsReportMap(store.reportRefs(tenantUuid, matterUuid)));
                return out;
            }

            case "facts.facts.set_trashed": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                String factUuid = str(params, "fact_uuid");
                boolean trashed = boolVal(params, "trashed", true);
                boolean changed = store.setFactTrashed(
                        tenantUuid,
                        matterUuid,
                        factUuid,
                        trashed,
                        str(params, "actor_user_uuid")
                );
                matter_facts.FactRec row = store.getFact(tenantUuid, matterUuid, factUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("fact", factsFactMap(row));
                out.put("report", factsReportMap(store.reportRefs(tenantUuid, matterUuid)));
                return out;
            }

            case "facts.report.refresh": {
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
                matter_facts.ReportRec report = store.refreshMatterReport(
                        tenantUuid,
                        matterUuid,
                        str(params, "actor_user_uuid")
                );
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("report", factsReportMap(report));
                return out;
            }

            default:
                throw new UnsupportedOperationException("Unknown operation: " + op);
        }
    }

    private static LinkedHashMap<String, Object> executeTasksOperation(String operation,
                                                                        Map<String, Object> params,
                                                                        String tenantUuid) throws Exception {
        String op = safe(operation).trim().toLowerCase(Locale.ROOT);
        if (!(op.startsWith("tasks.") || op.startsWith("task."))) return null;

        tasks store = tasks.defaultStore();

        switch (op) {
            case "task.attributes.list": {
                task_attributes attrStore = task_attributes.defaultStore();
                boolean enabledOnly = boolVal(params, "enabled_only", false);
                List<task_attributes.AttributeRec> rows = enabledOnly
                        ? attrStore.listEnabled(tenantUuid)
                        : attrStore.listAll(tenantUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (task_attributes.AttributeRec r : rows) {
                    if (r == null) continue;
                    items.add(taskAttributeMap(r));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "task.attributes.save": {
                task_attributes attrStore = task_attributes.defaultStore();
                List<task_attributes.AttributeRec> rows = parseTaskAttributeRows(params.get("rows"));
                attrStore.saveAll(tenantUuid, rows);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("saved", true);
                out.put("count", attrStore.listAll(tenantUuid).size());
                return out;
            }

            case "task.fields.get": {
                String taskUuid = str(params, "task_uuid");
                if (taskUuid.isBlank()) throw new IllegalArgumentException("task_uuid is required.");
                Map<String, String> rows = task_fields.defaultStore().read(tenantUuid, taskUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("task_uuid", taskUuid);
                out.put("fields", new LinkedHashMap<String, String>(rows));
                out.put("count", rows.size());
                return out;
            }

            case "task.fields.update": {
                String taskUuid = str(params, "task_uuid");
                if (taskUuid.isBlank()) throw new IllegalArgumentException("task_uuid is required.");
                if (store.getTask(tenantUuid, taskUuid) == null) throw new IllegalArgumentException("Task not found.");

                task_fields fieldStore = task_fields.defaultStore();
                boolean replace = boolVal(params, "replace", false);
                LinkedHashMap<String, String> incoming = stringMap(params.get("fields"));
                LinkedHashMap<String, String> next = replace
                        ? new LinkedHashMap<String, String>()
                        : new LinkedHashMap<String, String>(fieldStore.read(tenantUuid, taskUuid));
                next.putAll(incoming);
                fieldStore.write(tenantUuid, taskUuid, next);

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("saved", true);
                out.put("task_uuid", taskUuid);
                out.put("fields", fieldStore.read(tenantUuid, taskUuid));
                return out;
            }

            case "tasks.list": {
                boolean includeArchived = boolVal(params, "include_archived", false);
                String matterFilter = str(params, "matter_uuid");
                String statusFilter = str(params, "status");
                String assignedFilter = str(params, "assigned_user_uuid");
                String parentFilter = str(params, "parent_task_uuid");
                String threadFilter = str(params, "thread_uuid");
                String factFilter = str(params, "fact_uuid");
                String elementFilter = str(params, "element_uuid");
                String q = str(params, "q").toLowerCase(Locale.ROOT);

                List<tasks.TaskRec> rows = store.listTasks(tenantUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (tasks.TaskRec r : rows) {
                    if (r == null) continue;
                    if (!includeArchived && r.archived) continue;
                    if (!matterFilter.isBlank() && !matterFilter.equalsIgnoreCase(safe(r.matterUuid).trim())) continue;
                    if (!statusFilter.isBlank() && !statusFilter.equalsIgnoreCase(safe(r.status).trim())) continue;
                    if (!assignedFilter.isBlank() && !csvContains(safe(r.assignedUserUuid), assignedFilter)) continue;
                    if (!parentFilter.isBlank() && !parentFilter.equalsIgnoreCase(safe(r.parentTaskUuid).trim())) continue;
                    if (!threadFilter.isBlank() && !threadFilter.equalsIgnoreCase(safe(r.threadUuid).trim())) continue;
                    if (!factFilter.isBlank() && !factFilter.equalsIgnoreCase(safe(r.factUuid).trim())) continue;
                    if (!elementFilter.isBlank() && !elementFilter.equalsIgnoreCase(safe(r.elementUuid).trim())) continue;
                    if (!q.isBlank()) {
                        String hay = (safe(r.title) + " " + safe(r.description)).toLowerCase(Locale.ROOT);
                        if (!hay.contains(q)) continue;
                    }
                    items.add(taskMap(r));
                }

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "tasks.get": {
                String taskUuid = str(params, "task_uuid");
                if (taskUuid.isBlank()) throw new IllegalArgumentException("task_uuid is required.");

                tasks.TaskRec row = store.getTask(tenantUuid, taskUuid);
                if (row == null) throw new IllegalArgumentException("Task not found.");

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("task", taskMap(row));

                if (boolVal(params, "include_details", true)) {
                    ArrayList<LinkedHashMap<String, Object>> notes = new ArrayList<LinkedHashMap<String, Object>>();
                    for (tasks.NoteRec n : store.listNotes(tenantUuid, taskUuid)) {
                        if (n == null) continue;
                        notes.add(taskNoteMap(n));
                    }
                    ArrayList<LinkedHashMap<String, Object>> assignments = new ArrayList<LinkedHashMap<String, Object>>();
                    for (tasks.AssignmentRec a : store.listAssignments(tenantUuid, taskUuid)) {
                        if (a == null) continue;
                        assignments.add(taskAssignmentMap(a));
                    }
                    LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(task_fields.defaultStore().read(tenantUuid, taskUuid));

                    out.put("notes", notes);
                    out.put("assignments", assignments);
                    out.put("fields", fields);
                }
                return out;
            }

            case "tasks.create": {
                String assignmentMode = str(params, "assignment_mode");
                String assignedUserUuid = str(params, "assigned_user_uuid");
                if (assignedUserUuid.isBlank()) assignedUserUuid = str(params, "assigned_user_uuids");
                if ("round_robin".equalsIgnoreCase(assignmentMode) && assignedUserUuid.isBlank()) {
                    List<String> candidates = csvOrList(params.get("candidate_user_uuids"));
                    if (candidates.isEmpty()) candidates = csvOrList(params.get("round_robin_candidates"));
                    if (!candidates.isEmpty()) {
                        String queueKey = str(params, "round_robin_queue_key");
                        if (queueKey.isBlank()) {
                            queueKey = safe(str(params, "matter_uuid")) + "|" + safe(str(params, "thread_uuid"));
                        }
                        assignedUserUuid = store.chooseRoundRobinAssignee(tenantUuid, queueKey, candidates);
                    }
                }

                tasks.TaskRec in = new tasks.TaskRec();
                in.matterUuid = str(params, "matter_uuid");
                in.parentTaskUuid = str(params, "parent_task_uuid");
                in.title = str(params, "title");
                in.description = str(params, "description");
                in.status = str(params, "status");
                in.priority = str(params, "priority");
                in.assignmentMode = assignmentMode;
                in.assignedUserUuid = assignedUserUuid;
                in.dueAt = str(params, "due_at");
                in.reminderAt = str(params, "reminder_at");
                in.estimateMinutes = intVal(params, "estimate_minutes", 0);

                in.claimUuid = str(params, "claim_uuid");
                in.elementUuid = str(params, "element_uuid");
                in.factUuid = str(params, "fact_uuid");
                in.documentUuid = str(params, "document_uuid");
                in.partUuid = str(params, "part_uuid");
                in.versionUuid = str(params, "version_uuid");
                in.pageNumber = intVal(params, "page_number", 0);
                in.threadUuid = str(params, "thread_uuid");

                tasks.TaskRec row = store.createTask(tenantUuid, in, str(params, "actor_user_uuid"), str(params, "assignment_reason"));

                LinkedHashMap<String, String> fieldValues = stringMap(params.get("fields"));
                if (!fieldValues.isEmpty() && row != null) {
                    task_fields.defaultStore().write(tenantUuid, row.uuid, fieldValues);
                }

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("task", taskMap(row));
                out.put("fields", row == null ? Map.of() : task_fields.defaultStore().read(tenantUuid, row.uuid));
                return out;
            }

            case "tasks.update": {
                String taskUuid = str(params, "task_uuid");
                if (taskUuid.isBlank()) throw new IllegalArgumentException("task_uuid is required.");
                tasks.TaskRec current = store.getTask(tenantUuid, taskUuid);
                if (current == null) throw new IllegalArgumentException("Task not found.");

                tasks.TaskRec update = copyTask(current);
                update.uuid = taskUuid;

                String assignmentMode = update.assignmentMode;
                String assignedUserUuid = update.assignedUserUuid;
                if (hasParam(params, "assignment_mode")) assignmentMode = str(params, "assignment_mode");
                if (hasParam(params, "assigned_user_uuid")) assignedUserUuid = str(params, "assigned_user_uuid");
                if (hasParam(params, "assigned_user_uuids")) assignedUserUuid = str(params, "assigned_user_uuids");
                if ("round_robin".equalsIgnoreCase(assignmentMode) && safe(assignedUserUuid).trim().isBlank()) {
                    List<String> candidates = csvOrList(params.get("candidate_user_uuids"));
                    if (candidates.isEmpty()) candidates = csvOrList(params.get("round_robin_candidates"));
                    if (!candidates.isEmpty()) {
                        String queueKey = str(params, "round_robin_queue_key");
                        if (queueKey.isBlank()) {
                            String queueMatter = hasParam(params, "matter_uuid") ? str(params, "matter_uuid") : safe(current.matterUuid);
                            String queueThread = hasParam(params, "thread_uuid") ? str(params, "thread_uuid") : safe(current.threadUuid);
                            queueKey = queueMatter + "|" + queueThread;
                        }
                        assignedUserUuid = store.chooseRoundRobinAssignee(tenantUuid, queueKey, candidates);
                    }
                }

                if (hasParam(params, "matter_uuid")) update.matterUuid = str(params, "matter_uuid");
                if (hasParam(params, "parent_task_uuid")) update.parentTaskUuid = str(params, "parent_task_uuid");
                if (hasParam(params, "title")) update.title = str(params, "title");
                if (hasParam(params, "description")) update.description = str(params, "description");
                if (hasParam(params, "status")) update.status = str(params, "status");
                if (hasParam(params, "priority")) update.priority = str(params, "priority");
                update.assignmentMode = assignmentMode;
                update.assignedUserUuid = assignedUserUuid;
                if (hasParam(params, "due_at")) update.dueAt = str(params, "due_at");
                if (hasParam(params, "reminder_at")) update.reminderAt = str(params, "reminder_at");
                if (hasParam(params, "estimate_minutes")) update.estimateMinutes = intVal(params, "estimate_minutes", update.estimateMinutes);

                if (hasParam(params, "claim_uuid")) update.claimUuid = str(params, "claim_uuid");
                if (hasParam(params, "element_uuid")) update.elementUuid = str(params, "element_uuid");
                if (hasParam(params, "fact_uuid")) update.factUuid = str(params, "fact_uuid");
                if (hasParam(params, "document_uuid")) update.documentUuid = str(params, "document_uuid");
                if (hasParam(params, "part_uuid")) update.partUuid = str(params, "part_uuid");
                if (hasParam(params, "version_uuid")) update.versionUuid = str(params, "version_uuid");
                if (hasParam(params, "page_number")) update.pageNumber = intVal(params, "page_number", update.pageNumber);
                if (hasParam(params, "thread_uuid")) update.threadUuid = str(params, "thread_uuid");
                if (hasParam(params, "archived")) update.archived = boolVal(params, "archived", update.archived);

                boolean changed = store.updateTask(tenantUuid, update, str(params, "actor_user_uuid"), str(params, "assignment_reason"));
                tasks.TaskRec refreshed = store.getTask(tenantUuid, taskUuid);

                if (hasParam(params, "fields")) {
                    task_fields fieldStore = task_fields.defaultStore();
                    boolean replace = boolVal(params, "replace_fields", false);
                    LinkedHashMap<String, String> incoming = stringMap(params.get("fields"));
                    LinkedHashMap<String, String> next = replace
                            ? new LinkedHashMap<String, String>()
                            : new LinkedHashMap<String, String>(fieldStore.read(tenantUuid, taskUuid));
                    next.putAll(incoming);
                    fieldStore.write(tenantUuid, taskUuid, next);
                }

                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("task", taskMap(refreshed));
                out.put("fields", task_fields.defaultStore().read(tenantUuid, taskUuid));
                return out;
            }

            case "tasks.set_archived": {
                String taskUuid = str(params, "task_uuid");
                if (taskUuid.isBlank()) throw new IllegalArgumentException("task_uuid is required.");
                boolean archived = boolVal(params, "archived", true);
                boolean changed = store.setArchived(tenantUuid, taskUuid, archived, str(params, "actor_user_uuid"));
                tasks.TaskRec refreshed = store.getTask(tenantUuid, taskUuid);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("updated", changed);
                out.put("task", taskMap(refreshed));
                return out;
            }

            case "tasks.notes.list": {
                String taskUuid = str(params, "task_uuid");
                if (taskUuid.isBlank()) throw new IllegalArgumentException("task_uuid is required.");
                List<tasks.NoteRec> rows = store.listNotes(tenantUuid, taskUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (tasks.NoteRec row : rows) {
                    if (row == null) continue;
                    items.add(taskNoteMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "tasks.notes.add": {
                String taskUuid = str(params, "task_uuid");
                if (taskUuid.isBlank()) throw new IllegalArgumentException("task_uuid is required.");
                tasks.NoteRec row = store.addNote(tenantUuid, taskUuid, str(params, "body"), str(params, "actor_user_uuid"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("note", taskNoteMap(row));
                return out;
            }

            case "tasks.assignments.list": {
                String taskUuid = str(params, "task_uuid");
                if (taskUuid.isBlank()) throw new IllegalArgumentException("task_uuid is required.");
                List<tasks.AssignmentRec> rows = store.listAssignments(tenantUuid, taskUuid);
                ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
                for (tasks.AssignmentRec row : rows) {
                    if (row == null) continue;
                    items.add(taskAssignmentMap(row));
                }
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("items", items);
                out.put("count", items.size());
                return out;
            }

            case "tasks.round_robin.next_assignee": {
                String queueKey = str(params, "queue_key");
                List<String> candidates = csvOrList(params.get("candidate_user_uuids"));
                if (candidates.isEmpty()) candidates = csvOrList(params.get("candidates"));
                if (candidates.isEmpty()) throw new IllegalArgumentException("candidate_user_uuids are required.");
                String assignee = store.chooseRoundRobinAssignee(tenantUuid, queueKey, candidates);
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("assigned_user_uuid", assignee);
                return out;
            }

            case "tasks.report.refresh": {
                String taskUuid = str(params, "task_uuid");
                if (taskUuid.isBlank()) throw new IllegalArgumentException("task_uuid is required.");
                tasks.TaskRec row = store.refreshTaskReport(tenantUuid, taskUuid, str(params, "actor_user_uuid"));
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("task", taskMap(row));
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

    private static List<task_attributes.AttributeRec> parseTaskAttributeRows(Object raw) {
        ArrayList<task_attributes.AttributeRec> out = new ArrayList<task_attributes.AttributeRec>();
        List<Map<String, Object>> rows = objectList(raw);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            if (r == null) continue;
            out.add(new task_attributes.AttributeRec(
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
            row.put("required_permissions", requiredPermissionKeys(e.getKey()));
            items.add(row);
        }
        return items;
    }

    private static LinkedHashMap<String, String> capabilityMap() {
        LinkedHashMap<String, String> ops = new LinkedHashMap<String, String>();

        ops.put("auth.whoami", "Credential identity and tenant scope");
        ops.put("activity.recent", "Recent tenant activity log events");
        ops.put("permissions.catalog", "List permission definitions and built-in profiles");

        ops.put("api.credentials.list", "List API credentials for tenant");
        ops.put("api.credentials.get", "Get one API credential");
        ops.put("api.credentials.create", "Create API credential (returns key + one-time secret)");
        ops.put("api.credentials.update", "Update API credential label/scope");
        ops.put("api.credentials.rotate_secret", "Rotate API credential secret (returns one-time secret)");
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
        ops.put("contacts.list", "List contacts");
        ops.put("contacts.get", "Get one contact");
        ops.put("contacts.create", "Create native contact");
        ops.put("contacts.update", "Update native contact");
        ops.put("contacts.trash", "Archive contact");
        ops.put("contacts.restore", "Restore archived contact");

        ops.put("facts.tree.get", "Get full Claims->Elements->Facts hierarchy for matter");
        ops.put("facts.claims.list", "List claims for matter");
        ops.put("facts.claims.get", "Get one claim");
        ops.put("facts.claims.create", "Create claim (auto-refresh facts report)");
        ops.put("facts.claims.update", "Update claim (auto-refresh facts report)");
        ops.put("facts.claims.set_trashed", "Archive/restore claim and descendants");
        ops.put("facts.elements.list", "List elements for matter/claim");
        ops.put("facts.elements.get", "Get one element");
        ops.put("facts.elements.create", "Create element (auto-refresh facts report)");
        ops.put("facts.elements.update", "Update element (auto-refresh facts report)");
        ops.put("facts.elements.set_trashed", "Archive/restore element and descendant facts");
        ops.put("facts.facts.list", "List facts for matter/claim/element");
        ops.put("facts.facts.get", "Get one fact");
        ops.put("facts.facts.create", "Create fact with optional document/part/version/page link");
        ops.put("facts.facts.update", "Update fact fields/link (auto-refresh facts report)");
        ops.put("facts.facts.link_document", "Update only fact document linkage fields");
        ops.put("facts.facts.set_trashed", "Archive/restore fact");
        ops.put("facts.report.refresh", "Regenerate landscape facts case-plan PDF report");

        ops.put("task.attributes.list", "List task attribute definitions");
        ops.put("task.attributes.save", "Save task attribute definitions");
        ops.put("task.fields.get", "Read task custom field values");
        ops.put("task.fields.update", "Update task custom field values");
        ops.put("tasks.list", "List tasks");
        ops.put("tasks.get", "Get one task with optional notes/assignments/fields");
        ops.put("tasks.create", "Create task");
        ops.put("tasks.update", "Update task");
        ops.put("tasks.set_archived", "Archive/restore task");
        ops.put("tasks.notes.list", "List task internal notes");
        ops.put("tasks.notes.add", "Add task internal note");
        ops.put("tasks.assignments.list", "List task assignment history");
        ops.put("tasks.round_robin.next_assignee", "Choose next assignee from task round-robin queue");
        ops.put("tasks.report.refresh", "Regenerate matter-linked task PDF report");

        ops.put("leads.list", "List intake/CRM leads");
        ops.put("leads.get", "Get one lead");
        ops.put("leads.create", "Create lead intake/CRM record");
        ops.put("leads.update", "Update lead intake/CRM record");
        ops.put("leads.set_archived", "Archive/restore lead");
        ops.put("leads.notes.list", "List lead notes/history entries");
        ops.put("leads.notes.add", "Append lead note/history entry");
        ops.put("leads.convert_to_matter", "Convert lead to retained matter");

        ops.put("billing.overview", "Billing/trust overview across matters");
        ops.put("billing.time_entries.list", "List billing time entries for matter");
        ops.put("billing.time_entries.create", "Create billing time entry");
        ops.put("billing.expense_entries.list", "List billing expense entries for matter");
        ops.put("billing.expense_entries.create", "Create billing expense entry");
        ops.put("invoices.list", "List invoices for matter");
        ops.put("invoices.get", "Get invoice with optional payment/trust details");
        ops.put("invoices.draft", "Draft invoice from unbilled time/expenses");
        ops.put("invoices.finalize", "Finalize drafted invoice");
        ops.put("invoices.link_external", "Link invoice to external bill identifier");
        ops.put("payments.list", "List ledger payments by matter or invoice");
        ops.put("payments.record", "Record operating payment against invoice");
        ops.put("trust.deposits.record", "Record trust deposit");
        ops.put("trust.apply.record", "Apply trust funds to invoice");
        ops.put("trust.refunds.record", "Record trust refund");
        ops.put("trust.transactions.list", "List trust transactions by matter or invoice");
        ops.put("reconciliation.snapshot", "Generate trust three-way reconciliation snapshot");
        ops.put("reconciliation.compliance", "Generate trust compliance snapshot");
        ops.put("payment.processors.list", "List configured payment processor options");
        ops.put("payment.checkout.create", "Create payment checkout transaction");
        ops.put("payment.transactions.list", "List online payment transactions");
        ops.put("payment.transactions.get", "Get one online payment transaction");
        ops.put("payment.transactions.mark_paid", "Mark online payment transaction as paid and post to ledger");
        ops.put("payment.transactions.mark_failed", "Mark online payment transaction as failed");
        ops.put("payment.transactions.cancel", "Cancel online payment transaction");

        ops.put("esign.providers.list", "List e-sign provider options");
        ops.put("esign.requests.list", "List signature requests");
        ops.put("esign.requests.get", "Get one signature request");
        ops.put("esign.requests.create", "Create signature request");
        ops.put("esign.requests.update_status", "Update signature request lifecycle status");
        ops.put("esign.requests.events.list", "List signature request lifecycle events");

        ops.put("integrations.webhooks.list", "List webhook integration endpoints");
        ops.put("integrations.webhooks.get", "Get webhook integration endpoint");
        ops.put("integrations.webhooks.create", "Create webhook integration endpoint");
        ops.put("integrations.webhooks.update", "Update webhook integration endpoint");
        ops.put("integrations.webhooks.delete", "Delete webhook integration endpoint");
        ops.put("integrations.webhooks.deliveries.list", "List webhook delivery attempts");
        ops.put("integrations.events.emit", "Emit integration event to webhook subscribers");

        ops.put("analytics.kpis.summary", "Firm-level KPI summary");
        ops.put("analytics.kpis.daily", "Daily KPI trend series");

        ops.put("mail.items.list", "List inbound/outbound postal mail items");
        ops.put("mail.items.get", "Get one postal mail item with recipients/parts/tracking");
        ops.put("mail.items.create", "Create postal mail intake/outbound record (supports custom carriers/APIs)");
        ops.put("mail.items.update", "Update postal mail metadata/provider fields (supports custom carriers/APIs)");
        ops.put("mail.items.set_archived", "Archive/restore a postal mail item");
        ops.put("mail.items.mark_reviewed", "Mark inbound mail as reviewed");
        ops.put("mail.items.link_document", "Link reviewed mail to filed case document references");
        ops.put("mail.parts.list", "List mail parts (envelope, letter, receipt, proof, tracking)");
        ops.put("mail.parts.add", "Add a file/reference part to a mail item");
        ops.put("mail.parts.set_trashed", "Trash/restore a mail part");
        ops.put("mail.recipients.list", "List outbound recipients for a mail item");
        ops.put("mail.recipients.add", "Add one recipient (manual or contact-derived)");
        ops.put("mail.recipients.replace", "Replace all recipients for a mail item");
        ops.put("mail.addresses.validate", "Validate/normalize recipient address payload");
        ops.put("mail.tracking.validate", "Validate tracking number format (includes custom carrier fallback)");
        ops.put("mail.tracking.list", "List tracking events for a mail item");
        ops.put("mail.tracking.add_event", "Append tracking event and update summary");
        ops.put("mail.tracking.update_summary", "Update mail tracking carrier/number/status summary");

        ops.put("case.attributes.list", "List case attribute definitions");
        ops.put("case.attributes.save", "Save case attribute definitions");
        ops.put("case.fields.get", "Read case fields");
        ops.put("case.fields.update", "Update case fields");
        ops.put("case.list_items.get", "Read case list/grid XML datasets");
        ops.put("case.list_items.update", "Update case list/grid XML datasets");
        ops.put("conflicts.list", "List conflicts.xml entries for a case");
        ops.put("conflicts.get", "Get one conflicts.xml entry");
        ops.put("conflicts.upsert", "Create/update one conflicts.xml entry");
        ops.put("conflicts.delete", "Delete one conflicts.xml entry");
        ops.put("conflicts.scan", "Scan case versions + linked contacts into conflicts.xml");

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
        ops.put("document.versions.render_page", "Render source PDF/word-processor version page as PNG base64 + text + navigation");
        ops.put("document.versions.find_similar", "Find similar/duplicate document versions using tracked page/photo image hashes");
        ops.put("document.versions.redact", "Redact source PDF version and create new redacted version");
        ops.put("search.types", "List configured search types and operators");
        ops.put("search.jobs.enqueue", "Queue asynchronous search job");
        ops.put("search.jobs.list", "List asynchronous search jobs for requester");
        ops.put("search.jobs.get", "Get asynchronous search job details");
        ops.put("search.jobs.status", "Alias for search.jobs.get");

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
        ops.put("custom_objects.create", "Create custom object definition");
        ops.put("custom_objects.update", "Update custom object definition");
        ops.put("custom_objects.delete", "Delete custom object definition");
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
        ops.put("bpm.webhooks.receive", "Receive webhook payload and trigger business process runs");
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
        ops.put("texas_law.render_page", "Render PDF/word-processor page as PNG base64 + text + navigation");

        return ops;
    }

    private static void requireOperationPermission(String operation, String credentialScope) {
        List<String> required = requiredPermissionKeys(operation);
        if (required == null || required.isEmpty()) return;

        CredentialScope resolved = resolveCredentialScopePermissions(credentialScope);
        if (resolved.fullAccess) return;
        if (resolved.permissionKeys.contains("tenant_admin")) return;

        for (String key : required) {
            String k = safe(key).trim().toLowerCase(Locale.ROOT);
            if (!k.isBlank() && resolved.permissionKeys.contains(k)) return;
        }

        throw new SecurityException(
                "Credential scope does not allow operation '" + safe(operation).trim().toLowerCase(Locale.ROOT)
                        + "'. Required permission: " + String.join(" or ", required)
        );
    }

    private static boolean credentialHasPermission(String credentialScope, String permissionKey) {
        String key = safe(permissionKey).trim().toLowerCase(Locale.ROOT);
        if (key.isBlank()) return true;
        CredentialScope resolved = resolveCredentialScopePermissions(credentialScope);
        if (resolved.fullAccess) return true;
        if (resolved.permissionKeys.contains("tenant_admin")) return true;
        return resolved.permissionKeys.contains(key);
    }

    private static void requireCredentialPermission(String credentialScope,
                                                    String permissionKey,
                                                    String operation) {
        String key = safe(permissionKey).trim().toLowerCase(Locale.ROOT);
        if (credentialHasPermission(credentialScope, key)) return;
        throw new SecurityException(
                "Credential scope does not allow operation '" + safe(operation).trim().toLowerCase(Locale.ROOT)
                        + "'. Required permission: " + key
        );
    }

    private static String defaultSearchRequestedBy(String credentialId, String credentialLabel) {
        String requestedBy = safe(credentialId).trim();
        if (requestedBy.isBlank()) requestedBy = safe(credentialLabel).trim();
        if (requestedBy.isBlank()) requestedBy = "api_client";
        return requestedBy;
    }

    private static String resolveSearchRequestedBy(Map<String, Object> params,
                                                   String credentialId,
                                                   String credentialLabel,
                                                   String credentialScope,
                                                   String operation) {
        String fallback = defaultSearchRequestedBy(credentialId, credentialLabel);
        String requestedBy = str(params, "requested_by");
        if (requestedBy.isBlank()) return fallback;
        if (requestedBy.equalsIgnoreCase(fallback)) return fallback;

        String label = safe(credentialLabel).trim();
        if (!label.isBlank() && requestedBy.equalsIgnoreCase(label)) {
            return fallback;
        }

        requireCredentialPermission(credentialScope, "security.manage", operation);
        return requestedBy;
    }

    private static boolean searchJobVisibleToCredential(search_jobs_service.SearchJobSnapshot row,
                                                        String credentialScope) {
        if (row == null) return false;
        search_jobs_service.SearchTypeInfo type = search_jobs_service.defaultService().getSearchType(row.searchType);
        if (type == null) return credentialHasPermission(credentialScope, "security.manage");
        return credentialHasPermission(credentialScope, safe(type.permissionKey));
    }

    private static void requireSearchJobVisibleToCredential(search_jobs_service.SearchJobSnapshot row,
                                                            String credentialScope,
                                                            String operation) {
        if (row == null) throw new IllegalArgumentException("Search job not found.");
        search_jobs_service.SearchTypeInfo type = search_jobs_service.defaultService().getSearchType(row.searchType);
        String permissionKey = safe(type == null ? "" : type.permissionKey).trim();
        if (!permissionKey.isBlank()) {
            requireCredentialPermission(credentialScope, permissionKey, operation);
            return;
        }
        if (!credentialHasPermission(credentialScope, "security.manage")) {
            throw new SecurityException(
                    "Credential scope does not allow operation '" + safe(operation).trim().toLowerCase(Locale.ROOT)
                            + "'."
            );
        }
    }

    private static List<String> requiredPermissionKeys(String operation) {
        String op = safe(operation).trim().toLowerCase(Locale.ROOT);
        if (op.isBlank()) return List.of();

        if ("auth.whoami".equals(op)) return List.of();
        if ("activity.recent".equals(op)) return List.of("logs.view");
        if ("permissions.catalog".equals(op)) return List.of("permissions.manage");

        if (op.startsWith("api.credentials.")) return List.of("api.credentials.manage");

        if (op.startsWith("tenant.settings.")) return List.of("tenant_settings.manage");
        if (op.startsWith("tenant.fields.")) return List.of("tenant_fields.manage");

        if (op.startsWith("roles.") || op.startsWith("users.")) return List.of("security.manage");

        if (op.startsWith("matters.")) return List.of("cases.access");
        if (op.startsWith("contacts.")) return List.of("contacts.access");

        if (op.startsWith("facts.")) return List.of("facts.access");

        if (op.startsWith("task.attributes.")) return List.of("attributes.manage");
        if (op.startsWith("task.fields.") || op.startsWith("tasks.")) return List.of("tasks.access");
        if (op.startsWith("leads.")) return List.of("cases.access");

        if (op.startsWith("billing.")
                || op.startsWith("invoices.")
                || op.startsWith("payments.")
                || op.startsWith("payment.")
                || op.startsWith("trust.")
                || op.startsWith("reconciliation.")) return List.of("cases.access");

        if (op.startsWith("esign.")) return List.of("forms.access", "cases.access");
        if (op.startsWith("integrations.webhooks.") || "integrations.events.emit".equals(op)) {
            return List.of("integrations.manage");
        }
        if (op.startsWith("analytics.")) return List.of("logs.view", "cases.access");

        if (op.startsWith("mail.")) return List.of("mail.access");

        if (op.startsWith("case.attributes.")) return List.of("attributes.manage");
        if (op.startsWith("case.fields.") || op.startsWith("case.list_items.")) return List.of("case_fields.access");
        if ("conflicts.list".equals(op) || "conflicts.get".equals(op)) return List.of("conflicts.access");
        if ("conflicts.scan".equals(op) || "conflicts.upsert".equals(op) || "conflicts.delete".equals(op)) {
            return List.of("conflicts.manage");
        }

        if (op.startsWith("document.taxonomy.")) return List.of("tenant_settings.manage");
        if (op.startsWith("document.attributes.")) return List.of("attributes.manage");
        if (op.startsWith("documents.")
                || op.startsWith("document.fields.")
                || op.startsWith("document.parts.")
                || op.startsWith("document.versions.")) return List.of("documents.access");
        if (op.startsWith("search.")) return List.of("documents.access", "conflicts.access");

        if (op.startsWith("templates.")
                || op.startsWith("template.tools.")
                || op.startsWith("assembler.")
                || op.startsWith("assembly.")
                || op.startsWith("assembled_forms.")) return List.of("forms.access");

        if (op.startsWith("custom_objects.")) return List.of("custom_objects.manage");
        if (op.startsWith("custom_object_attributes.")) return List.of("attributes.manage");
        if ("custom_object_records.list".equals(op) || "custom_object_records.get".equals(op)) {
            return List.of("custom_objects.records.access");
        }
        if ("custom_object_records.create".equals(op)) return List.of("custom_objects.records.create");
        if ("custom_object_records.update".equals(op)) return List.of("custom_objects.records.edit");
        if ("custom_object_records.set_trashed".equals(op)) return List.of("custom_objects.records.archive");

        if (op.startsWith("omnichannel.")) return List.of("threads.access");

        if (op.startsWith("bpm.reviews.")) return List.of("business_process_reviews.manage");
        if (op.startsWith("bpm.")) return List.of("business_processes.manage");

        if (op.startsWith("texas_law.")) return List.of("texas_law.access");

        return List.of();
    }

    private static final class CredentialScope {
        boolean fullAccess = false;
        final LinkedHashSet<String> permissionKeys = new LinkedHashSet<String>();
    }

    private static CredentialScope resolveCredentialScopePermissions(String scopeLiteral) {
        CredentialScope out = new CredentialScope();
        String normalized = normalizeCredentialScope(scopeLiteral);
        if ("full_access".equals(normalized)) {
            out.fullAccess = true;
            return out;
        }

        if (normalized.startsWith("profile:")) {
            String profile = safe(normalized.substring("profile:".length())).trim();
            LinkedHashMap<String, String> perms = permission_layers.defaultStore().profilePermissions(profile);
            for (Map.Entry<String, String> e : perms.entrySet()) {
                if (e == null) continue;
                String k = safe(e.getKey()).trim().toLowerCase(Locale.ROOT);
                if (k.isBlank()) continue;
                if ("true".equalsIgnoreCase(safe(e.getValue()).trim())) {
                    out.permissionKeys.add(k);
                }
            }
            return out;
        }

        String payload = normalized;
        if (normalized.startsWith("permissions:")) {
            payload = normalized.substring("permissions:".length());
        }

        LinkedHashSet<String> keys = parseScopePermissionKeys(payload);
        if (keys.contains("full_access")) {
            out.fullAccess = true;
            return out;
        }
        out.permissionKeys.addAll(keys);
        return out;
    }

    private static String normalizeCredentialScope(String scopeLiteral) {
        String raw = safe(scopeLiteral).trim();
        if (raw.isBlank()) return "full_access";

        String lower = raw.toLowerCase(Locale.ROOT);
        if ("full_access".equals(lower) || "full".equals(lower) || "all".equals(lower) || "*".equals(lower)) {
            return "full_access";
        }

        if (lower.startsWith("profile:")) {
            String profile = safe(raw.substring(raw.indexOf(':') + 1)).trim().toLowerCase(Locale.ROOT);
            if (profile.isBlank()) return "full_access";
            return "profile:" + profile;
        }

        String payload = raw;
        if (lower.startsWith("permissions:")) {
            payload = raw.substring(raw.indexOf(':') + 1);
        }
        LinkedHashSet<String> keys = parseScopePermissionKeys(payload);
        if (keys.isEmpty() || keys.contains("full_access")) return "full_access";
        return "permissions:" + String.join(",", keys);
    }

    private static LinkedHashSet<String> parseScopePermissionKeys(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        String text = safe(raw).trim();
        if (text.isBlank()) return out;

        String[] parts = text.split("[,;|\\s]+");
        for (String part : parts) {
            String k = safe(part).trim().toLowerCase(Locale.ROOT);
            if (k.isBlank()) continue;
            if ("full_access".equals(k) || "full".equals(k) || "all".equals(k) || "*".equals(k)) {
                out.clear();
                out.add("full_access");
                return out;
            }
            out.add(k);
        }
        return out;
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
- Credential scopes are enforced per operation:
  - `full_access`
  - `profile:<profile-key>`
  - `permissions:<comma-separated permission keys>`

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
- Case conflicts XML scan/list/search management
- Facts case plans (Claims->Elements->Facts), source document linkage, landscape report refresh
- Tasks (custom attributes/fields, subtasks, associations, notes, assignments, round-robin, task report refresh)
- Postal mail module (inbound/outbound workflow, recipients, multi-part artifacts, address validation, tracking, custom carriers/APIs)
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

    private static LinkedHashMap<String, Object> credentialMap(api_credentials.CredentialRec r) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        if (r == null) return m;
        m.put("credential_id", r.credentialId);
        m.put("label", r.label);
        m.put("scope", r.scope);
        m.put("created_at", r.createdAt);
        m.put("created_by_user_uuid", r.createdByUserUuid);
        m.put("last_used_at", r.lastUsedAt);
        m.put("last_used_from_ip", r.lastUsedFromIp);
        m.put("revoked", r.revoked);
        return m;
    }

    private static leads_crm.LeadInput leadInputFromMap(Map<String, Object> raw) {
        leads_crm.LeadInput out = new leads_crm.LeadInput();
        if (raw == null) return out;
        out.status = str(raw, "status");
        out.source = str(raw, "source");
        out.intakeChannel = str(raw, "intake_channel");
        out.referredBy = str(raw, "referred_by");
        out.firstName = str(raw, "first_name");
        out.lastName = str(raw, "last_name");
        out.displayName = str(raw, "display_name");
        out.company = str(raw, "company");
        out.email = str(raw, "email");
        out.phone = str(raw, "phone");
        out.notes = str(raw, "notes");
        out.tagsCsv = hasParam(raw, "tags_csv") ? str(raw, "tags_csv") : String.join(",", csvOrList(raw.get("tags")));
        out.assignedUserUuid = str(raw, "assigned_user_uuid");
        out.matterUuid = str(raw, "matter_uuid");
        out.archived = boolVal(raw, "archived", false);
        return out;
    }

    private static LinkedHashMap<String, Object> leadMap(leads_crm.LeadRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("lead_uuid", safe(r.leadUuid));
        out.put("status", safe(r.status));
        out.put("source", safe(r.source));
        out.put("intake_channel", safe(r.intakeChannel));
        out.put("referred_by", safe(r.referredBy));
        out.put("first_name", safe(r.firstName));
        out.put("last_name", safe(r.lastName));
        out.put("display_name", safe(r.displayName));
        out.put("company", safe(r.company));
        out.put("email", safe(r.email));
        out.put("phone", safe(r.phone));
        out.put("notes", safe(r.notes));
        out.put("tags_csv", safe(r.tagsCsv));
        out.put("tags", csvOrList(safe(r.tagsCsv)));
        out.put("assigned_user_uuid", safe(r.assignedUserUuid));
        out.put("matter_uuid", safe(r.matterUuid));
        out.put("archived", r.archived);
        out.put("converted_at", safe(r.convertedAt));
        out.put("created_at", safe(r.createdAt));
        out.put("updated_at", safe(r.updatedAt));
        return out;
    }

    private static LinkedHashMap<String, Object> leadNoteMap(leads_crm.LeadNoteRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("note_uuid", safe(r.noteUuid));
        out.put("lead_uuid", safe(r.leadUuid));
        out.put("body", safe(r.body));
        out.put("author_user_uuid", safe(r.authorUserUuid));
        out.put("created_at", safe(r.createdAt));
        return out;
    }

    private static LinkedHashMap<String, Object> billingTimeEntryMap(billing_accounting.TimeEntryRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("time_entry_uuid", safe(r.uuid));
        out.put("source", safe(r.source));
        out.put("source_id", safe(r.sourceId));
        out.put("matter_uuid", safe(r.matterUuid));
        out.put("user_uuid", safe(r.userUuid));
        out.put("activity_code", safe(r.activityCode));
        out.put("note", safe(r.note));
        out.put("minutes", r.minutes);
        out.put("rate_cents", r.rateCents);
        out.put("currency", safe(r.currency));
        out.put("billable", r.billable);
        out.put("worked_at", safe(r.workedAt));
        out.put("created_at", safe(r.createdAt));
        out.put("updated_at", safe(r.updatedAt));
        out.put("billed_invoice_uuid", safe(r.billedInvoiceUuid));
        return out;
    }

    private static LinkedHashMap<String, Object> billingExpenseEntryMap(billing_accounting.ExpenseEntryRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("expense_entry_uuid", safe(r.uuid));
        out.put("source", safe(r.source));
        out.put("source_id", safe(r.sourceId));
        out.put("matter_uuid", safe(r.matterUuid));
        out.put("description", safe(r.description));
        out.put("amount_cents", r.amountCents);
        out.put("tax_cents", r.taxCents);
        out.put("currency", safe(r.currency));
        out.put("billable", r.billable);
        out.put("incurred_at", safe(r.incurredAt));
        out.put("created_at", safe(r.createdAt));
        out.put("updated_at", safe(r.updatedAt));
        out.put("billed_invoice_uuid", safe(r.billedInvoiceUuid));
        return out;
    }

    private static LinkedHashMap<String, Object> billingInvoiceLineMap(billing_accounting.InvoiceLineRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("line_uuid", safe(r.uuid));
        out.put("kind", safe(r.type));
        out.put("type", safe(r.type));
        out.put("source_uuid", safe(r.sourceUuid));
        out.put("description", safe(r.description));
        out.put("quantity", r.quantity);
        out.put("unit_amount_cents", r.unitAmountCents);
        out.put("tax_cents", r.taxCents);
        out.put("total_cents", r.totalCents);
        return out;
    }

    private static LinkedHashMap<String, Object> billingInvoiceMap(billing_accounting.InvoiceRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("invoice_uuid", safe(r.uuid));
        out.put("source", safe(r.source));
        out.put("source_id", safe(r.sourceId));
        out.put("matter_uuid", safe(r.matterUuid));
        out.put("status", safe(r.status));
        out.put("currency", safe(r.currency));
        out.put("issued_at", safe(r.issuedAt));
        out.put("due_at", safe(r.dueAt));
        out.put("created_at", safe(r.createdAt));
        out.put("updated_at", safe(r.updatedAt));
        out.put("subtotal_cents", r.subtotalCents);
        out.put("tax_cents", r.taxCents);
        out.put("total_cents", r.totalCents);
        out.put("paid_cents", r.paidCents);
        out.put("outstanding_cents", r.outstandingCents);
        out.put("source_time_entry_uuids", new ArrayList<String>(r.sourceTimeEntryUuids));
        out.put("source_expense_entry_uuids", new ArrayList<String>(r.sourceExpenseEntryUuids));
        out.put("void_reason", safe(r.voidReason));
        ArrayList<LinkedHashMap<String, Object>> lines = new ArrayList<LinkedHashMap<String, Object>>();
        for (billing_accounting.InvoiceLineRec line : r.lines) {
            if (line == null) continue;
            lines.add(billingInvoiceLineMap(line));
        }
        out.put("lines", lines);
        return out;
    }

    private static LinkedHashMap<String, Object> billingPaymentMap(billing_accounting.PaymentRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("payment_uuid", safe(r.uuid));
        out.put("source", safe(r.source));
        out.put("source_id", safe(r.sourceId));
        out.put("matter_uuid", safe(r.matterUuid));
        out.put("invoice_uuid", safe(r.invoiceUuid));
        out.put("kind", safe(r.kind));
        out.put("amount_cents", r.amountCents);
        out.put("currency", safe(r.currency));
        out.put("posted_at", safe(r.postedAt));
        out.put("reference", safe(r.reference));
        out.put("created_at", safe(r.createdAt));
        return out;
    }

    private static LinkedHashMap<String, Object> billingTrustTxnMap(billing_accounting.TrustTxnRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("trust_txn_uuid", safe(r.uuid));
        out.put("source", safe(r.source));
        out.put("source_id", safe(r.sourceId));
        out.put("matter_uuid", safe(r.matterUuid));
        out.put("invoice_uuid", safe(r.invoiceUuid));
        out.put("kind", safe(r.kind));
        out.put("type", safe(r.kind));
        out.put("amount_cents", r.amountCents);
        out.put("currency", safe(r.currency));
        out.put("posted_at", safe(r.postedAt));
        out.put("reference", safe(r.reference));
        out.put("created_at", safe(r.createdAt));
        return out;
    }

    private static LinkedHashMap<String, Object> billingTrustReconciliationMap(billing_accounting.TrustReconciliationRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("statement_date", safe(r.statementDate));
        out.put("statement_ending_balance_cents", r.statementEndingBalanceCents);
        out.put("book_trust_bank_balance_cents", r.bookTrustBankBalanceCents);
        out.put("client_ledger_total_cents", r.clientLedgerTotalCents);
        out.put("trust_liability_balance_cents", r.trustLiabilityBalanceCents);
        out.put("bank_vs_book_delta_cents", r.bankVsBookDeltaCents);
        out.put("book_vs_client_delta_cents", r.bookVsClientLedgerDeltaCents);
        out.put("book_vs_liability_delta_cents", r.bookVsTrustLiabilityDeltaCents);
        out.put("balanced", r.balanced);
        return out;
    }

    private static LinkedHashMap<String, Object> billingComplianceMap(billing_accounting.ComplianceSnapshot r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("reconciliation", billingTrustReconciliationMap(r.trustReconciliation));
        out.put("violations", new ArrayList<String>(r.violations));
        out.put("violation_count", r.violations == null ? 0 : r.violations.size());
        return out;
    }

    private static online_payments.CheckoutInput checkoutInputFromMap(Map<String, Object> raw) {
        online_payments.CheckoutInput out = new online_payments.CheckoutInput();
        if (raw == null) return out;
        out.invoiceUuid = str(raw, "invoice_uuid");
        out.processorKey = str(raw, "processor_key");
        out.currency = str(raw, "currency");
        out.amountCents = moneyCents(raw, "amount", "amount_cents");
        out.payerName = str(raw, "payer_name");
        out.payerEmail = str(raw, "payer_email");
        out.returnUrl = str(raw, "return_url");
        out.cancelUrl = str(raw, "cancel_url");
        out.metadataJson = str(raw, "metadata_json");
        return out;
    }

    private static LinkedHashMap<String, Object> paymentTransactionMap(online_payments.PaymentTransactionRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("transaction_uuid", safe(r.transactionUuid));
        out.put("processor_key", safe(r.processorKey));
        out.put("status", safe(r.status));
        out.put("invoice_uuid", safe(r.invoiceUuid));
        out.put("matter_uuid", safe(r.matterUuid));
        out.put("currency", safe(r.currency));
        out.put("amount_cents", r.amountCents);
        out.put("checkout_url", safe(r.checkoutUrl));
        out.put("provider_checkout_id", safe(r.providerCheckoutId));
        out.put("provider_payment_id", safe(r.providerPaymentId));
        out.put("payer_name", safe(r.payerName));
        out.put("payer_email", safe(r.payerEmail));
        out.put("reference", safe(r.reference));
        out.put("error_message", safe(r.errorMessage));
        out.put("metadata_json", safe(r.metadataJson));
        out.put("created_at", safe(r.createdAt));
        out.put("updated_at", safe(r.updatedAt));
        out.put("paid_at", safe(r.paidAt));
        out.put("failed_at", safe(r.failedAt));
        return out;
    }

    private static LinkedHashMap<String, Object> esignProviderRow(String key, String label) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("provider_key", safe(key).trim().toLowerCase(Locale.ROOT));
        out.put("label", safe(label).trim());
        return out;
    }

    private static LinkedHashMap<String, Object> signatureRequestMap(esign_requests.SignatureRequestRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("request_uuid", safe(r.requestUuid));
        out.put("provider_key", safe(r.providerKey));
        out.put("provider_request_id", safe(r.providerRequestId));
        out.put("matter_uuid", safe(r.matterUuid));
        out.put("document_uuid", safe(r.documentUuid));
        out.put("part_uuid", safe(r.partUuid));
        out.put("version_uuid", safe(r.versionUuid));
        out.put("subject", safe(r.subject));
        out.put("to", safe(r.toCsv));
        out.put("cc", safe(r.ccCsv));
        out.put("bcc", safe(r.bccCsv));
        out.put("signature_link", safe(r.signatureLink));
        out.put("delivery_mode", safe(r.deliveryMode));
        out.put("status", safe(r.status));
        out.put("requested_by_user_uuid", safe(r.requestedByUserUuid));
        out.put("created_at", safe(r.createdAt));
        out.put("updated_at", safe(r.updatedAt));
        out.put("sent_at", safe(r.sentAt));
        out.put("completed_at", safe(r.completedAt));
        out.put("last_event_at", safe(r.lastEventAt));
        out.put("last_event_type", safe(r.lastEventType));
        out.put("last_event_note", safe(r.lastEventNote));
        return out;
    }

    private static LinkedHashMap<String, Object> signatureEventMap(esign_requests.SignatureEventRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("event_uuid", safe(r.eventUuid));
        out.put("request_uuid", safe(r.requestUuid));
        out.put("event_type", safe(r.eventType));
        out.put("status", safe(r.status));
        out.put("note", safe(r.note));
        out.put("actor_user_uuid", safe(r.actorUserUuid));
        out.put("provider_request_id", safe(r.providerRequestId));
        out.put("created_at", safe(r.createdAt));
        return out;
    }

    private static integration_webhooks.EndpointInput integrationWebhookInputFromMap(Map<String, Object> raw) {
        integration_webhooks.EndpointInput out = new integration_webhooks.EndpointInput();
        if (raw == null) return out;
        out.label = str(raw, "label");
        out.url = str(raw, "url");
        out.eventFilterCsv = hasParam(raw, "event_filter_csv")
                ? str(raw, "event_filter_csv")
                : String.join(",", csvOrList(raw.get("events")));
        out.signingSecret = str(raw, "signing_secret");
        out.enabled = boolVal(raw, "enabled", true);
        return out;
    }

    private static LinkedHashMap<String, Object> webhookEndpointMap(integration_webhooks.EndpointRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("webhook_uuid", safe(r.webhookUuid));
        out.put("label", safe(r.label));
        out.put("url", safe(r.url));
        out.put("event_filter_csv", safe(r.eventFilterCsv));
        out.put("signing_secret_masked", safe(r.signingSecretMasked));
        out.put("enabled", r.enabled);
        out.put("created_at", safe(r.createdAt));
        out.put("updated_at", safe(r.updatedAt));
        out.put("last_attempt_at", safe(r.lastAttemptAt));
        out.put("last_success_at", safe(r.lastSuccessAt));
        out.put("last_error", safe(r.lastError));
        out.put("last_status_code", r.lastStatusCode);
        return out;
    }

    private static LinkedHashMap<String, Object> webhookDeliveryMap(integration_webhooks.DeliveryRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("delivery_uuid", safe(r.deliveryUuid));
        out.put("webhook_uuid", safe(r.webhookUuid));
        out.put("event_type", safe(r.eventType));
        out.put("success", r.success);
        out.put("status_code", r.statusCode);
        out.put("request_body_sha256", safe(r.requestBodySha256));
        out.put("error", safe(r.error));
        out.put("attempted_at", safe(r.attemptedAt));
        return out;
    }

    private static LinkedHashMap<String, Object> analyticsSummaryMap(kpi_analytics.SummaryRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("generated_at", safe(r.generatedAt));
        out.put("matters_total", r.mattersTotal);
        out.put("matters_active", r.mattersActive);
        out.put("leads_total", r.leadsTotal);
        out.put("leads_new", r.leadsNew);
        out.put("leads_qualified", r.leadsQualified);
        out.put("leads_consult_scheduled", r.leadsConsultScheduled);
        out.put("leads_retained", r.leadsRetained);
        out.put("leads_closed_lost", r.leadsClosedLost);
        out.put("lead_conversion_rate", r.leadConversionRate);
        out.put("tasks_total", r.tasksTotal);
        out.put("tasks_open", r.tasksOpen);
        out.put("tasks_completed", r.tasksCompleted);
        out.put("tasks_overdue", r.tasksOverdue);
        out.put("invoices_total", r.invoicesTotal);
        out.put("invoices_issued", r.invoicesIssued);
        out.put("invoices_paid", r.invoicesPaid);
        out.put("invoice_outstanding_cents", r.invoiceOutstandingCents);
        out.put("invoice_paid_cents", r.invoicePaidCents);
        out.put("trust_balance_total_cents", r.trustBalanceTotalCents);
        out.put("payments_received_cents", r.paymentsReceivedCents);
        out.put("payment_transactions_pending", r.paymentTransactionsPending);
        out.put("payment_transactions_paid", r.paymentTransactionsPaid);
        out.put("payment_transactions_failed", r.paymentTransactionsFailed);
        out.put("signatures_total", r.signaturesTotal);
        out.put("signatures_pending", r.signaturesPending);
        out.put("signatures_signed", r.signaturesSigned);
        out.put("signatures_declined", r.signaturesDeclined);
        return out;
    }

    private static LinkedHashMap<String, Object> analyticsDailyMap(kpi_analytics.DailyRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("date", safe(r.date));
        out.put("leads_created", r.leadsCreated);
        out.put("leads_converted", r.leadsConverted);
        out.put("payments_collected_cents", r.paymentsCollectedCents);
        out.put("signatures_completed", r.signaturesCompleted);
        out.put("tasks_completed", r.tasksCompleted);
        return out;
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
        out.put("clio_read_only", matters.isClioManaged(m));
        out.put("jurisdiction_uuid", m.jurisdictionUuid);
        out.put("matter_category_uuid", m.matterCategoryUuid);
        out.put("matter_subcategory_uuid", m.matterSubcategoryUuid);
        out.put("matter_status_uuid", m.matterStatusUuid);
        out.put("matter_substatus_uuid", m.matterSubstatusUuid);
        return out;
    }

    private static contacts.ContactInput contactInputFromParams(Map<String, Object> params) {
        contacts.ContactInput in = new contacts.ContactInput();
        if (params == null) return in;
        in.displayName = str(params, "display_name");
        in.givenName = str(params, "given_name");
        in.middleName = str(params, "middle_name");
        in.surname = str(params, "surname");
        in.companyName = str(params, "company_name");
        in.jobTitle = str(params, "job_title");
        in.emailPrimary = str(params, "email_primary");
        in.emailSecondary = str(params, "email_secondary");
        in.emailTertiary = str(params, "email_tertiary");
        in.businessPhone = str(params, "business_phone");
        in.businessPhone2 = str(params, "business_phone_2");
        in.mobilePhone = str(params, "mobile_phone");
        in.homePhone = str(params, "home_phone");
        in.otherPhone = str(params, "other_phone");
        in.website = str(params, "website");
        in.street = str(params, "street");
        in.city = str(params, "city");
        in.state = str(params, "state");
        in.postalCode = str(params, "postal_code");
        in.country = str(params, "country");
        in.notes = str(params, "notes");
        return in;
    }

    private static LinkedHashMap<String, List<matter_contacts.LinkRec>> linksByContactUuid(List<matter_contacts.LinkRec> links) {
        LinkedHashMap<String, List<matter_contacts.LinkRec>> out = new LinkedHashMap<String, List<matter_contacts.LinkRec>>();
        if (links == null) return out;
        for (matter_contacts.LinkRec link : links) {
            if (link == null) continue;
            String contactUuid = safe(link.contactUuid).trim();
            if (contactUuid.isBlank()) continue;
            out.computeIfAbsent(contactUuid, k -> new ArrayList<matter_contacts.LinkRec>()).add(link);
        }
        return out;
    }

    private static LinkedHashMap<String, Object> contactMap(contacts.ContactRec c, List<matter_contacts.LinkRec> links) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (c == null) return out;
        out.put("uuid", c.uuid);
        out.put("enabled", c.enabled);
        out.put("trashed", c.trashed);
        out.put("display_name", safe(c.displayName));
        out.put("given_name", safe(c.givenName));
        out.put("middle_name", safe(c.middleName));
        out.put("surname", safe(c.surname));
        out.put("company_name", safe(c.companyName));
        out.put("job_title", safe(c.jobTitle));
        out.put("email_primary", safe(c.emailPrimary));
        out.put("email_secondary", safe(c.emailSecondary));
        out.put("email_tertiary", safe(c.emailTertiary));
        out.put("business_phone", safe(c.businessPhone));
        out.put("business_phone_2", safe(c.businessPhone2));
        out.put("mobile_phone", safe(c.mobilePhone));
        out.put("home_phone", safe(c.homePhone));
        out.put("other_phone", safe(c.otherPhone));
        out.put("website", safe(c.website));
        out.put("street", safe(c.street));
        out.put("city", safe(c.city));
        out.put("state", safe(c.state));
        out.put("postal_code", safe(c.postalCode));
        out.put("country", safe(c.country));
        out.put("notes", safe(c.notes));
        out.put("source", contacts.sourceType(c));
        out.put("source_display", contacts.sourceDisplayName(c));
        out.put("source_contact_id", safe(c.sourceContactId));
        out.put("clio_updated_at", safe(c.clioUpdatedAt));
        out.put("updated_at", safe(c.updatedAt));
        out.put("external_read_only", contacts.isExternalReadOnly(c));
        out.put("clio_read_only", contacts.isClioLocked(c));

        ArrayList<LinkedHashMap<String, Object>> linkRows = new ArrayList<LinkedHashMap<String, Object>>();
        List<matter_contacts.LinkRec> xs = links == null ? List.of() : links;
        for (matter_contacts.LinkRec link : xs) {
            if (link == null) continue;
            LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("matter_uuid", safe(link.matterUuid));
            row.put("source", safe(link.source));
            row.put("source_matter_id", safe(link.sourceMatterId));
            row.put("source_contact_id", safe(link.sourceContactId));
            row.put("updated_at", safe(link.updatedAt));
            linkRows.add(row);
        }
        out.put("matter_links", linkRows);
        return out;
    }

    private static postal_mail.MailItemRec copyMailItem(postal_mail.MailItemRec in) {
        postal_mail.MailItemRec out = new postal_mail.MailItemRec();
        if (in == null) return out;
        out.uuid = safe(in.uuid);
        out.matterUuid = safe(in.matterUuid);
        out.direction = safe(in.direction);
        out.workflow = safe(in.workflow);
        out.service = safe(in.service);
        out.status = safe(in.status);
        out.subject = safe(in.subject);
        out.notes = safe(in.notes);
        out.sourceEmailAddress = safe(in.sourceEmailAddress);
        out.sourceDocumentUuid = safe(in.sourceDocumentUuid);
        out.sourcePartUuid = safe(in.sourcePartUuid);
        out.sourceVersionUuid = safe(in.sourceVersionUuid);
        out.filedDocumentUuid = safe(in.filedDocumentUuid);
        out.filedPartUuid = safe(in.filedPartUuid);
        out.filedVersionUuid = safe(in.filedVersionUuid);
        out.trackingCarrier = safe(in.trackingCarrier);
        out.trackingNumber = safe(in.trackingNumber);
        out.trackingStatus = safe(in.trackingStatus);
        out.providerReference = safe(in.providerReference);
        out.providerMessage = safe(in.providerMessage);
        out.providerRequestJson = safe(in.providerRequestJson);
        out.providerResponseJson = safe(in.providerResponseJson);
        out.addressValidationStatus = safe(in.addressValidationStatus);
        out.createdBy = safe(in.createdBy);
        out.createdAt = safe(in.createdAt);
        out.updatedAt = safe(in.updatedAt);
        out.receivedAt = safe(in.receivedAt);
        out.sentAt = safe(in.sentAt);
        out.reviewedBy = safe(in.reviewedBy);
        out.reviewedAt = safe(in.reviewedAt);
        out.archived = in.archived;
        return out;
    }

    private static postal_mail.RecipientRec mailRecipientInputFromMap(Map<String, Object> raw, String tenantUuid) throws Exception {
        postal_mail.RecipientRec out = new postal_mail.RecipientRec();
        Map<String, Object> row = raw == null ? new LinkedHashMap<String, Object>() : raw;

        out.uuid = str(row, "recipient_uuid");
        if (out.uuid.isBlank()) out.uuid = str(row, "uuid");
        out.contactUuid = str(row, "contact_uuid");

        if (!out.contactUuid.isBlank()) {
            contacts.ContactRec contact = contacts.defaultStore().getByUuid(tenantUuid, out.contactUuid);
            if (contact == null) throw new IllegalArgumentException("Recipient contact not found: " + out.contactUuid);
            applyMailRecipientContactDefaults(out, contact, intVal(row, "address_slot", 1));
        }

        if (hasParam(row, "display_name")) out.displayName = str(row, "display_name");
        if (hasParam(row, "company_name")) out.companyName = str(row, "company_name");

        if (hasParam(row, "address_line_1")) out.addressLine1 = str(row, "address_line_1");
        else if (hasParam(row, "street")) out.addressLine1 = str(row, "street");
        if (hasParam(row, "address_line_2")) out.addressLine2 = str(row, "address_line_2");
        if (hasParam(row, "city")) out.city = str(row, "city");
        if (hasParam(row, "state")) out.state = str(row, "state");
        if (hasParam(row, "postal_code")) out.postalCode = str(row, "postal_code");
        else if (hasParam(row, "zip")) out.postalCode = str(row, "zip");
        if (hasParam(row, "country")) out.country = str(row, "country");
        if (hasParam(row, "email_address")) out.emailAddress = str(row, "email_address");
        else if (hasParam(row, "email")) out.emailAddress = str(row, "email");
        if (hasParam(row, "phone")) out.phone = str(row, "phone");
        return out;
    }

    private static void applyMailRecipientContactDefaults(postal_mail.RecipientRec recipient,
                                                          contacts.ContactRec contact,
                                                          int addressSlot) {
        if (recipient == null || contact == null) return;
        recipient.displayName = safe(contact.displayName).trim();
        if (recipient.displayName.isBlank()) {
            recipient.displayName = (safe(contact.givenName) + " " + safe(contact.surname)).trim();
        }
        recipient.companyName = safe(contact.companyName).trim();

        int slot = clampInt(addressSlot, 1, 3);
        if (slot == 2) {
            recipient.addressLine1 = safe(contact.streetSecondary).trim();
            recipient.city = safe(contact.citySecondary).trim();
            recipient.state = safe(contact.stateSecondary).trim();
            recipient.postalCode = safe(contact.postalCodeSecondary).trim();
            recipient.country = safe(contact.countrySecondary).trim();
        } else if (slot == 3) {
            recipient.addressLine1 = safe(contact.streetTertiary).trim();
            recipient.city = safe(contact.cityTertiary).trim();
            recipient.state = safe(contact.stateTertiary).trim();
            recipient.postalCode = safe(contact.postalCodeTertiary).trim();
            recipient.country = safe(contact.countryTertiary).trim();
        } else {
            recipient.addressLine1 = safe(contact.street).trim();
            recipient.city = safe(contact.city).trim();
            recipient.state = safe(contact.state).trim();
            recipient.postalCode = safe(contact.postalCode).trim();
            recipient.country = safe(contact.country).trim();
        }

        recipient.emailAddress = firstNonBlank(contact.emailPrimary, contact.emailSecondary, contact.emailTertiary);
        recipient.phone = firstNonBlank(contact.businessPhone, contact.mobilePhone, contact.homePhone, contact.otherPhone, contact.businessPhone2);
    }

    private static postal_mail.AddressInput mailAddressInputFromRecipient(postal_mail.RecipientRec recipient) {
        postal_mail.AddressInput out = new postal_mail.AddressInput();
        if (recipient == null) return out;
        out.displayName = safe(recipient.displayName);
        out.companyName = safe(recipient.companyName);
        out.addressLine1 = safe(recipient.addressLine1);
        out.addressLine2 = safe(recipient.addressLine2);
        out.city = safe(recipient.city);
        out.state = safe(recipient.state);
        out.postalCode = safe(recipient.postalCode);
        out.country = safe(recipient.country);
        out.emailAddress = safe(recipient.emailAddress);
        out.phone = safe(recipient.phone);
        return out;
    }

    private static LinkedHashMap<String, Object> mailItemMap(postal_mail.MailItemRec row) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (row == null) return out;
        out.put("uuid", safe(row.uuid));
        out.put("mail_uuid", safe(row.uuid));
        out.put("matter_uuid", safe(row.matterUuid));
        out.put("direction", safe(row.direction));
        out.put("workflow", safe(row.workflow));
        out.put("service", safe(row.service));
        out.put("status", safe(row.status));
        out.put("subject", safe(row.subject));
        out.put("notes", safe(row.notes));
        out.put("source_email_address", safe(row.sourceEmailAddress));
        out.put("source_document_uuid", safe(row.sourceDocumentUuid));
        out.put("source_part_uuid", safe(row.sourcePartUuid));
        out.put("source_version_uuid", safe(row.sourceVersionUuid));
        out.put("filed_document_uuid", safe(row.filedDocumentUuid));
        out.put("filed_part_uuid", safe(row.filedPartUuid));
        out.put("filed_version_uuid", safe(row.filedVersionUuid));
        out.put("tracking_carrier", safe(row.trackingCarrier));
        out.put("tracking_number", safe(row.trackingNumber));
        out.put("tracking_status", safe(row.trackingStatus));
        out.put("provider_reference", safe(row.providerReference));
        out.put("provider_message", safe(row.providerMessage));
        out.put("provider_request_json", safe(row.providerRequestJson));
        out.put("provider_response_json", safe(row.providerResponseJson));
        out.put("address_validation_status", safe(row.addressValidationStatus));
        out.put("created_by", safe(row.createdBy));
        out.put("created_at", safe(row.createdAt));
        out.put("updated_at", safe(row.updatedAt));
        out.put("received_at", safe(row.receivedAt));
        out.put("sent_at", safe(row.sentAt));
        out.put("reviewed_by", safe(row.reviewedBy));
        out.put("reviewed_at", safe(row.reviewedAt));
        out.put("archived", row.archived);
        return out;
    }

    private static LinkedHashMap<String, Object> mailPartMap(postal_mail.MailPartRec row) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (row == null) return out;
        out.put("uuid", safe(row.uuid));
        out.put("mail_part_uuid", safe(row.uuid));
        out.put("part_uuid", safe(row.partUuid));
        out.put("mail_uuid", safe(row.mailUuid));
        out.put("part_type", safe(row.partType));
        out.put("label", safe(row.label));
        out.put("document_uuid", safe(row.documentUuid));
        out.put("linked_part_uuid", safe(row.partUuid));
        out.put("version_uuid", safe(row.versionUuid));
        out.put("notes", safe(row.notes));
        out.put("created_by", safe(row.createdBy));
        out.put("created_at", safe(row.createdAt));
        out.put("updated_at", safe(row.updatedAt));
        out.put("trashed", row.trashed);
        return out;
    }

    private static LinkedHashMap<String, Object> mailRecipientMap(postal_mail.RecipientRec row) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (row == null) return out;
        out.put("uuid", safe(row.uuid));
        out.put("recipient_uuid", safe(row.uuid));
        out.put("mail_uuid", safe(row.mailUuid));
        out.put("contact_uuid", safe(row.contactUuid));
        out.put("display_name", safe(row.displayName));
        out.put("company_name", safe(row.companyName));
        out.put("address_line_1", safe(row.addressLine1));
        out.put("address_line_2", safe(row.addressLine2));
        out.put("city", safe(row.city));
        out.put("state", safe(row.state));
        out.put("postal_code", safe(row.postalCode));
        out.put("country", safe(row.country));
        out.put("email_address", safe(row.emailAddress));
        out.put("phone", safe(row.phone));
        out.put("validated", row.validated);
        out.put("validation_message", safe(row.validationMessage));
        out.put("created_at", safe(row.createdAt));
        out.put("updated_at", safe(row.updatedAt));
        return out;
    }

    private static LinkedHashMap<String, Object> mailTrackingEventMap(postal_mail.TrackingEventRec row) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (row == null) return out;
        out.put("uuid", safe(row.uuid));
        out.put("event_uuid", safe(row.uuid));
        out.put("mail_uuid", safe(row.mailUuid));
        out.put("carrier", safe(row.carrier));
        out.put("tracking_number", safe(row.trackingNumber));
        out.put("status", safe(row.status));
        out.put("location", safe(row.location));
        out.put("event_at", safe(row.eventAt));
        out.put("notes", safe(row.notes));
        out.put("source", safe(row.source));
        out.put("created_at", safe(row.createdAt));
        return out;
    }

    private static LinkedHashMap<String, Object> mailAddressValidationMap(postal_mail.AddressValidationResult row) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (row == null) return out;
        out.put("valid", row.valid);
        out.put("message", safe(row.message));
        out.put("display_name", safe(row.normalizedDisplayName));
        out.put("company_name", safe(row.normalizedCompanyName));
        out.put("address_line_1", safe(row.normalizedAddressLine1));
        out.put("address_line_2", safe(row.normalizedAddressLine2));
        out.put("city", safe(row.normalizedCity));
        out.put("state", safe(row.normalizedState));
        out.put("postal_code", safe(row.normalizedPostalCode));
        out.put("country", safe(row.normalizedCountry));
        out.put("email_address", safe(row.normalizedEmailAddress));
        out.put("phone", safe(row.normalizedPhone));
        out.put("errors", new ArrayList<String>(row.errors == null ? List.of() : row.errors));
        out.put("warnings", new ArrayList<String>(row.warnings == null ? List.of() : row.warnings));
        return out;
    }

    private static LinkedHashMap<String, Object> mailTrackingValidationMap(postal_mail.TrackingValidationResult row) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (row == null) return out;
        out.put("valid", row.valid);
        out.put("message", safe(row.message));
        out.put("tracking_number", safe(row.normalizedTrackingNumber));
        out.put("carrier_hint", safe(row.carrierHint));
        return out;
    }

    private static matter_conflicts.ConflictEntry conflictEntryFromParams(Map<String, Object> params) {
        matter_conflicts.ConflictEntry out = new matter_conflicts.ConflictEntry();
        if (params == null) return out;
        Map<String, Object> nested = mapFrom(params.get("entry"), params);
        out.uuid = str(nested, "entry_uuid");
        if (out.uuid.isBlank()) out.uuid = str(nested, "uuid");
        out.entityType = str(nested, "entity_type");
        out.displayName = str(nested, "display_name");
        out.normalizedName = str(nested, "normalized_name");
        out.sourceTags = str(nested, "source_tags");
        out.sourceRefs = str(nested, "source_refs");
        out.linkedContactUuids = str(nested, "linked_contact_uuids");
        out.notes = str(nested, "notes");
        out.occurrenceCount = intVal(nested, "occurrence_count", 1);
        out.firstSeenAt = str(nested, "first_seen_at");
        out.lastSeenAt = str(nested, "last_seen_at");
        return out;
    }

    private static LinkedHashMap<String, Object> conflictEntryMap(matter_conflicts.ConflictEntry row) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (row == null) return out;
        out.put("entry_uuid", safe(row.uuid));
        out.put("entity_type", safe(row.entityType));
        out.put("display_name", safe(row.displayName));
        out.put("normalized_name", safe(row.normalizedName));
        out.put("source_tags", safe(row.sourceTags));
        out.put("source_refs", safe(row.sourceRefs));
        out.put("linked_contact_uuids", safe(row.linkedContactUuids));
        out.put("occurrence_count", row.occurrenceCount);
        out.put("first_seen_at", safe(row.firstSeenAt));
        out.put("last_seen_at", safe(row.lastSeenAt));
        out.put("notes", safe(row.notes));
        return out;
    }

    private static LinkedHashMap<String, Object> conflictScanSummaryMap(conflicts_scan_service.ScanSummary row) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (row == null) return out;
        out.put("tenant_uuid", safe(row.tenantUuid));
        out.put("matter_uuid", safe(row.matterUuid));
        out.put("started_at", safe(row.startedAt));
        out.put("completed_at", safe(row.completedAt));
        out.put("versions_total", row.versionsTotal);
        out.put("versions_scanned", row.versionsScanned);
        out.put("versions_skipped", row.versionsSkipped);
        out.put("nlp_entities", row.nlpEntities);
        out.put("linked_contact_entities", row.linkedContactEntities);
        out.put("entries_changed", row.entriesChanged);
        out.put("ocr_warnings", row.ocrWarnings);
        out.put("message", safe(row.message));
        return out;
    }

    private static matters.MatterRec requireMatterExists(String tenantUuid, String matterUuid) throws Exception {
        String mu = safe(matterUuid).trim();
        if (mu.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");
        matters.MatterRec matter = matters.defaultStore().getByUuid(tenantUuid, mu);
        if (matter == null) throw new IllegalArgumentException("Matter not found.");
        return matter;
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
        out.put("source", safe(r.source));
        out.put("source_document_id", safe(r.sourceDocumentId));
        out.put("source_updated_at", safe(r.sourceUpdatedAt));
        out.put("read_only", documents.isReadOnly(r));
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

    private static LinkedHashMap<String, Object> searchTypeMap(search_jobs_service.SearchTypeInfo r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("key", safe(r.key));
        out.put("label", safe(r.label));
        out.put("description", safe(r.description));
        out.put("permission_key", safe(r.permissionKey));
        out.put("operators", new ArrayList<String>(r.operators == null ? List.of() : r.operators));
        return out;
    }

    private static LinkedHashMap<String, Object> searchJobMap(search_jobs_service.SearchJobSnapshot r, boolean includeResults) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("job_id", safe(r.jobId));
        out.put("tenant_uuid", safe(r.tenantUuid));
        out.put("requested_by", safe(r.requestedBy));
        out.put("search_type", safe(r.searchType));
        out.put("query", safe(r.query));
        out.put("operator", safe(r.operator));
        out.put("logic", safe(r.logic));
        ArrayList<LinkedHashMap<String, Object>> criteria = new ArrayList<LinkedHashMap<String, Object>>();
        for (search_jobs_service.SearchCriterion c : r.criteria) {
            if (c == null) continue;
            criteria.add(searchCriterionMap(c));
        }
        out.put("criteria", criteria);
        out.put("case_sensitive", r.caseSensitive);
        out.put("include_metadata", r.includeMetadata);
        out.put("include_ocr", r.includeOcr);
        out.put("status", safe(r.status));
        out.put("message", safe(r.message));
        out.put("created_at", safe(r.createdAt));
        out.put("started_at", safe(r.startedAt));
        out.put("completed_at", safe(r.completedAt));
        out.put("processed_count", r.processedCount);
        out.put("total_count", r.totalCount);
        out.put("result_count", r.resultCount);
        out.put("truncated", r.truncated);
        out.put("queued_ahead", r.queuedAhead);
        if (includeResults) {
            ArrayList<LinkedHashMap<String, Object>> items = new ArrayList<LinkedHashMap<String, Object>>();
            for (search_jobs_service.SearchResultRec row : r.results) {
                if (row == null) continue;
                items.add(searchResultMap(row));
            }
            out.put("results", items);
        }
        return out;
    }

    private static LinkedHashMap<String, Object> searchResultMap(search_jobs_service.SearchResultRec r) {
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

    private static LinkedHashMap<String, Object> searchCriterionMap(search_jobs_service.SearchCriterion r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("query", safe(r.query));
        out.put("operator", safe(r.operator));
        out.put("scope", safe(r.scope));
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
        out.put("review_type", r.reviewType);
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
        out.put("assignee_user_uuid", r.assigneeUserUuid);
        out.put("assignee_role_uuid", r.assigneeRoleUuid);
        out.put("queue_permission_key", r.queuePermissionKey);
        out.put("due_at", r.dueAt);
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

    private static LinkedHashMap<String, Object> factsClaimMap(matter_facts.ClaimRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("claim_uuid", safe(r.uuid));
        out.put("title", safe(r.title));
        out.put("summary", safe(r.summary));
        out.put("sort_order", r.sortOrder);
        out.put("created_at", safe(r.createdAt));
        out.put("updated_at", safe(r.updatedAt));
        out.put("trashed", r.trashed);
        return out;
    }

    private static LinkedHashMap<String, Object> factsElementMap(matter_facts.ElementRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("element_uuid", safe(r.uuid));
        out.put("claim_uuid", safe(r.claimUuid));
        out.put("title", safe(r.title));
        out.put("notes", safe(r.notes));
        out.put("sort_order", r.sortOrder);
        out.put("created_at", safe(r.createdAt));
        out.put("updated_at", safe(r.updatedAt));
        out.put("trashed", r.trashed);
        return out;
    }

    private static LinkedHashMap<String, Object> factsFactMap(matter_facts.FactRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("fact_uuid", safe(r.uuid));
        out.put("claim_uuid", safe(r.claimUuid));
        out.put("element_uuid", safe(r.elementUuid));
        out.put("summary", safe(r.summary));
        out.put("detail", safe(r.detail));
        out.put("internal_notes", safe(r.internalNotes));
        out.put("status", safe(r.status));
        out.put("strength", safe(r.strength));
        out.put("document_uuid", safe(r.documentUuid));
        out.put("part_uuid", safe(r.partUuid));
        out.put("version_uuid", safe(r.versionUuid));
        out.put("page_number", r.pageNumber);
        out.put("sort_order", r.sortOrder);
        out.put("created_at", safe(r.createdAt));
        out.put("updated_at", safe(r.updatedAt));
        out.put("trashed", r.trashed);
        return out;
    }

    private static LinkedHashMap<String, Object> factsReportMap(matter_facts.ReportRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("report_document_uuid", safe(r.reportDocumentUuid));
        out.put("report_part_uuid", safe(r.reportPartUuid));
        out.put("last_report_version_uuid", safe(r.lastReportVersionUuid));
        out.put("report_generated_at", safe(r.reportGeneratedAt));
        return out;
    }

    private static LinkedHashMap<String, Object> taskAttributeMap(task_attributes.AttributeRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("uuid", safe(r.uuid));
        out.put("key", safe(r.key));
        out.put("label", safe(r.label));
        out.put("data_type", safe(r.dataType));
        out.put("options", safe(r.options));
        out.put("required", r.required);
        out.put("enabled", r.enabled);
        out.put("sort_order", r.sortOrder);
        out.put("updated_at", safe(r.updatedAt));
        return out;
    }

    private static LinkedHashMap<String, Object> taskMap(tasks.TaskRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("task_uuid", safe(r.uuid));
        out.put("matter_uuid", safe(r.matterUuid));
        out.put("parent_task_uuid", safe(r.parentTaskUuid));
        out.put("title", safe(r.title));
        out.put("description", safe(r.description));
        out.put("status", safe(r.status));
        out.put("priority", safe(r.priority));
        out.put("assignment_mode", safe(r.assignmentMode));
        out.put("assigned_user_uuid", safe(r.assignedUserUuid));
        out.put("assigned_user_uuids", csvOrList(safe(r.assignedUserUuid)));
        out.put("due_at", safe(r.dueAt));
        out.put("reminder_at", safe(r.reminderAt));
        out.put("estimate_minutes", r.estimateMinutes);

        out.put("claim_uuid", safe(r.claimUuid));
        out.put("element_uuid", safe(r.elementUuid));
        out.put("fact_uuid", safe(r.factUuid));

        out.put("document_uuid", safe(r.documentUuid));
        out.put("part_uuid", safe(r.partUuid));
        out.put("version_uuid", safe(r.versionUuid));
        out.put("page_number", r.pageNumber);

        out.put("thread_uuid", safe(r.threadUuid));
        out.put("created_by", safe(r.createdBy));
        out.put("created_at", safe(r.createdAt));
        out.put("updated_at", safe(r.updatedAt));
        out.put("completed_at", safe(r.completedAt));
        out.put("archived", r.archived);

        out.put("report_document_uuid", safe(r.reportDocumentUuid));
        out.put("report_part_uuid", safe(r.reportPartUuid));
        out.put("last_report_version_uuid", safe(r.lastReportVersionUuid));
        return out;
    }

    private static LinkedHashMap<String, Object> taskNoteMap(tasks.NoteRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("note_uuid", safe(r.uuid));
        out.put("task_uuid", safe(r.taskUuid));
        out.put("body", safe(r.body));
        out.put("created_by", safe(r.createdBy));
        out.put("created_at", safe(r.createdAt));
        return out;
    }

    private static LinkedHashMap<String, Object> taskAssignmentMap(tasks.AssignmentRec r) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (r == null) return out;
        out.put("assignment_uuid", safe(r.uuid));
        out.put("task_uuid", safe(r.taskUuid));
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

    private static tasks.TaskRec copyTask(tasks.TaskRec in) {
        tasks.TaskRec out = new tasks.TaskRec();
        if (in == null) return out;
        out.uuid = safe(in.uuid);
        out.matterUuid = safe(in.matterUuid);
        out.parentTaskUuid = safe(in.parentTaskUuid);
        out.title = safe(in.title);
        out.description = safe(in.description);
        out.status = safe(in.status);
        out.priority = safe(in.priority);
        out.assignmentMode = safe(in.assignmentMode);
        out.assignedUserUuid = safe(in.assignedUserUuid);
        out.dueAt = safe(in.dueAt);
        out.reminderAt = safe(in.reminderAt);
        out.estimateMinutes = in.estimateMinutes;
        out.claimUuid = safe(in.claimUuid);
        out.elementUuid = safe(in.elementUuid);
        out.factUuid = safe(in.factUuid);
        out.documentUuid = safe(in.documentUuid);
        out.partUuid = safe(in.partUuid);
        out.versionUuid = safe(in.versionUuid);
        out.pageNumber = in.pageNumber;
        out.threadUuid = safe(in.threadUuid);
        out.createdBy = safe(in.createdBy);
        out.createdAt = safe(in.createdAt);
        out.updatedAt = safe(in.updatedAt);
        out.completedAt = safe(in.completedAt);
        out.archived = in.archived;
        out.reportDocumentUuid = safe(in.reportDocumentUuid);
        out.reportPartUuid = safe(in.reportPartUuid);
        out.lastReportVersionUuid = safe(in.lastReportVersionUuid);
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
            base = base.replaceAll("[^A-Za-z0-9.]", "_");
            v = base;
        }
        v = v.replaceAll("[^A-Za-z0-9.]", "_");
        if (v.isBlank()) v = "assembled";
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

    private static String mailUuidParam(Map<String, Object> params) {
        String id = str(params, "mail_uuid");
        if (id.isBlank()) id = str(params, "item_uuid");
        if (id.isBlank()) id = str(params, "uuid");
        return id;
    }

    private static String threadUuidParam(Map<String, Object> params) {
        String id = str(params, "thread_uuid");
        if (id.isBlank()) id = str(params, "ticket_uuid");
        return id;
    }

    private static String firstNonBlank(String... values) {
        if (values == null || values.length == 0) return "";
        for (String value : values) {
            String v = safe(value).trim();
            if (!v.isBlank()) return v;
        }
        return "";
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

    private static long moneyCents(Map<String, Object> params, String decimalKey, String centsKey) {
        if (params == null) return 0L;
        if (!safe(centsKey).trim().isBlank() && hasParam(params, centsKey)) {
            return longVal(params.get(centsKey), 0L);
        }
        if (safe(decimalKey).trim().isBlank()) return 0L;
        String raw = str(params, decimalKey).replace("$", "").replace(",", "").trim();
        if (raw.isBlank()) return 0L;
        try {
            java.math.BigDecimal bd = new java.math.BigDecimal(raw);
            return bd.multiply(java.math.BigDecimal.valueOf(100L)).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static long longVal(Object raw, long def) {
        if (raw == null) return def;
        if (raw instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(safe(asString(raw)).trim());
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

    private static ArrayList<search_jobs_service.SearchCriterion> searchCriteriaList(Map<String, Object> params) {
        ArrayList<search_jobs_service.SearchCriterion> out = new ArrayList<search_jobs_service.SearchCriterion>();
        if (params == null) return out;

        Object raw = params.get("criteria");
        collectSearchCriteria(raw, out);
        if (!out.isEmpty()) return out;

        String criteriaJson = str(params, "criteria_json");
        if (!criteriaJson.isBlank()) {
            try {
                Object parsed = JSON.readValue(criteriaJson, Object.class);
                collectSearchCriteria(parsed, out);
            } catch (Exception ignored) {
                return new ArrayList<search_jobs_service.SearchCriterion>();
            }
        }
        return out;
    }

    private static void collectSearchCriteria(Object raw, ArrayList<search_jobs_service.SearchCriterion> out) {
        if (out == null || raw == null) return;
        if (raw instanceof List<?> xs) {
            for (Object item : xs) {
                collectSearchCriteria(item, out);
            }
            return;
        }
        if (!(raw instanceof Map<?, ?> m)) return;
        search_jobs_service.SearchCriterion c = new search_jobs_service.SearchCriterion();
        c.query = safe(asString(m.get("query")));
        c.operator = safe(asString(m.get("operator")));
        c.scope = safe(asString(m.get("scope")));
        if (safe(c.query).trim().isBlank()) return;
        out.add(c);
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
