<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>

<%!
  private static String safe(String s) { return s == null ? "" : s; }

  private static String normalizeNext(String next) {
    String n = safe(next).trim();
    if (n.isBlank()) return "/index.jsp";
    if (n.contains("://") || n.startsWith("//") || n.contains("\r") || n.contains("\n")) return "/index.jsp";
    if (!n.startsWith("/")) return "/index.jsp";
    return n;
  }
%>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";
  String next = normalizeNext(request.getParameter("next"));
  response.sendRedirect(ctx + "/tenant_login.jsp?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8));
%>
