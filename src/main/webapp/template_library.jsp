<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.io.ByteArrayInputStream" %>
<%@ page import="java.io.ByteArrayOutputStream" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Base64" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.zip.ZipEntry" %>
<%@ page import="java.util.zip.ZipInputStream" %>

<%@ page import="net.familylawandprobate.controversies.form_templates" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%! 
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";
  private static final int MAX_IMPORT_BYTES = 30 * 1024 * 1024;
  private static final int MAX_ZIP_UPLOAD_BYTES = 60 * 1024 * 1024;
  private static final int MAX_UPLOAD_PAYLOAD_CHARS = 120 * 1024 * 1024;
  private static final int MAX_IMPORT_ITEMS = 500;
  private static final long MAX_TOTAL_IMPORT_BYTES = 120L * 1024L * 1024L;
  private static final int MAX_ZIP_ENTRIES_PER_ARCHIVE = 500;

  private static String safe(String s) { return s == null ? "" : s; }

  private static String esc(String s) {
    if (s == null) return "";
    return s.replace("&","&amp;")
            .replace("<","&lt;")
            .replace(">","&gt;")
            .replace("\"","&quot;")
            .replace("'","&#39;");
  }

  private static String enc(String s) {
    return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
  }

  private static String csrfForRender(jakarta.servlet.http.HttpServletRequest req) {
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

  private static byte[] decodeBase64(String b64) {
    String raw = safe(b64).trim();
    if (raw.isBlank()) return new byte[0];
    try {
      return Base64.getDecoder().decode(raw);
    } catch (Exception ignored) {
      return new byte[0];
    }
  }

  private static String decodeBase64Utf8(String b64) {
    byte[] bytes = decodeBase64(b64);
    if (bytes.length == 0) return "";
    try {
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (Exception ignored) {
      return "";
    }
  }

  private static byte[] readStreamBytes(java.io.InputStream in, int maxBytes) throws Exception {
    if (in == null) return new byte[0];
    ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
    byte[] buf = new byte[8192];
    int total = 0;
    while (true) {
      int read = in.read(buf);
      if (read < 0) break;
      if (read == 0) continue;
      total += read;
      if (maxBytes > 0 && total > maxBytes) {
        throw new IllegalArgumentException("Uploaded file exceeds the 30 MB import limit.");
      }
      out.write(buf, 0, read);
    }
    return out.toByteArray();
  }

  private static String normalizeRelativePath(String raw) {
    String in = safe(raw).replace('\\', '/').trim();
    if (in.isBlank()) return "";

    String[] pieces = in.split("/");
    ArrayList<String> clean = new ArrayList<String>();
    for (int i = 0; i < pieces.length; i++) {
      String p = safe(pieces[i]).trim();
      if (p.isBlank()) continue;
      if (".".equals(p)) continue;
      if ("..".equals(p)) continue;
      clean.add(p);
    }
    if (clean.isEmpty()) return "";
    return String.join("/", clean);
  }

  private static String fileNameOnly(String raw) {
    String in = normalizeRelativePath(raw);
    if (in.isBlank()) return "";
    int slash = in.lastIndexOf('/');
    if (slash < 0) return in;
    if (slash + 1 >= in.length()) return "";
    return in.substring(slash + 1).trim();
  }

  private static String parentPath(String raw) {
    String in = normalizeRelativePath(raw);
    if (in.isBlank()) return "";
    int slash = in.lastIndexOf('/');
    if (slash <= 0) return "";
    return in.substring(0, slash).trim();
  }

  private static String joinFolderPath(String left, String right) {
    String l = safe(left).trim();
    String r = safe(right).trim();
    if (l.isBlank()) return r;
    if (r.isBlank()) return l;
    return l + "/" + r;
  }

  private static String labelFromFileName(String fileName) {
    String in = fileNameOnly(fileName);
    if (in.isBlank()) return "Template";

    int dot = in.lastIndexOf('.');
    if (dot > 0) in = in.substring(0, dot);

    String label = in.replaceAll("[_\\-]+", " ").trim();
    return label.isBlank() ? "Template" : label;
  }

  private static boolean isImportableTemplateName(String fileName) {
    String n = safe(fileName).trim().toLowerCase(Locale.ROOT);
    return n.endsWith(".docx")
        || n.endsWith(".doc")
        || n.endsWith(".rtf")
        || n.endsWith(".odt")
        || n.endsWith(".txt");
  }

  private static boolean isZipName(String fileName) {
    return safe(fileName).trim().toLowerCase(Locale.ROOT).endsWith(".zip");
  }

  private static int intOr(String raw, int fallback) {
    try {
      return Integer.parseInt(safe(raw).trim());
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static String shortErrorMessage(Throwable ex) {
    if (ex == null) return "";
    String msg = safe(ex.getMessage()).trim();
    if (!msg.isBlank()) return msg;
    return ex.getClass().getSimpleName();
  }

  private static void logInfo(jakarta.servlet.ServletContext app, String message) {
    if (app == null) return;
    app.log("[template_library] " + safe(message));
  }

  private static void logWarn(jakarta.servlet.ServletContext app, String message, Throwable ex) {
    if (app == null) return;
    if (ex == null) {
      app.log("[template_library] " + safe(message));
    } else {
      app.log("[template_library] " + safe(message), ex);
    }
  }

  private static final class UploadBlob {
    public final String fileName;
    public final String relativePath;
    public final byte[] bytes;

    public UploadBlob(String fileName, String relativePath, byte[] bytes) {
      this.fileName = safe(fileName);
      this.relativePath = safe(relativePath);
      this.bytes = (bytes == null) ? new byte[0] : bytes;
    }
  }

  private static List<UploadBlob> parseUploadPayload(String payload) {
    ArrayList<UploadBlob> out = new ArrayList<UploadBlob>();
    String raw = safe(payload);
    if (raw.isBlank()) return out;
    if (raw.length() > MAX_UPLOAD_PAYLOAD_CHARS) {
      throw new IllegalArgumentException("Upload payload is too large.");
    }

    String[] lines = raw.split("\\r?\\n");
    long totalDecodedBytes = 0L;
    for (int i = 0; i < lines.length; i++) {
      String line = safe(lines[i]).trim();
      if (line.isBlank()) continue;

      String[] parts = line.split("\\|", 3);
      if (parts.length < 3) continue;

      String fileName = fileNameOnly(decodeBase64Utf8(parts[0]));
      String relativePath = normalizeRelativePath(decodeBase64Utf8(parts[1]));
      byte[] bytes = decodeBase64(parts[2]);
      if (fileName.isBlank() || bytes.length == 0) continue;
      if (out.size() >= MAX_IMPORT_ITEMS) {
        throw new IllegalArgumentException("Too many upload items. Please import fewer files at a time.");
      }
      int maxForItem = isZipName(fileName) ? MAX_ZIP_UPLOAD_BYTES : MAX_IMPORT_BYTES;
      if (bytes.length > maxForItem) {
        throw new IllegalArgumentException("Upload item is too large: " + fileName);
      }
      totalDecodedBytes += bytes.length;
      if (totalDecodedBytes > MAX_TOTAL_IMPORT_BYTES) {
        throw new IllegalArgumentException("Total upload size exceeds import limit.");
      }

      if (relativePath.isBlank()) relativePath = fileName;
      out.add(new UploadBlob(fileName, relativePath, bytes));
    }

    return out;
  }

  private static String templateDisplayPath(form_templates.TemplateRec t) {
    if (t == null) return "";
    String folder = safe(t.folderPath).trim();
    String label = safe(t.label).trim();
    if (folder.isBlank()) return label;
    if (label.isBlank()) return folder;
    return folder + "/" + label;
  }
%>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";

  String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  matters matterStore = matters.defaultStore();
  form_templates templateStore = form_templates.defaultStore();

  try {
    matterStore.ensure(tenantUuid);
  } catch (Exception ex) {
    logWarn(application, "Unable to ensure matters store for tenant " + tenantUuid + ": " + shortErrorMessage(ex), ex);
  }
  try {
    templateStore.ensure(tenantUuid);
  } catch (Exception ex) {
    logWarn(application, "Unable to ensure template store for tenant " + tenantUuid + ": " + shortErrorMessage(ex), ex);
  }

  String csrfToken = csrfForRender(request);

  String message = null;
  String error = null;

  String selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
  String selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();

  String action = "";

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    action = safe(request.getParameter("action")).trim();
    selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
    selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();

    if ("import_templates".equalsIgnoreCase(action)) {
      String payload = safe(request.getParameter("upload_payload"));
      String rootFolder = templateStore.normalizeFolderPath(request.getParameter("import_root_folder"));
      try {
        List<UploadBlob> uploads = parseUploadPayload(payload);
        if (uploads.isEmpty()) {
          throw new IllegalArgumentException("Select at least one template file, folder, or zip archive.");
        }

        int imported = 0;
        int skipped = 0;
        long totalImportedBytes = 0L;
        String lastTemplateUuid = selectedTemplateUuid;
        String firstFailure = "";

        for (int i = 0; i < uploads.size(); i++) {
          UploadBlob up = uploads.get(i);
          if (up == null || up.bytes.length == 0) {
            skipped++;
            continue;
          }

          String uploadFileName = fileNameOnly(up.fileName);
          String uploadPath = normalizeRelativePath(up.relativePath);
          if (uploadPath.isBlank()) uploadPath = uploadFileName;
          String uploadFolder = templateStore.normalizeFolderPath(joinFolderPath(rootFolder, parentPath(uploadPath)));

          if (isZipName(uploadFileName)) {
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(up.bytes))) {
              ZipEntry entry;
              int zipEntries = 0;
              long zipExpandedBytes = 0L;
              while ((entry = zis.getNextEntry()) != null) {
                String entryPath = "";
                try {
                  zipEntries++;
                  if (zipEntries > MAX_ZIP_ENTRIES_PER_ARCHIVE) {
                    throw new IllegalArgumentException("Zip contains too many entries.");
                  }
                  if (entry.isDirectory()) continue;
                  entryPath = normalizeRelativePath(entry.getName());
                  String entryFileName = fileNameOnly(entryPath);
                  if (!isImportableTemplateName(entryFileName)) {
                    skipped++;
                    continue;
                  }

                  byte[] entryBytes = readStreamBytes(zis, MAX_IMPORT_BYTES);
                  if (entryBytes.length == 0) {
                    skipped++;
                    continue;
                  }
                  zipExpandedBytes += entryBytes.length;
                  if (zipExpandedBytes > MAX_TOTAL_IMPORT_BYTES) {
                    throw new IllegalArgumentException("Zip expanded size exceeds import limit.");
                  }
                  totalImportedBytes += entryBytes.length;
                  if (totalImportedBytes > MAX_TOTAL_IMPORT_BYTES) {
                    throw new IllegalArgumentException("Total imported content exceeds limit.");
                  }

                  String entryFolder = templateStore.normalizeFolderPath(
                      joinFolderPath(uploadFolder, parentPath(entryPath))
                  );
                  String label = labelFromFileName(entryFileName);
                  form_templates.TemplateRec rec = templateStore.create(
                      tenantUuid,
                      label,
                      entryFolder,
                      entryFileName,
                      entryBytes
                  );
                  imported++;
                  lastTemplateUuid = safe(rec.uuid);
                } catch (Exception exEntry) {
                  skipped++;
                  String target = entryPath.isBlank() ? uploadFileName : uploadFileName + ":" + entryPath;
                  if (firstFailure.isBlank()) {
                    firstFailure = "Failed to import " + target + ": " + shortErrorMessage(exEntry);
                  }
                  logWarn(application, "Failed zip entry import for tenant " + tenantUuid + " from " + target + ": " + shortErrorMessage(exEntry), exEntry);
                } finally {
                  try {
                    zis.closeEntry();
                  } catch (Exception closeEx) {
                    logWarn(application, "Unable to close zip entry during import: " + shortErrorMessage(closeEx), closeEx);
                  }
                }
              }
            } catch (Exception exZip) {
              skipped++;
              if (firstFailure.isBlank()) {
                firstFailure = "Failed to read zip " + uploadFileName + ": " + shortErrorMessage(exZip);
              }
              logWarn(application, "Failed zip import for tenant " + tenantUuid + " from " + uploadFileName + ": " + shortErrorMessage(exZip), exZip);
            }
            continue;
          }

          if (!isImportableTemplateName(uploadFileName)) {
            skipped++;
            continue;
          }
          totalImportedBytes += up.bytes.length;
          if (totalImportedBytes > MAX_TOTAL_IMPORT_BYTES) {
            throw new IllegalArgumentException("Total imported content exceeds limit.");
          }

          try {
            String label = labelFromFileName(uploadFileName);
            form_templates.TemplateRec rec = templateStore.create(
                tenantUuid,
                label,
                uploadFolder,
                uploadFileName,
                up.bytes
            );
            imported++;
            lastTemplateUuid = safe(rec.uuid);
          } catch (Exception exFile) {
            skipped++;
            if (firstFailure.isBlank()) {
              firstFailure = "Failed to import " + uploadFileName + ": " + shortErrorMessage(exFile);
            }
            logWarn(application, "Failed file import for tenant " + tenantUuid + " from " + uploadFileName + ": " + shortErrorMessage(exFile), exFile);
          }
        }

        if (imported <= 0) {
          String base = "No valid templates were imported. Supported types: .docx, .doc, .rtf, .odt, .txt, and .zip containing those files.";
          if (!firstFailure.isBlank()) base = base + " " + firstFailure;
          throw new IllegalArgumentException(base);
        }

        logInfo(application, "Imported " + imported + " template(s) for tenant " + tenantUuid + "; skipped " + skipped + " item(s).");

        response.sendRedirect(
            ctx + "/template_library.jsp?matter_uuid=" + enc(selectedMatterUuid)
            + "&template_uuid=" + enc(lastTemplateUuid)
            + "&imported=" + imported
            + "&skipped=" + skipped
        );
        return;
      } catch (Exception ex) {
        logWarn(application, "Import failed for tenant " + tenantUuid + ": " + shortErrorMessage(ex), ex);
        error = "Unable to import templates: " + safe(ex.getMessage());
      }
    }

    if ("create_template".equalsIgnoreCase(action)) {
      String label = safe(request.getParameter("new_template_label")).trim();
      String folderPath = safe(request.getParameter("new_template_folder")).trim();
      String uploadName = safe(request.getParameter("upload_file_name")).trim();
      byte[] uploadBytes = decodeBase64(request.getParameter("upload_file_b64"));
      try {
        form_templates.TemplateRec rec = templateStore.create(tenantUuid, label, folderPath, uploadName, uploadBytes);
        response.sendRedirect(ctx + "/template_library.jsp?matter_uuid=" + enc(selectedMatterUuid) + "&template_uuid=" + enc(rec.uuid) + "&created=1");
        return;
      } catch (Exception ex) {
        logWarn(application, "Create template failed for tenant " + tenantUuid + ": " + shortErrorMessage(ex), ex);
        error = "Unable to create template: " + safe(ex.getMessage());
      }
    }

    if ("save_template_label".equalsIgnoreCase(action)) {
      String label = safe(request.getParameter("template_label")).trim();
      String folderPath = safe(request.getParameter("template_folder")).trim();
      try {
        boolean ok = templateStore.updateMeta(tenantUuid, selectedTemplateUuid, label, folderPath);
        if (!ok) throw new IllegalStateException("Template not found.");
        response.sendRedirect(ctx + "/template_library.jsp?matter_uuid=" + enc(selectedMatterUuid) + "&template_uuid=" + enc(selectedTemplateUuid) + "&saved=1");
        return;
      } catch (Exception ex) {
        logWarn(application, "Save template metadata failed for tenant " + tenantUuid + ", template " + selectedTemplateUuid + ": " + shortErrorMessage(ex), ex);
        error = "Unable to save template metadata: " + safe(ex.getMessage());
      }
    }

    if ("replace_template_file".equalsIgnoreCase(action)) {
      String uploadName = safe(request.getParameter("replace_file_name")).trim();
      byte[] uploadBytes = decodeBase64(request.getParameter("replace_file_b64"));
      try {
        boolean ok = templateStore.replaceFile(tenantUuid, selectedTemplateUuid, uploadName, uploadBytes);
        if (!ok) throw new IllegalStateException("Template not found.");
        response.sendRedirect(ctx + "/template_library.jsp?matter_uuid=" + enc(selectedMatterUuid) + "&template_uuid=" + enc(selectedTemplateUuid) + "&file_saved=1");
        return;
      } catch (Exception ex) {
        logWarn(application, "Replace template file failed for tenant " + tenantUuid + ", template " + selectedTemplateUuid + ": " + shortErrorMessage(ex), ex);
        error = "Unable to replace template file: " + safe(ex.getMessage());
      }
    }

    if ("delete_template".equalsIgnoreCase(action)) {
      try {
        templateStore.delete(tenantUuid, selectedTemplateUuid);
        response.sendRedirect(ctx + "/template_library.jsp?matter_uuid=" + enc(selectedMatterUuid) + "&deleted=1");
        return;
      } catch (Exception ex) {
        logWarn(application, "Delete template failed for tenant " + tenantUuid + ", template " + selectedTemplateUuid + ": " + shortErrorMessage(ex), ex);
        error = "Unable to delete template: " + safe(ex.getMessage());
      }
    }
  }

  if ("1".equals(request.getParameter("created"))) message = "Template created.";
  int importedCount = intOr(request.getParameter("imported"), 0);
  int skippedCount = intOr(request.getParameter("skipped"), 0);
  if (importedCount > 0 && skippedCount > 0) message = importedCount + " template(s) imported. " + skippedCount + " item(s) skipped.";
  if (importedCount > 0 && skippedCount == 0) message = importedCount + " template(s) imported.";
  if ("1".equals(request.getParameter("saved"))) message = "Template details saved.";
  if ("1".equals(request.getParameter("file_saved"))) message = "Template file replaced.";
  if ("1".equals(request.getParameter("deleted"))) message = "Template deleted.";

  List<matters.MatterRec> allCases = new ArrayList<matters.MatterRec>();
  try {
    allCases = matterStore.listAll(tenantUuid);
  } catch (Exception ex) {
    logWarn(application, "Unable to list matters for tenant " + tenantUuid + ": " + shortErrorMessage(ex), ex);
  }

  List<matters.MatterRec> activeCases = new ArrayList<matters.MatterRec>();
  for (int i = 0; i < allCases.size(); i++) {
    matters.MatterRec c = allCases.get(i);
    if (c == null) continue;
    if (!c.trashed) activeCases.add(c);
  }

  if (selectedMatterUuid.isBlank() && !activeCases.isEmpty()) selectedMatterUuid = safe(activeCases.get(0).uuid);

  List<form_templates.TemplateRec> templates = new ArrayList<form_templates.TemplateRec>();
  try {
    templates = templateStore.list(tenantUuid);
  } catch (Exception ex) {
    logWarn(application, "Unable to list templates for tenant " + tenantUuid + ": " + shortErrorMessage(ex), ex);
  }
  if (selectedTemplateUuid.isBlank() && !templates.isEmpty()) selectedTemplateUuid = safe(templates.get(0).uuid);

  form_templates.TemplateRec selectedTemplate = null;
  for (int i = 0; i < templates.size(); i++) {
    form_templates.TemplateRec t = templates.get(i);
    if (t == null) continue;
    if (safe(t.uuid).trim().equals(selectedTemplateUuid)) {
      selectedTemplate = t;
      break;
    }
  }
  if (selectedTemplate == null && !templates.isEmpty()) {
    selectedTemplate = templates.get(0);
    selectedTemplateUuid = safe(selectedTemplate.uuid);
  }
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Template Library</h1>
  <div class="meta" style="margin-top:4px;">Manage legal document templates used for assembly.</div>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (error != null) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <form class="form" method="get" action="<%= ctx %>/template_library.jsp">
    <div class="grid" style="display:grid; grid-template-columns: 2fr 2fr auto; gap:12px;">
      <label>
        <span>Case Context</span>
        <select name="matter_uuid">
          <% if (activeCases.isEmpty()) { %>
            <option value="">No active cases</option>
          <% } else {
               for (int i = 0; i < activeCases.size(); i++) {
                 matters.MatterRec c = activeCases.get(i);
                 if (c == null) continue;
                 String id = safe(c.uuid);
                 boolean sel = id.equals(safe(selectedMatterUuid));
          %>
            <option value="<%= esc(id) %>" <%= sel ? "selected" : "" %>><%= esc(safe(c.label)) %></option>
          <%   }
             } %>
        </select>
      </label>

      <label>
        <span>Template</span>
        <select name="template_uuid">
          <% if (templates.isEmpty()) { %>
            <option value="">No templates</option>
          <% } else {
               for (int i = 0; i < templates.size(); i++) {
                 form_templates.TemplateRec t = templates.get(i);
                 if (t == null) continue;
                 String id = safe(t.uuid);
                 boolean sel = id.equals(safe(selectedTemplateUuid));
          %>
            <option value="<%= esc(id) %>" <%= sel ? "selected" : "" %>>
              <%= esc(templateDisplayPath(t)) %> (<%= esc(safe(t.fileExt).toUpperCase(Locale.ROOT)) %>)
            </option>
          <%   }
             } %>
        </select>
      </label>

      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit">Load</button>
      </label>
    </div>
  </form>

  <div class="actions" style="display:flex; gap:10px; margin-top:10px; flex-wrap:wrap;">
    <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Back To Form Assembly</a>
    <a class="btn btn-ghost" href="<%= ctx %>/template_editor.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Template Editor</a>
    <a class="btn btn-ghost" href="<%= ctx %>/case_fields.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Case Fields</a>
    <a class="btn btn-ghost" href="<%= ctx %>/token_guide.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Token Guide</a>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <form id="importTemplatesForm" class="form" method="post" action="<%= ctx %>/template_library.jsp">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="import_templates" />
    <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
    <input type="hidden" name="upload_payload" id="import_upload_payload" value="" />

    <label>
      <span>Root Folder Prefix (optional)</span>
      <input type="text" name="import_root_folder" placeholder="Pleadings/Originals" />
    </label>
    <label>
      <span>Template Files (.docx, .doc, .rtf, .odt, .txt, .zip)</span>
      <input type="file" id="import_upload_files" accept=".docx,.doc,.rtf,.odt,.txt,.zip" multiple />
    </label>
    <label>
      <span>Template Folder (includes subfolders)</span>
      <input type="file" id="import_upload_folder" accept=".docx,.doc,.rtf,.odt,.txt,.zip" webkitdirectory directory multiple />
    </label>
    <div class="meta">Select files, folders, or zip archives. Zip archives are extracted server-side.</div>
    <button class="btn" type="submit">Import Templates</button>
  </form>

  <% if (selectedTemplate != null) {
       String templateLabel = safe(selectedTemplate.label);
       String templateFolder = safe(selectedTemplate.folderPath);
       String templateFileName = safe(selectedTemplate.fileName);
       String templateExt = safe(selectedTemplate.fileExt);
  %>
    <hr style="margin:14px 0;" />

    <div class="meta" style="margin-bottom:8px;">
      Template path: <strong><%= esc(templateDisplayPath(selectedTemplate)) %></strong><br />
      Active template file: <strong><%= esc(templateFileName) %></strong>
      <% if (!templateExt.isBlank()) { %>
        (<%= esc(templateExt.toUpperCase(Locale.ROOT)) %>)
      <% } %>
    </div>

    <form class="form" method="post" action="<%= ctx %>/template_library.jsp">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="save_template_label" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />

      <label>
        <span>Template Name</span>
        <input type="text" name="template_label" value="<%= esc(templateLabel) %>" required />
      </label>
      <label>
        <span>Folder Path (optional)</span>
        <input type="text" name="template_folder" value="<%= esc(templateFolder) %>" placeholder="Pleadings/Originals" />
      </label>
      <button class="btn" type="submit">Save Name</button>
    </form>

    <form id="replaceTemplateFileForm" class="form" method="post" action="<%= ctx %>/template_library.jsp" style="margin-top:10px;">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="replace_template_file" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
      <input type="hidden" name="replace_file_name" id="replace_file_name" value="" />
      <input type="hidden" name="replace_file_b64" id="replace_file_b64" value="" />

      <label>
        <span>Replace File</span>
        <input type="file" id="replace_upload_file" accept=".docx,.doc,.rtf,.odt,.txt" required />
      </label>
      <button class="btn btn-ghost" type="submit">Replace File</button>
    </form>

    <form method="post" action="<%= ctx %>/template_library.jsp" style="margin-top:10px;">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="delete_template" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
      <button class="btn btn-ghost" type="submit" onclick="return confirm('Delete this template?');">Delete Template</button>
    </form>
  <% } %>
</section>

<script>
  function toBase64Utf8(value) {
    var text = String(value || "");
    if (window.TextEncoder) {
      var bytes = new TextEncoder().encode(text);
      var binary = "";
      for (var i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
      return btoa(binary);
    }
    var encoded = encodeURIComponent(text).replace(/%([0-9A-F]{2})/g, function (_m, hex) {
      return String.fromCharCode(parseInt(hex, 16));
    });
    return btoa(encoded);
  }

  function readFileAsBase64(file) {
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () {
        var raw = String(reader.result || "");
        var comma = raw.indexOf(",");
        resolve(comma >= 0 ? raw.substring(comma + 1) : raw);
      };
      reader.onerror = function () {
        reject(new Error("Failed to read file."));
      };
      reader.readAsDataURL(file);
    });
  }

  function collectImportFiles(inputIds) {
    var out = [];
    var seen = {};
    for (var i = 0; i < inputIds.length; i++) {
      var input = document.getElementById(inputIds[i]);
      if (!input || !input.files || input.files.length === 0) continue;
      for (var j = 0; j < input.files.length; j++) {
        var f = input.files[j];
        if (!f) continue;

        var relPath = String(f.webkitRelativePath || f.name || "").replace(/\\/g, "/").trim();
        if (!relPath) relPath = f.name || "template";

        var key = relPath + "::" + String(f.size || 0) + "::" + String(f.lastModified || 0);
        if (seen[key]) continue;
        seen[key] = true;

        out.push({ file: f, relativePath: relPath });
      }
    }
    return out;
  }

  function attachImportSubmit(formId, payloadId, inputIds) {
    var form = document.getElementById(formId);
    var payload = document.getElementById(payloadId);
    if (!form || !payload) return;

    form.addEventListener("submit", function (e) {
      if (form.dataset.fileReady === "1") {
        form.dataset.fileReady = "0";
        return;
      }
      if (form.dataset.busy === "1") {
        e.preventDefault();
        return;
      }

      e.preventDefault();
      var rows = collectImportFiles(inputIds || []);
      if (!rows.length) {
        alert("Please choose at least one template file, folder, or zip archive.");
        return;
      }

      form.dataset.busy = "1";
      (async function () {
        try {
          var lines = [];
          for (var i = 0; i < rows.length; i++) {
            var row = rows[i];
            var file = row.file;
            var b64 = await readFileAsBase64(file);
            lines.push(
              toBase64Utf8(file.name || "template")
              + "|"
              + toBase64Utf8(row.relativePath || file.name || "template")
              + "|"
              + b64
            );
          }

          payload.value = lines.join("\n");
          form.dataset.fileReady = "1";
          form.submit();
        } catch (err) {
          alert("Unable to read one or more files for upload.");
        } finally {
          form.dataset.busy = "0";
        }
      })();
    });
  }

  function attachEncodedFileSubmit(formId, inputId, nameId, b64Id) {
    var form = document.getElementById(formId);
    var input = document.getElementById(inputId);
    var nameEl = document.getElementById(nameId);
    var b64El = document.getElementById(b64Id);
    if (!form || !input || !nameEl || !b64El) return;

    form.addEventListener("submit", function (e) {
      if (form.dataset.fileReady === "1") {
        form.dataset.fileReady = "0";
        return;
      }

      var f = input.files && input.files.length > 0 ? input.files[0] : null;
      if (!f) {
        e.preventDefault();
        alert("Please choose a template file.");
        return;
      }

      e.preventDefault();
      var reader = new FileReader();
      reader.onload = function () {
        var raw = String(reader.result || "");
        var comma = raw.indexOf(",");
        var b64 = comma >= 0 ? raw.substring(comma + 1) : raw;

        nameEl.value = f.name || "template";
        b64El.value = b64;

        form.dataset.fileReady = "1";
        form.submit();
      };
      reader.readAsDataURL(f);
    });
  }

  attachImportSubmit("importTemplatesForm", "import_upload_payload", ["import_upload_files", "import_upload_folder"]);
  attachEncodedFileSubmit("replaceTemplateFileForm", "replace_upload_file", "replace_file_name", "replace_file_b64");
</script>

<jsp:include page="footer.jsp" />
