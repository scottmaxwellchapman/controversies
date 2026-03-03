package net.familylawandprobate.controversies;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class api_facts_extensions_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void api_supports_facts_hierarchy_links_and_report_refresh() throws Exception {
        String tenant = "tenant-api-facts-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        matters matterStore = matters.defaultStore();
        matterStore.ensure(tenant);
        matters.MatterRec matter = matterStore.create(
                tenant,
                "API Facts Matter",
                "",
                "",
                "",
                "",
                ""
        );

        documents docs = documents.defaultStore();
        document_parts parts = document_parts.defaultStore();
        part_versions versions = part_versions.defaultStore();

        documents.DocumentRec doc = docs.create(
                tenant,
                matter.uuid,
                "API Evidence",
                "evidence",
                "exhibit",
                "draft",
                "owner",
                "work_product",
                "",
                "",
                ""
        );
        document_parts.PartRec part = parts.create(
                tenant,
                matter.uuid,
                doc.uuid,
                "Evidence Part",
                "lead",
                "1",
                "internal",
                "owner",
                ""
        );
        Path sourcePath = parts.partFolder(tenant, matter.uuid, doc.uuid, part.uuid)
                .resolve("version_files")
                .resolve("api_source.pdf")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(sourcePath.getParent());
        Files.write(sourcePath, "api-source".getBytes(StandardCharsets.UTF_8));
        part_versions.VersionRec version = versions.create(
                tenant,
                matter.uuid,
                doc.uuid,
                part.uuid,
                "v1",
                "uploaded",
                "application/pdf",
                "abc123",
                String.valueOf(Files.size(sourcePath)),
                sourcePath.toString(),
                "owner",
                "",
                true
        );

        LinkedHashMap<String, Object> claimCreated = invokeApi(
                "facts.claims.create",
                Map.of(
                        "matter_uuid", matter.uuid,
                        "title", "Negligence",
                        "summary", "Duty and breach evaluation",
                        "sort_order", 10,
                        "actor_user_uuid", "tester"
                ),
                tenant
        );
        String claimUuid = nestedString(claimCreated, "claim", "claim_uuid");
        assertFalse(claimUuid.isBlank());

        LinkedHashMap<String, Object> elementCreated = invokeApi(
                "facts.elements.create",
                Map.of(
                        "matter_uuid", matter.uuid,
                        "claim_uuid", claimUuid,
                        "title", "Breach of duty",
                        "notes", "Standard of care analysis",
                        "sort_order", 10,
                        "actor_user_uuid", "tester"
                ),
                tenant
        );
        String elementUuid = nestedString(elementCreated, "element", "element_uuid");
        assertFalse(elementUuid.isBlank());

        LinkedHashMap<String, Object> factParams = new LinkedHashMap<String, Object>();
        factParams.put("matter_uuid", matter.uuid);
        factParams.put("claim_uuid", claimUuid);
        factParams.put("element_uuid", elementUuid);
        factParams.put("summary", "Driver crossed center line");
        factParams.put("detail", "Police report and photo evidence confirm lane invasion.");
        factParams.put("internal_notes", "Use in opening chronology.");
        factParams.put("status", "corroborated");
        factParams.put("strength", "high");
        factParams.put("document_uuid", doc.uuid);
        factParams.put("part_uuid", part.uuid);
        factParams.put("version_uuid", version.uuid);
        factParams.put("page_number", Integer.valueOf(2));
        factParams.put("sort_order", Integer.valueOf(10));
        factParams.put("actor_user_uuid", "tester");
        LinkedHashMap<String, Object> factCreated = invokeApi("facts.facts.create", factParams, tenant);
        String factUuid = nestedString(factCreated, "fact", "fact_uuid");
        assertFalse(factUuid.isBlank());
        assertEquals(doc.uuid, nestedString(factCreated, "fact", "document_uuid"));

        LinkedHashMap<String, Object> tree = invokeApi(
                "facts.tree.get",
                Map.of("matter_uuid", matter.uuid, "include_trashed", false),
                tenant
        );
        assertEquals(1, asInt(tree.get("claim_count")));
        assertEquals(1, asInt(tree.get("element_count")));
        assertEquals(1, asInt(tree.get("fact_count")));

        LinkedHashMap<String, Object> relinked = invokeApi(
                "facts.facts.link_document",
                Map.of(
                        "matter_uuid", matter.uuid,
                        "fact_uuid", factUuid,
                        "document_uuid", doc.uuid,
                        "part_uuid", part.uuid,
                        "version_uuid", version.uuid,
                        "page_number", 3,
                        "actor_user_uuid", "tester"
                ),
                tenant
        );
        assertTrue(Boolean.parseBoolean(String.valueOf(relinked.get("updated"))));
        assertEquals(3, asInt(nestedObject(relinked, "fact", "page_number")));

        LinkedHashMap<String, Object> report = invokeApi(
                "facts.report.refresh",
                Map.of("matter_uuid", matter.uuid, "actor_user_uuid", "tester"),
                tenant
        );
        assertFalse(nestedString(report, "report", "report_document_uuid").isBlank());
        assertFalse(nestedString(report, "report", "report_part_uuid").isBlank());
        assertFalse(nestedString(report, "report", "last_report_version_uuid").isBlank());
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, Object> invokeApi(String operation, Map<String, Object> params, String tenantUuid) throws Exception {
        Method m = api_servlet.class.getDeclaredMethod(
                "executeOperation",
                String.class,
                Map.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class
        );
        m.setAccessible(true);
        try {
            Object out = m.invoke(null, operation, params, tenantUuid, "cred-1", "Credential", "127.0.0.1", "POST");
            if (out instanceof LinkedHashMap<?, ?> map) return (LinkedHashMap<String, Object>) map;
            return new LinkedHashMap<String, Object>();
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception e) throw e;
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object nestedObject(Map<String, Object> root, String key, String nestedKey) {
        if (root == null) return "";
        Object v = root.get(key);
        if (!(v instanceof Map<?, ?> map)) return "";
        return map.containsKey(nestedKey) ? map.get(nestedKey) : "";
    }

    private static String nestedString(Map<String, Object> root, String key, String nestedKey) {
        return safe(String.valueOf(nestedObject(root, key, nestedKey)));
    }

    private static int asInt(Object raw) {
        if (raw instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(p -> {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
            }
        });
    }
}
