package net.familylawandprobate.controversies;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class document_taxonomy {

    public static final class Taxonomy {
        public final Set<String> categories = new LinkedHashSet<String>();
        public final Set<String> subcategories = new LinkedHashSet<String>();
        public final Set<String> statuses = new LinkedHashSet<String>();
    }

    public static document_taxonomy defaultStore() { return new document_taxonomy(); }

    public void ensure(String tenantUuid) throws Exception {
        String tu = document_workflow_support.safe(tenantUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        Path p = taxonomyPath(tu);
        Files.createDirectories(p.getParent());
        if (!Files.exists(p)) {
            String now = app_clock.now().toString();
            document_workflow_support.writeAtomic(p,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                  + "<document-taxonomy created=\"" + document_workflow_support.xmlText(now)
                  + "\" updated=\"" + document_workflow_support.xmlText(now) + "\">\n"
                  + "  <categories></categories>\n"
                  + "  <subcategories></subcategories>\n"
                  + "  <statuses></statuses>\n"
                  + "</document-taxonomy>\n");
        }
    }

    public Taxonomy read(String tenantUuid) throws Exception {
        String tu = document_workflow_support.safe(tenantUuid).trim();
        Taxonomy out = new Taxonomy();
        if (tu.isBlank()) return out;
        ensure(tu);
        Document doc = document_workflow_support.parseXml(taxonomyPath(tu));
        Element root = doc == null ? null : doc.getDocumentElement();
        if (root == null) return out;
        fillValues(root, "categories", out.categories);
        fillValues(root, "subcategories", out.subcategories);
        fillValues(root, "statuses", out.statuses);
        return out;
    }

    public void addValues(String tenantUuid, List<String> categories, List<String> subcategories, List<String> statuses) throws Exception {
        Taxonomy tx = read(tenantUuid);
        addAll(tx.categories, categories);
        addAll(tx.subcategories, subcategories);
        addAll(tx.statuses, statuses);
        write(tenantUuid, tx);
    }

    private void write(String tenantUuid, Taxonomy tx) throws Exception {
        String tu = document_workflow_support.safe(tenantUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        String now = app_clock.now().toString();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<document-taxonomy updated=\"").append(document_workflow_support.xmlText(now)).append("\">\n");
        writeBlock(sb, "categories", tx.categories);
        writeBlock(sb, "subcategories", tx.subcategories);
        writeBlock(sb, "statuses", tx.statuses);
        sb.append("</document-taxonomy>\n");
        document_workflow_support.writeAtomic(taxonomyPath(tu), sb.toString());
    }

    private static void writeBlock(StringBuilder sb, String tag, Set<String> values) {
        sb.append("  <").append(tag).append(">\n");
        for (String value : values) {
            String token = document_workflow_support.normalizeToken(value);
            if (token.isBlank()) continue;
            sb.append("    <value>").append(document_workflow_support.xmlText(token)).append("</value>\n");
        }
        sb.append("  </").append(tag).append(">\n");
    }

    private static void addAll(Set<String> target, List<String> values) {
        if (values == null) return;
        for (String value : values) {
            String token = document_workflow_support.normalizeToken(value);
            if (!token.isBlank()) target.add(token);
        }
    }

    private static void fillValues(Element root, String parentTag, Set<String> out) {
        NodeList roots = root.getElementsByTagName(parentTag);
        if (roots == null || roots.getLength() == 0) return;
        Node valuesRoot = roots.item(0);
        if (!(valuesRoot instanceof Element)) return;
        NodeList values = ((Element) valuesRoot).getElementsByTagName("value");
        for (int i = 0; i < values.getLength(); i++) {
            Node n = values.item(i);
            if (!(n instanceof Element)) continue;
            String v = document_workflow_support.normalizeToken(n.getTextContent());
            if (!v.isBlank()) out.add(v);
        }
    }

    private static Path taxonomyPath(String tenantUuid) {
        return Paths.get("data", "tenants", tenantUuid, "document-taxonomy.xml").toAbsolutePath();
    }
}
