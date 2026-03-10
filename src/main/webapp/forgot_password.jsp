<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.nio.file.Files" %>
<%@ page import="java.nio.file.Path" %>
<%@ page import="java.nio.file.Paths" %>
<%@ page import="java.time.Duration" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>
<%@ page import="java.util.stream.Stream" %>

<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ page import="net.familylawandprobate.controversies.notification_emails" %>
<%@ page import="net.familylawandprobate.controversies.password_reset_tokens" %>
<%@ page import="net.familylawandprobate.controversies.tenant_settings" %>
<%@ page import="net.familylawandprobate.controversies.tenants" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ include file="security.jspf" %>

<%
  if (!require_loopback()) return;
%>

<%!
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

  private static String fpSafe(String s) {
    return s == null ? "" : s;
  }

  private static String fpEsc(String s) {
    if (s == null) return "";
    return s.replace("&","&amp;")
            .replace("<","&lt;")
            .replace(">","&gt;")
            .replace("\"","&quot;")
            .replace("'","&#39;");
  }

  private static String fpSafeFileToken(String s) {
    String t = fpSafe(s).trim();
    if (t.isBlank()) return "";
    return t.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static boolean fpTenantInList(List<tenants.Tenant> xs, String tenantUuid) {
    String tu = fpSafe(tenantUuid).trim();
    if (tu.isBlank() || xs == null) return false;
    for (int i = 0; i < xs.size(); i++) {
      tenants.Tenant t = xs.get(i);
      if (t == null) continue;
      if (tu.equals(fpSafe(t.uuid).trim())) return true;
    }
    return false;
  }

  private static String fpNormalizeEmail(String email) {
    return fpSafe(email).trim().toLowerCase(Locale.ROOT);
  }

  private static String fpCsrfForRender(jakarta.servlet.http.HttpServletRequest req) {
    Object a = req.getAttribute("csrfToken");
    if (a instanceof String) {
      String s = (String) a;
      if (s != null && !s.trim().isEmpty()) return s;
    }
    try {
      jakarta.servlet.http.HttpSession sess = req.getSession(false);
      if (sess != null) {
        Object t = sess.getAttribute(CSRF_SESSION_KEY);
        if (t instanceof String) {
          String cs = (String) t;
          if (cs != null && !cs.trim().isEmpty()) return cs;
        }
      }
    } catch (Exception ignored) {}
    return "";
  }

  private static boolean fpIsLocalhostIp(String ip) {
    if (ip == null) return false;
    String s = ip.trim();
    return "127.0.0.1".equals(s)
        || "::1".equals(s)
        || "0:0:0:0:0:0:0:1".equalsIgnoreCase(s)
        || s.startsWith("127.");
  }

  private static boolean fpIsPrivateOrLoopback(String ip) {
    if (ip == null) return false;
    String s = ip.trim();
    if (fpIsLocalhostIp(s)) return true;
    if (s.startsWith("10.")) return true;
    if (s.startsWith("192.168.")) return true;
    if (s.startsWith("172.")) {
      String[] parts = s.split("\\.");
      if (parts.length >= 2) {
        try {
          int b = Integer.parseInt(parts[1]);
          return b >= 16 && b <= 31;
        } catch (Exception ignored) {}
      }
    }
    return false;
  }

  private static String fpClientIp(jakarta.servlet.http.HttpServletRequest req) {
    String ra = req.getRemoteAddr();
    if (fpIsPrivateOrLoopback(ra)) {
      String xff = req.getHeader("X-Forwarded-For");
      if (xff != null && !xff.isBlank()) {
        int comma = xff.indexOf(',');
        String first = (comma > 0 ? xff.substring(0, comma) : xff).trim();
        if (!first.isBlank()) return first;
      }
      String xrip = req.getHeader("X-Real-IP");
      if (xrip != null && !xrip.isBlank()) return xrip.trim();
    }
    return ra;
  }

  private static void fpClearAllUserBindings(String tenantUuid, String userUuid) {
    String tu = fpSafeFileToken(tenantUuid);
    String uu = fpSafeFileToken(userUuid);
    if (tu.isBlank() || uu.isBlank()) return;

    Path bindingsDir = Paths.get("data", "tenants", tu, "bindings").toAbsolutePath();
    if (!Files.isDirectory(bindingsDir)) return;

    String prefix = "user_binding_" + uu + "_session_";
    try (Stream<Path> stream = Files.list(bindingsDir)) {
      stream.forEach(p -> {
        try {
          if (p == null || !Files.isRegularFile(p)) return;
          String name = fpSafe(p.getFileName() == null ? "" : p.getFileName().toString());
          if (name.startsWith(prefix) && name.endsWith(".xml")) Files.deleteIfExists(p);
        } catch (Exception ignored) {}
      });
    } catch (Exception ignored) {}
  }

  private static void fpAudit(activity_log logs,
                              String level,
                              String action,
                              String tenantUuid,
                              String userUuid,
                              Map<String, String> details) {
    if (logs == null) return;
    String lvl = fpSafe(level).trim().toLowerCase(Locale.ROOT);
    if ("error".equals(lvl)) logs.logError(action, tenantUuid, userUuid, "", "", details);
    else if ("warning".equals(lvl)) logs.logWarning(action, tenantUuid, userUuid, "", "", details);
    else logs.logVerbose(action, tenantUuid, userUuid, "", "", details);
  }
%>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";

  String message = null;
  String error = null;
  String action = fpSafe(request.getParameter("action")).trim();

  String reqTenantUuid = fpSafe(request.getParameter("reqTenantUuid")).trim();
  String reqEmail = fpNormalizeEmail(request.getParameter("reqEmail"));

  String rstTenantUuid = fpSafe(request.getParameter("rstTenantUuid")).trim();
  String resetToken = fpSafe(request.getParameter("resetToken")).trim();
  if (resetToken.isBlank()) resetToken = fpSafe(request.getParameter("token")).trim();
  String newPassword = fpSafe(request.getParameter("newPassword"));
  String confirmPassword = fpSafe(request.getParameter("confirmPassword"));

  String tenantFromQuery = fpSafe(request.getParameter("tenantUuid")).trim();
  if (reqTenantUuid.isBlank() && !tenantFromQuery.isBlank()) reqTenantUuid = tenantFromQuery;
  if (rstTenantUuid.isBlank() && !tenantFromQuery.isBlank()) rstTenantUuid = tenantFromQuery;

  tenants tenantStore = tenants.defaultStore();
  tenant_settings settingsStore = tenant_settings.defaultStore();
  notification_emails emailStore = notification_emails.defaultStore();
  users_roles userStore = users_roles.defaultStore();
  password_reset_tokens resetStore = password_reset_tokens.defaultStore();
  activity_log logs = activity_log.defaultStore();

  List<tenants.Tenant> enabledTenants = new ArrayList<tenants.Tenant>();
  List<tenants.Tenant> eligibleTenants = new ArrayList<tenants.Tenant>();
  LinkedHashMap<String, String> tenantLabelByUuid = new LinkedHashMap<String, String>();

  try { tenantStore.ensure(); } catch (Exception ignored) {}
  try {
    List<tenants.Tenant> all = tenantStore.list();
    for (int i = 0; i < all.size(); i++) {
      tenants.Tenant t = all.get(i);
      if (t == null) continue;
      String tUuid = fpSafe(t.uuid).trim();
      String tLabel = fpSafe(t.label).trim();
      if (!t.enabled || tUuid.isBlank() || tLabel.isBlank()) continue;
      enabledTenants.add(t);
      tenantLabelByUuid.put(tUuid, tLabel);
    }
    enabledTenants.sort((a, b) -> fpSafe(a.label).compareToIgnoreCase(fpSafe(b.label)));
  } catch (Exception ex) {
    error = "Unable to load tenants.";
    application.log("[forgot_password] unable to load tenants", ex);
  }

  for (int i = 0; i < enabledTenants.size(); i++) {
    tenants.Tenant t = enabledTenants.get(i);
    if (t == null) continue;
    String tUuid = fpSafe(t.uuid).trim();
    if (tUuid.isBlank()) continue;
    try {
      LinkedHashMap<String, String> cfg = settingsStore.read(tUuid);
      notification_emails.ValidationResult vr = emailStore.validateConfiguration(cfg);
      if (vr.ok && !"disabled".equalsIgnoreCase(fpSafe(vr.provider))) eligibleTenants.add(t);
    } catch (Exception ignored) {}
  }

  if (reqTenantUuid.isBlank() && eligibleTenants.size() == 1) {
    reqTenantUuid = fpSafe(eligibleTenants.get(0).uuid).trim();
  }
  if (rstTenantUuid.isBlank() && !reqTenantUuid.isBlank()) rstTenantUuid = reqTenantUuid;
  if (rstTenantUuid.isBlank() && eligibleTenants.size() == 1) {
    rstTenantUuid = fpSafe(eligibleTenants.get(0).uuid).trim();
  }

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    if ("request_reset".equalsIgnoreCase(action)) {
      if (eligibleTenants.isEmpty()) {
        error = "Forgot password is unavailable because no tenant has notification email configured.";
        fpAudit(logs, "warning", "auth.password_reset.request_rejected", reqTenantUuid, "", Map.of("reason", "feature_unavailable"));
      } else if (reqTenantUuid.isBlank()) {
        error = "Please select a tenant.";
        fpAudit(logs, "warning", "auth.password_reset.request_rejected", reqTenantUuid, "", Map.of("reason", "missing_tenant"));
      } else if (!fpTenantInList(eligibleTenants, reqTenantUuid)) {
        error = "Forgot password is unavailable for the selected tenant.";
        fpAudit(logs, "warning", "auth.password_reset.request_rejected", reqTenantUuid, "", Map.of("reason", "tenant_not_eligible"));
      } else if (reqEmail.isBlank()) {
        error = "Email is required.";
        fpAudit(logs, "warning", "auth.password_reset.request_rejected", reqTenantUuid, "", Map.of("reason", "missing_email"));
      } else {
        try {
          String clientIp = fpClientIp(request);
          users_roles.UserRec user = null;
          try { userStore.ensure(reqTenantUuid); } catch (Exception ignored) {}
          try { user = userStore.getUserByEmail(reqTenantUuid, reqEmail); } catch (Exception ignored) {}

          if (user != null && user.enabled) {
            password_reset_tokens.IssueResult issued = resetStore.issue(
                    reqTenantUuid,
                    user.uuid,
                    user.emailAddress,
                    clientIp,
                    Duration.ofMinutes(20)
            );
            fpAudit(logs, "verbose", "auth.password_reset.requested", reqTenantUuid, fpSafe(user.uuid).trim(), Map.of(
              "client_ip", fpSafe(clientIp),
              "delivery_channel", "notification_email",
              "expires_at", fpSafe(issued.expiresAt)
            ));

            String tenantLabel = tenantLabelByUuid.getOrDefault(reqTenantUuid, "your tenant");
            String subject = "Password reset code for " + tenantLabel;
            String bodyText =
                    "A password reset was requested for your account.\n\n"
                            + "Tenant: " + tenantLabel + "\n"
                            + "Reset code: " + issued.token + "\n"
                            + "Expires at: " + issued.expiresAt + "\n\n"
                            + "If you did not request this, you can ignore this email.";
            String bodyHtml =
                    "<p>A password reset was requested for your account.</p>"
                            + "<p><strong>Tenant:</strong> " + fpEsc(tenantLabel) + "<br/>"
                            + "<strong>Reset code:</strong> <code>" + fpEsc(issued.token) + "</code><br/>"
                            + "<strong>Expires at:</strong> " + fpEsc(issued.expiresAt) + "</p>"
                            + "<p>If you did not request this, you can ignore this email.</p>";

            try {
              emailStore.ensureQueue(reqTenantUuid);
              notification_emails.NotificationEmailRequest mailReq =
                      new notification_emails.NotificationEmailRequest(
                              "forgot_password",
                              "",
                              "",
                              "",
                              subject,
                              bodyText,
                              bodyHtml,
                              List.of(user.emailAddress),
                              List.of(),
                              List.of(),
                              List.of()
                      );
              emailStore.enqueue(reqTenantUuid, "forgot_password", mailReq);
            } catch (Exception mailEx) {
              application.log("[forgot_password] unable to enqueue reset email for tenant " + reqTenantUuid, mailEx);
              fpAudit(logs, "error", "auth.password_reset.delivery_failed", reqTenantUuid, fpSafe(user.uuid).trim(), Map.of(
                "reason", fpSafe(mailEx.getMessage())
              ));
            }
          } else {
            fpAudit(logs, "warning", "auth.password_reset.request_ignored", reqTenantUuid, "", Map.of(
              "reason", user == null ? "user_not_found" : "user_disabled",
              "client_ip", fpSafe(clientIp)
            ));
          }
        } catch (Exception ex) {
          application.log("[forgot_password] request reset failure", ex);
          fpAudit(logs, "error", "auth.password_reset.request_failed", reqTenantUuid, "", Map.of(
            "reason", fpSafe(ex.getMessage())
          ));
        }

        message = "If an active account exists for that tenant and email, a reset code has been queued for delivery.";
        reqEmail = "";
      }
    } else if ("complete_reset".equalsIgnoreCase(action)) {
      if (eligibleTenants.isEmpty()) {
        error = "Forgot password is unavailable because no tenant has notification email configured.";
        fpAudit(logs, "warning", "auth.password_reset.complete_rejected", rstTenantUuid, "", Map.of("reason", "feature_unavailable"));
      } else if (rstTenantUuid.isBlank()) {
        error = "Please select a tenant.";
        fpAudit(logs, "warning", "auth.password_reset.complete_rejected", rstTenantUuid, "", Map.of("reason", "missing_tenant"));
      } else if (!fpTenantInList(eligibleTenants, rstTenantUuid)) {
        error = "Forgot password is unavailable for the selected tenant.";
        fpAudit(logs, "warning", "auth.password_reset.complete_rejected", rstTenantUuid, "", Map.of("reason", "tenant_not_eligible"));
      } else if (resetToken.isBlank()) {
        error = "Reset code is required.";
        fpAudit(logs, "warning", "auth.password_reset.complete_rejected", rstTenantUuid, "", Map.of("reason", "missing_reset_code"));
      } else if (newPassword.isBlank()) {
        error = "New password is required.";
        fpAudit(logs, "warning", "auth.password_reset.complete_rejected", rstTenantUuid, "", Map.of("reason", "missing_new_password"));
      } else if (!newPassword.equals(confirmPassword)) {
        error = "Password confirmation does not match.";
        fpAudit(logs, "warning", "auth.password_reset.complete_rejected", rstTenantUuid, "", Map.of("reason", "confirm_mismatch"));
      } else {
        List<String> policyIssues = settingsStore.validatePasswordAgainstPolicy(rstTenantUuid, newPassword.toCharArray());
        if (policyIssues != null && !policyIssues.isEmpty()) {
          error = "Password does not meet tenant policy: " + String.join(" ", policyIssues);
          fpAudit(logs, "warning", "auth.password_reset.complete_rejected", rstTenantUuid, "", Map.of("reason", "password_policy_failed"));
        }
      }

      if (error == null) {
        try {
          password_reset_tokens.ConsumeResult consumed = resetStore.consume(
                  rstTenantUuid,
                  resetToken,
                  fpClientIp(request)
          );
          if (!consumed.ok) {
            error = "Reset code is invalid, expired, or already used.";
            fpAudit(logs, "warning", "auth.password_reset.consume_failed", rstTenantUuid, "", Map.of("reason", "invalid_or_expired_token"));
          } else {
            boolean updated = false;
            users_roles.UserRec target = null;
            try { userStore.ensure(rstTenantUuid); } catch (Exception ignored) {}
            try { target = userStore.getUserByUuid(rstTenantUuid, consumed.userUuid); } catch (Exception ignored) {}

            if (target == null || !target.enabled) {
              error = "Unable to reset this account. Request a new reset code.";
              fpAudit(logs, "warning", "auth.password_reset.complete_failed", rstTenantUuid, fpSafe(consumed.userUuid).trim(), Map.of("reason", "target_user_missing_or_disabled"));
            } else {
              try {
                updated = userStore.updateUserPassword(rstTenantUuid, target.uuid, newPassword.toCharArray());
              } catch (Exception ex) {
                updated = false;
                String msg = fpSafe(ex.getMessage()).trim();
                if (!msg.isBlank()) error = msg;
                application.log("[forgot_password] unable to update password for tenant " + rstTenantUuid, ex);
                fpAudit(logs, "error", "auth.password_reset.complete_failed", rstTenantUuid, fpSafe(target.uuid).trim(), Map.of("reason", fpSafe(ex.getMessage())));
              }

              if (!updated) {
                if (error == null || error.isBlank()) {
                  error = "Unable to update password. Request a new reset code and try again.";
                }
                fpAudit(logs, "warning", "auth.password_reset.complete_failed", rstTenantUuid, fpSafe(target.uuid).trim(), Map.of("reason", "password_not_updated"));
              } else {
                fpClearAllUserBindings(rstTenantUuid, target.uuid);
                message = "Password reset complete. You can sign in now.";
                fpAudit(logs, "verbose", "auth.password_reset.completed", rstTenantUuid, fpSafe(target.uuid).trim(), Map.of("session_bindings_cleared", "true"));
                resetToken = "";
                newPassword = "";
                confirmPassword = "";
              }
            }
          }
        } catch (Exception ex) {
          error = "Unable to process reset code.";
          application.log("[forgot_password] complete reset failure", ex);
          fpAudit(logs, "error", "auth.password_reset.complete_failed", rstTenantUuid, "", Map.of("reason", fpSafe(ex.getMessage())));
        }
      }
    }
  }

  String csrfToken = fpCsrfForRender(request);
%>

<jsp:include page="header.jsp" />

<div class="container main">
  <section class="card narrow">
    <div class="section-head">
      <div>
        <h1>Forgot Password</h1>
        <div class="meta">Request a password reset code and then set a new password.</div>
      </div>
    </div>

    <% if (message != null) { %>
      <div class="alert alert-ok"><%= fpEsc(message) %></div>
      <div style="height:12px;"></div>
    <% } %>
    <% if (error != null) { %>
      <div class="alert alert-error"><%= fpEsc(error) %></div>
      <div style="height:12px;"></div>
    <% } %>

    <% if (eligibleTenants.isEmpty()) { %>
      <div class="alert alert-warn">
        Forgot password is not available. A tenant administrator must configure Notification Email in Tenant Settings first.
      </div>
      <div style="height:12px;"></div>
    <% } %>

    <form class="form" method="post" action="<%= ctx %>/forgot_password.jsp">
      <input type="hidden" name="csrfToken" value="<%= fpEsc(csrfToken) %>" />
      <input type="hidden" name="action" value="request_reset" />

      <label>
        <span>Tenant</span>
        <select name="reqTenantUuid" required <%= eligibleTenants.isEmpty() ? "disabled" : "" %>>
          <% if (eligibleTenants.size() != 1) { %>
            <option value="" <%= reqTenantUuid.isBlank() ? "selected" : "" %> disabled>Select a tenant…</option>
          <% } %>
          <% for (int i = 0; i < eligibleTenants.size(); i++) {
               tenants.Tenant t = eligibleTenants.get(i);
               String tUuid = fpSafe(t.uuid).trim();
               String tLabel = fpSafe(t.label).trim();
               boolean selected = !reqTenantUuid.isBlank() && reqTenantUuid.equals(tUuid);
          %>
            <option value="<%= fpEsc(tUuid) %>" <%= selected ? "selected" : "" %>><%= fpEsc(tLabel) %></option>
          <% } %>
        </select>
      </label>

      <label>
        <span>Email</span>
        <input type="text" name="reqEmail" value="<%= fpEsc(reqEmail) %>" autocomplete="email" required />
      </label>

      <div class="actions">
        <button class="btn" type="submit" <%= eligibleTenants.isEmpty() ? "disabled" : "" %>>Send Reset Code</button>
        <a class="btn btn-ghost" href="<%= ctx %>/tenant_login.jsp">Back To Sign In</a>
      </div>

      <div class="help">
        Only tenants with valid Notification Email configuration are listed here.
      </div>
    </form>
  </section>

  <section class="card narrow" style="margin-top:12px;">
    <div class="section-head">
      <div>
        <h2 style="margin:0;">Complete Reset</h2>
        <div class="meta">Use the emailed reset code to set a new password.</div>
      </div>
    </div>

    <form class="form" method="post" action="<%= ctx %>/forgot_password.jsp">
      <input type="hidden" name="csrfToken" value="<%= fpEsc(csrfToken) %>" />
      <input type="hidden" name="action" value="complete_reset" />

      <label>
        <span>Tenant</span>
        <select name="rstTenantUuid" required <%= eligibleTenants.isEmpty() ? "disabled" : "" %>>
          <% if (eligibleTenants.size() != 1) { %>
            <option value="" <%= rstTenantUuid.isBlank() ? "selected" : "" %> disabled>Select a tenant…</option>
          <% } %>
          <% for (int i = 0; i < eligibleTenants.size(); i++) {
               tenants.Tenant t = eligibleTenants.get(i);
               String tUuid = fpSafe(t.uuid).trim();
               String tLabel = fpSafe(t.label).trim();
               boolean selected = !rstTenantUuid.isBlank() && rstTenantUuid.equals(tUuid);
          %>
            <option value="<%= fpEsc(tUuid) %>" <%= selected ? "selected" : "" %>><%= fpEsc(tLabel) %></option>
          <% } %>
        </select>
      </label>

      <label>
        <span>Reset Code</span>
        <input type="text" name="resetToken" value="<%= fpEsc(resetToken) %>" autocomplete="one-time-code" required />
      </label>

      <label>
        <span>New Password</span>
        <input type="password" name="newPassword" autocomplete="new-password" required />
      </label>

      <label>
        <span>Confirm New Password</span>
        <input type="password" name="confirmPassword" autocomplete="new-password" required />
      </label>

      <div class="actions">
        <button class="btn" type="submit" <%= eligibleTenants.isEmpty() ? "disabled" : "" %>>Reset Password</button>
      </div>

      <div class="help">
        Reset codes are one-time and expire quickly for security.
      </div>
    </form>
  </section>
</div>

<jsp:include page="footer.jsp" />
