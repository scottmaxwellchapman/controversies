<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.time.Duration" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>

<%@ page import="net.familylawandprobate.controversies.email_change_tokens" %>
<%@ page import="net.familylawandprobate.controversies.notification_emails" %>
<%@ page import="net.familylawandprobate.controversies.tenant_settings" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ page import="net.familylawandprobate.controversies.users_roles.UserRec" %>
<%@ include file="security.jspf" %>

<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

  private static String ceSafe(String s) { return s == null ? "" : s; }

  private static String ceEsc(String s) {
    if (s == null) return "";
    return s.replace("&","&amp;")
            .replace("<","&lt;")
            .replace(">","&gt;")
            .replace("\"","&quot;")
            .replace("'","&#39;");
  }

  private static String ceNormalizeEmail(String email) {
    return ceSafe(email).trim().toLowerCase(Locale.ROOT);
  }

  private static String ceCsrfForRender(jakarta.servlet.http.HttpServletRequest req) {
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

  private static boolean ceIsLocalhostIp(String ip) {
    if (ip == null) return false;
    String s = ip.trim();
    return "127.0.0.1".equals(s)
        || "::1".equals(s)
        || "0:0:0:0:0:0:0:1".equalsIgnoreCase(s)
        || s.startsWith("127.");
  }

  private static boolean ceIsPrivateOrLoopback(String ip) {
    if (ip == null) return false;
    String s = ip.trim();
    if (ceIsLocalhostIp(s)) return true;
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

  private static String ceClientIp(jakarta.servlet.http.HttpServletRequest req) {
    String ra = req.getRemoteAddr();
    if (ceIsPrivateOrLoopback(ra)) {
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
%>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";

  String tenantUuid = ceSafe((String)session.getAttribute(S_TENANT_UUID)).trim();
  String sessionUserUuid = ceSafe((String)session.getAttribute(users_roles.S_USER_UUID)).trim();
  String sessionUserEmail = ceSafe((String)session.getAttribute(users_roles.S_USER_EMAIL)).trim();
  boolean tenantAdmin = users_roles.hasPermissionTrue(session, "tenant_admin");

  if (tenantUuid.isBlank() || sessionUserUuid.isBlank() || sessionUserEmail.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  String message = null;
  String error = null;
  String action = ceSafe(request.getParameter("action")).trim();

  String requestNewEmail = ceNormalizeEmail(request.getParameter("requestNewEmail"));
  String verifyNewEmail = ceNormalizeEmail(request.getParameter("verifyNewEmail"));
  String verificationCode = ceSafe(request.getParameter("verificationCode")).trim();
  String currentPassword = ceSafe(request.getParameter("currentPassword"));

  String adminUserUuid = ceSafe(request.getParameter("adminUserUuid")).trim();
  String adminNewEmail = ceNormalizeEmail(request.getParameter("adminNewEmail"));

  users_roles store = users_roles.defaultStore();
  tenant_settings settingsStore = tenant_settings.defaultStore();
  notification_emails emailStore = notification_emails.defaultStore();
  email_change_tokens tokens = email_change_tokens.defaultStore();

  try { store.ensure(tenantUuid); } catch (Exception ignored) {}

  LinkedHashMap<String, String> emailCfg = new LinkedHashMap<String, String>();
  emailCfg.putAll(settingsStore.read(tenantUuid));
  notification_emails.ValidationResult emailValidation = emailStore.validateConfiguration(emailCfg);
  boolean emailVerificationAvailable = emailValidation.ok && !"disabled".equalsIgnoreCase(ceSafe(emailValidation.provider));

  List<UserRec> allUsers = new ArrayList<UserRec>();
  if (tenantAdmin) {
    try {
      allUsers = new ArrayList<UserRec>(store.listUsers(tenantUuid));
      allUsers.sort((a, b) -> ceSafe(a.emailAddress).compareToIgnoreCase(ceSafe(b.emailAddress)));
      if (adminUserUuid.isBlank() && !allUsers.isEmpty()) adminUserUuid = ceSafe(allUsers.get(0).uuid).trim();
    } catch (Exception ex) {
      error = "Unable to load users.";
      application.log("[change_email] unable to load users", ex);
    }
  }

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    if ("request_verification".equalsIgnoreCase(action)) {
      if (!emailVerificationAvailable) {
        error = "Email verification is unavailable until Notification Email is configured for this tenant.";
      } else if (requestNewEmail.isBlank()) {
        error = "New email address is required.";
      } else if (requestNewEmail.equals(sessionUserEmail)) {
        error = "The new email address must be different from your current email.";
      } else {
        try {
          UserRec existing = store.getUserByEmail(tenantUuid, requestNewEmail);
          if (existing != null && !sessionUserUuid.equals(ceSafe(existing.uuid).trim())) {
            error = "That email address is already in use.";
          } else {
            email_change_tokens.IssueResult issued = tokens.issue(
                    tenantUuid,
                    sessionUserUuid,
                    requestNewEmail,
                    ceClientIp(request),
                    Duration.ofMinutes(20)
            );

            String subject = "Verify your new email address";
            String bodyText =
                    "A request was made to change your sign-in email address.\n\n"
                            + "New email address: " + issued.newEmailAddress + "\n"
                            + "Verification code: " + issued.token + "\n"
                            + "Expires at: " + issued.expiresAt + "\n\n"
                            + "If this was not you, ignore this message.";
            String bodyHtml =
                    "<p>A request was made to change your sign-in email address.</p>"
                            + "<p><strong>New email address:</strong> " + ceEsc(issued.newEmailAddress) + "<br/>"
                            + "<strong>Verification code:</strong> <code>" + ceEsc(issued.token) + "</code><br/>"
                            + "<strong>Expires at:</strong> " + ceEsc(issued.expiresAt) + "</p>"
                            + "<p>If this was not you, ignore this message.</p>";

            emailStore.ensureQueue(tenantUuid);
            emailStore.enqueue(
                    tenantUuid,
                    sessionUserUuid,
                    new notification_emails.NotificationEmailRequest(
                            "change_email",
                            "",
                            "",
                            "",
                            subject,
                            bodyText,
                            bodyHtml,
                            List.of(issued.newEmailAddress),
                            List.of(),
                            List.of(),
                            List.of()
                    )
            );

            message = "Verification code queued for delivery to the new email address.";
            verifyNewEmail = requestNewEmail;
            requestNewEmail = "";
          }
        } catch (Exception ex) {
          String m = ceSafe(ex.getMessage()).trim();
          error = m.isBlank() ? "Unable to request verification code." : m;
          application.log("[change_email] request verification failed", ex);
        }
      }
    } else if ("verify_and_change_self".equalsIgnoreCase(action)) {
      if (verifyNewEmail.isBlank()) {
        error = "New email address is required.";
      } else if (verificationCode.isBlank()) {
        error = "Verification code is required.";
      } else if (currentPassword.isBlank()) {
        error = "Current password is required.";
      } else {
        try {
          UserRec existing = store.getUserByEmail(tenantUuid, verifyNewEmail);
          if (existing != null && !sessionUserUuid.equals(ceSafe(existing.uuid).trim())) {
            error = "That email address is already in use.";
          } else {
            users_roles.AuthResult ar = store.authenticate(tenantUuid, sessionUserEmail, currentPassword.toCharArray());
            if (ar == null || ar.user == null || !sessionUserUuid.equals(ceSafe(ar.user.uuid).trim())) {
              error = "Current password is incorrect.";
            } else {
              email_change_tokens.ConsumeResult consumed = tokens.consume(
                      tenantUuid,
                      sessionUserUuid,
                      verifyNewEmail,
                      verificationCode,
                      ceClientIp(request)
              );
              if (!consumed.ok) {
                error = "Verification code is invalid, expired, or already used.";
              } else {
                boolean changed = store.updateUserEmail(tenantUuid, sessionUserUuid, consumed.newEmailAddress);
                if (!changed) {
                  error = "No changes were made.";
                } else {
                  session.setAttribute(users_roles.S_USER_EMAIL, consumed.newEmailAddress);
                  message = "Email address updated.";
                  verifyNewEmail = "";
                  verificationCode = "";
                }
              }
            }
          }
        } catch (Exception ex) {
          String m = ceSafe(ex.getMessage()).trim();
          error = m.isBlank() ? "Unable to update email address." : m;
          application.log("[change_email] verify-and-change failed", ex);
        }
      }
    } else if ("admin_change_email".equalsIgnoreCase(action)) {
      if (!tenantAdmin) {
        error = "Tenant administrator permission is required.";
      } else if (adminUserUuid.isBlank()) {
        error = "Select a user.";
      } else if (adminNewEmail.isBlank()) {
        error = "New email address is required.";
      } else {
        try {
          boolean changed = store.updateUserEmail(tenantUuid, adminUserUuid, adminNewEmail);
          if (!changed) {
            error = "No changes were made.";
          } else {
            if (adminUserUuid.equals(sessionUserUuid)) {
              session.setAttribute(users_roles.S_USER_EMAIL, adminNewEmail);
            }
            message = "User email address updated.";
          }
        } catch (Exception ex) {
          String m = ceSafe(ex.getMessage()).trim();
          error = m.isBlank() ? "Unable to update user email address." : m;
        }
      }
    }
  }

  String csrfToken = ceCsrfForRender(request);
%>

<jsp:include page="header.jsp" />

<div class="container main">
  <section class="card narrow">
    <div class="section-head">
      <div>
        <h1>Change E-Mail Address</h1>
        <div class="meta">Verify your new email address before applying the change.</div>
      </div>
    </div>

    <% if (message != null) { %>
      <div class="alert alert-ok"><%= ceEsc(message) %></div>
      <div style="height:12px;"></div>
    <% } %>
    <% if (error != null) { %>
      <div class="alert alert-error"><%= ceEsc(error) %></div>
      <div style="height:12px;"></div>
    <% } %>

    <% if (!emailVerificationAvailable) { %>
      <div class="alert alert-warn" style="margin-bottom:12px;">
        Email verification is unavailable because Notification Email is not fully configured for this tenant.
      </div>
    <% } %>

    <form class="form" method="post" action="<%= ctx %>/change_email.jsp">
      <input type="hidden" name="csrfToken" value="<%= ceEsc(csrfToken) %>" />
      <input type="hidden" name="action" value="request_verification" />

      <label>
        <span>New E-Mail Address</span>
        <input type="text" name="requestNewEmail" value="<%= ceEsc(requestNewEmail) %>" autocomplete="email" required />
      </label>

      <div class="actions">
        <button class="btn" type="submit" <%= emailVerificationAvailable ? "" : "disabled" %>>Send Verification Code</button>
      </div>
    </form>

    <div style="height:12px;"></div>

    <form class="form" method="post" action="<%= ctx %>/change_email.jsp">
      <input type="hidden" name="csrfToken" value="<%= ceEsc(csrfToken) %>" />
      <input type="hidden" name="action" value="verify_and_change_self" />

      <label>
        <span>New E-Mail Address</span>
        <input type="text" name="verifyNewEmail" value="<%= ceEsc(verifyNewEmail) %>" autocomplete="email" required />
      </label>

      <label>
        <span>Verification Code</span>
        <input type="text" name="verificationCode" value="<%= ceEsc(verificationCode) %>" autocomplete="one-time-code" required />
      </label>

      <label>
        <span>Current Password</span>
        <input type="password" name="currentPassword" autocomplete="current-password" required />
      </label>

      <div class="actions">
        <button class="btn" type="submit">Verify And Apply Change</button>
      </div>
    </form>
  </section>

  <% if (tenantAdmin) { %>
    <section class="card narrow" style="margin-top:12px;">
      <div class="section-head">
        <div>
          <h2 style="margin:0;">Admin: Set User E-Mail Address</h2>
          <div class="meta">Tenant administrators can update any user email without verification.</div>
        </div>
      </div>

      <form class="form" method="post" action="<%= ctx %>/change_email.jsp">
        <input type="hidden" name="csrfToken" value="<%= ceEsc(csrfToken) %>" />
        <input type="hidden" name="action" value="admin_change_email" />

        <label>
          <span>User</span>
          <select name="adminUserUuid" required <%= allUsers.isEmpty() ? "disabled" : "" %>>
            <% if (allUsers.isEmpty()) { %>
              <option value="" selected>No users available</option>
            <% } else { %>
              <% for (int i = 0; i < allUsers.size(); i++) {
                   UserRec u = allUsers.get(i);
                   String uUuid = ceSafe(u.uuid).trim();
                   boolean selected = !adminUserUuid.isBlank() && adminUserUuid.equals(uUuid);
              %>
                <option value="<%= ceEsc(uUuid) %>" <%= selected ? "selected" : "" %>><%= ceEsc(ceSafe(u.emailAddress)) %></option>
              <% } %>
            <% } %>
          </select>
        </label>

        <label>
          <span>New E-Mail Address</span>
          <input type="text" name="adminNewEmail" value="<%= ceEsc(adminNewEmail) %>" autocomplete="email" required />
        </label>

        <div class="actions">
          <button class="btn" type="submit" <%= allUsers.isEmpty() ? "disabled" : "" %>>Set User E-Mail Address</button>
        </div>
      </form>
    </section>
  <% } %>
</div>

<jsp:include page="footer.jsp" />
