<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.awt.image.BufferedImage" %>
<%@ page import="net.familylawandprobate.controversies.image_hash_tools" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<% if (!require_permission("documents.access")) return; %>
<%!
  private static String safe(String s) { return s == null ? "" : s; }
  private static String csrfForRender(jakarta.servlet.http.HttpServletRequest req) {
    Object a = req == null ? null : req.getAttribute("csrfToken");
    if (a instanceof String s && !safe(s).trim().isBlank()) return s;
    try {
      jakarta.servlet.http.HttpSession sess = req == null ? null : req.getSession(false);
      Object t = sess == null ? null : sess.getAttribute("CSRF_TOKEN");
      if (t instanceof String cs && !safe(cs).trim().isBlank()) return cs;
    } catch (Exception ignored) {}
    return "";
  }
  private static String esc(String s) {
    return safe(s)
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;");
  }
%>
<%
String ctx = safe(request.getContextPath());
request.setAttribute("activeNav", "/image_hash_lab.jsp");
String csrfToken = csrfForRender(request);

String action = safe(request.getParameter("action")).trim();
String base64Png = safe(request.getParameter("base64_png"));
String compareAhash = safe(request.getParameter("compare_ahash"));
String compareDhash = safe(request.getParameter("compare_dhash"));
String error = "";
image_hash_tools.HashRec hash = null;
int ahashDistance = -1;
int dhashDistance = -1;

if ("compute".equalsIgnoreCase(action)) {
  try {
    BufferedImage image = image_hash_tools.decodePngBase64(base64Png);
    if (image == null) throw new IllegalArgumentException("Provide a valid Base64 PNG payload.");
    hash = image_hash_tools.compute(image);

    String normalizedCompareA = image_hash_tools.normalizeHex64(compareAhash);
    String normalizedCompareD = image_hash_tools.normalizeHex64(compareDhash);
    if (!normalizedCompareA.isBlank()) {
      ahashDistance = image_hash_tools.hammingDistance64(hash.averageHash64, normalizedCompareA);
    }
    if (!normalizedCompareD.isBlank()) {
      dhashDistance = image_hash_tools.hammingDistance64(hash.differenceHash64, normalizedCompareD);
    }
  } catch (Exception ex) {
    error = safe(ex.getMessage());
    if (error.isBlank()) error = "Unable to compute image hash.";
  }
}
%>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">Image Hash Lab</h1>
  <div class="meta u-mt-6">Compute SHA-256, average hash, and difference hash via <code>image_hash_tools</code>.</div>
</section>

<section class="card section-gap-12">
  <form method="post" action="<%= ctx %>/image_hash_lab.jsp" class="form">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="compute" />

    <label for="base64_png">Base64 PNG Payload</label>
    <textarea id="base64_png" name="base64_png" rows="10" placeholder="Paste Base64 PNG bytes (without data:image/png prefix)."><%= esc(base64Png) %></textarea>

    <div class="grid grid-2">
      <div>
        <label for="compare_ahash">Compare Against AHash (optional)</label>
        <input id="compare_ahash" name="compare_ahash" type="text" value="<%= esc(compareAhash) %>" placeholder="16-char hex hash" />
      </div>
      <div>
        <label for="compare_dhash">Compare Against DHash (optional)</label>
        <input id="compare_dhash" name="compare_dhash" type="text" value="<%= esc(compareDhash) %>" placeholder="16-char hex hash" />
      </div>
    </div>

    <button class="btn" type="submit">Compute Hashes</button>
  </form>
</section>

<% if (!error.isBlank()) { %>
<section class="card section-gap-12">
  <div class="alert alert-error"><%= esc(error) %></div>
</section>
<% } %>

<% if ("compute".equalsIgnoreCase(action) && error.isBlank() && hash != null) { %>
<section class="card section-gap-12">
  <h2 class="u-m-0">Result</h2>
  <div class="table-wrap table-wrap-tight u-mt-8">
    <table class="table">
      <tbody>
      <tr><th>Width</th><td><%= hash.width %></td></tr>
      <tr><th>Height</th><td><%= hash.height %></td></tr>
      <tr><th>SHA-256 (RGB)</th><td><code><%= esc(safe(hash.sha256Rgb)) %></code></td></tr>
      <tr><th>AHash 64</th><td><code><%= esc(safe(hash.averageHash64)) %></code></td></tr>
      <tr><th>DHash 64</th><td><code><%= esc(safe(hash.differenceHash64)) %></code></td></tr>
      <% if (ahashDistance >= 0) { %>
      <tr><th>AHash Hamming Distance</th><td><%= ahashDistance %></td></tr>
      <% } %>
      <% if (dhashDistance >= 0) { %>
      <tr><th>DHash Hamming Distance</th><td><%= dhashDistance %></td></tr>
      <% } %>
      </tbody>
    </table>
  </div>
</section>
<% } %>

<jsp:include page="footer.jsp" />
