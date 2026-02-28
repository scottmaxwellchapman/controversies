package net.familylawandprobate.controversies;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * case_list_items
 *
 * Case-scoped XML store for repeatable list/table datasets used by template directives.
 * File: data/tenants/{tenantUuid}/matters/{matterUuid}/list-items.xml
 */
public final class case_list_items {

    public static case_list_items defaultStore() {
        return new case_list_items();
    }

    public Map<String, String> read(String tenantUuid, String matterUuid) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        try {
            Path p = xmlPath(tenantUuid, matterUuid);
            if (p == null || !Files.exists(p)) return out;

            String raw = Files.readString(p, StandardCharsets.UTF_8);
            if (raw == null || raw.trim().isEmpty()) return out;
            Document doc = parseXml(raw);
            if (doc == null) return out;

            Element root = doc.getDocumentElement();
            if (root == null) return out;
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (!(n instanceof Element)) continue;
                Element listEl = (Element) n;
                if (!"list".equalsIgnoreCase(listEl.getTagName())) continue;

                String key = normalizeKey(listEl.getAttribute("key"));
                if (key.isBlank()) key = normalizeKey(listEl.getAttribute("name"));
                if (key.isBlank()) continue;

                out.put(key, toXmlString(listEl));
            }
            return out;
        } catch (Exception ignored) {
            return out;
        }
    }

    public void write(String tenantUuid, String matterUuid, Map<String, String> listXmlByKey) throws Exception {
        Path p = xmlPath(tenantUuid, matterUuid);
        if (p == null) throw new IllegalArgumentException("tenantUuid and matterUuid are required");

        Document doc = newSecureBuilder().newDocument();
        Element root = doc.createElement("case-list-items");
        doc.appendChild(root);

        if (listXmlByKey != null) {
            for (Map.Entry<String, String> e : listXmlByKey.entrySet()) {
                if (e == null) continue;
                String key = normalizeKey(e.getKey());
                String rawXml = safe(e.getValue()).trim();
                if (key.isBlank() || rawXml.isBlank()) continue;

                Element listEl = doc.createElement("list");
                listEl.setAttribute("key", key);

                Document listDoc = looksLikeXml(rawXml) ? parseXml(rawXml) : null;
                if (listDoc != null && listDoc.getDocumentElement() != null) {
                    Node imported = doc.importNode(listDoc.getDocumentElement(), true);
                    listEl.appendChild(imported);
                } else {
                    Element items = doc.createElement("items");
                    String[] parts = rawXml.split("\\r?\\n|\\|");
                    if (parts.length <= 1) parts = rawXml.split(",");
                    for (String part : parts) {
                        String v = safe(part).trim();
                        if (v.isBlank()) continue;
                        Element item = doc.createElement("item");
                        item.setTextContent(v);
                        items.appendChild(item);
                    }
                    listEl.appendChild(items);
                }
                root.appendChild(listEl);
            }
        }

        Files.createDirectories(p.getParent());
        Files.writeString(
                p,
                toXmlString(doc.getDocumentElement()),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    public String normalizeKey(String key) {
        String k = safe(key).trim().toLowerCase(Locale.ROOT);
        if (k.isBlank()) return "";
        StringBuilder sb = new StringBuilder(k.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < k.length(); i++) {
            char ch = k.charAt(i);
            boolean ok =
                    (ch >= 'a' && ch <= 'z') ||
                    (ch >= '0' && ch <= '9') ||
                    ch == '_' || ch == '-' || ch == '.';
            if (ok) {
                sb.append(ch);
                lastUnderscore = false;
            } else {
                if (!lastUnderscore && sb.length() > 0) {
                    sb.append('_');
                    lastUnderscore = true;
                }
            }
        }
        String out = sb.toString();
        while (out.startsWith("_")) out = out.substring(1);
        while (out.endsWith("_")) out = out.substring(0, out.length() - 1);
        if (out.length() > 80) out = out.substring(0, 80);
        return out;
    }

    private static Path xmlPath(String tenantUuid, String matterUuid) {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        if (tu.isBlank() || mu.isBlank()) return null;
        return Paths.get("data", "tenants", tu, "matters", mu, "list-items.xml").toAbsolutePath();
    }

    private static DocumentBuilder newSecureBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }

    private static Document parseXml(String xml) {
        try {
            DocumentBuilder b = newSecureBuilder();
            InputSource src = new InputSource(new StringReader(safe(xml)));
            return b.parse(src);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean looksLikeXml(String raw) {
        String s = safe(raw).trim();
        return s.startsWith("<") && s.endsWith(">") && s.contains("</");
    }

    private static String toXmlString(Node node) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty(OutputKeys.INDENT, "no");

            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(node), new StreamResult(sw));
            return safe(sw.toString());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String safeFileToken(String s) {
        String t = safe(s).trim();
        if (t.isBlank()) return "";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
