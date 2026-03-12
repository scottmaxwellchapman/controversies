package net.familylawandprobate.controversies;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class webdav_service_test {

    private static final String PASSWORD = "WebDavPass_2026!Strong";

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void authenticates_realm_style_username_and_lists_case_folders() throws Exception {
        TestCtx ctx = createContext();
        matters.MatterRec matter = matters.defaultStore().create(
                ctx.tenantUuid,
                "Alpha Intake",
                "",
                "",
                "",
                "",
                ""
        );

        List<webdav_service.Resource> rows = ctx.service.propfind(ctx.principal, "/", 1);
        assertFalse(rows.isEmpty());
        assertEquals(webdav_service.Kind.ROOT, rows.get(0).kind);

        String expectedCaseSegment = webdav_service.caseSegment(matter);
        boolean found = false;
        for (webdav_service.Resource row : rows) {
            if (row == null || row.kind != webdav_service.Kind.CASE) continue;
            if (!row.canonicalSegments.isEmpty() && expectedCaseSegment.equals(row.canonicalSegments.get(0))) {
                found = true;
                break;
            }
        }
        assertTrue(found);
    }

    @Test
    void document_level_upload_routes_to_orphan_part_when_multiple_parts_exist() throws Exception {
        TestCtx ctx = createContext();

        matters.MatterRec matter = matters.defaultStore().create(ctx.tenantUuid, "Upload Matter", "", "", "", "", "");
        documents.DocumentRec doc = documents.defaultStore().create(
                ctx.tenantUuid,
                matter.uuid,
                "Upload Doc",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                ""
        );

        document_parts.PartRec lead = document_parts.defaultStore().create(
                ctx.tenantUuid,
                matter.uuid,
                doc.uuid,
                "Lead",
                "lead",
                "1",
                "",
                ctx.principal.actor(),
                ""
        );
        document_parts.defaultStore().create(
                ctx.tenantUuid,
                matter.uuid,
                doc.uuid,
                "Exhibits",
                "attachment",
                "2",
                "",
                ctx.principal.actor(),
                ""
        );

        byte[] payload = "orphan-upload".getBytes(StandardCharsets.UTF_8);
        String path = "/" + webdav_service.caseSegment(matter)
                + "/" + webdav_service.documentSegment(doc)
                + "/intake-note.txt";

        webdav_service.Resource created = ctx.service.put(
                ctx.principal,
                path,
                "text/plain",
                new ByteArrayInputStream(payload)
        );
        assertNotNull(created);
        assertEquals(webdav_service.Kind.FILE, created.kind);

        List<document_parts.PartRec> parts = document_parts.defaultStore().listAll(ctx.tenantUuid, matter.uuid, doc.uuid);
        document_parts.PartRec orphan = null;
        for (document_parts.PartRec part : parts) {
            if (part == null || part.trashed) continue;
            if ("orphan uploads".equals(safe(part.label).trim().toLowerCase(Locale.ROOT))) {
                orphan = part;
                break;
            }
        }

        assertNotNull(orphan);

        List<part_versions.VersionRec> orphanVersions = part_versions.defaultStore().listAll(
                ctx.tenantUuid,
                matter.uuid,
                doc.uuid,
                orphan.uuid
        );
        assertEquals(1, orphanVersions.size());

        List<part_versions.VersionRec> leadVersions = part_versions.defaultStore().listAll(
                ctx.tenantUuid,
                matter.uuid,
                doc.uuid,
                lead.uuid
        );
        assertEquals(0, leadVersions.size());
    }

    @Test
    void part_uploads_create_current_alias_and_multiple_versions() throws Exception {
        TestCtx ctx = createContext();

        matters.MatterRec matter = matters.defaultStore().create(ctx.tenantUuid, "Version Matter", "", "", "", "", "");
        documents.DocumentRec doc = documents.defaultStore().create(
                ctx.tenantUuid,
                matter.uuid,
                "Version Doc",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                ""
        );
        document_parts.PartRec part = document_parts.defaultStore().create(
                ctx.tenantUuid,
                matter.uuid,
                doc.uuid,
                "Main Draft",
                "lead",
                "1",
                "",
                ctx.principal.actor(),
                ""
        );

        byte[] first = "draft-v1".getBytes(StandardCharsets.UTF_8);
        byte[] second = "draft-v2-updated".getBytes(StandardCharsets.UTF_8);

        String partPath = "/" + webdav_service.caseSegment(matter)
                + "/" + webdav_service.documentSegment(doc)
                + "/" + webdav_service.partSegment(part);

        ctx.service.put(ctx.principal, partPath + "/draft.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new ByteArrayInputStream(first));
        ctx.service.put(ctx.principal, partPath + "/draft.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new ByteArrayInputStream(second));

        List<part_versions.VersionRec> versions = part_versions.defaultStore().listAll(ctx.tenantUuid, matter.uuid, doc.uuid, part.uuid);
        assertEquals(2, versions.size());

        int currentCount = 0;
        for (part_versions.VersionRec rec : versions) {
            if (rec != null && rec.current) currentCount++;
        }
        assertEquals(1, currentCount);

        List<webdav_service.Resource> rows = ctx.service.propfind(ctx.principal, partPath, 1);
        long fileCount = rows.stream().filter(r -> r != null && r.kind == webdav_service.Kind.FILE).count();
        long currentAliasCount = rows.stream().filter(r -> r != null && r.currentAlias).count();

        assertEquals(3L, fileCount);
        assertEquals(1L, currentAliasCount);

        webdav_service.Resource currentAlias = rows.stream()
                .filter(r -> r != null && r.currentAlias)
                .findFirst()
                .orElseThrow();

        String aliasPath = "/" + String.join("/", currentAlias.canonicalSegments);
        webdav_service.Resource opened = ctx.service.fileForGet(ctx.principal, aliasPath);
        assertNotNull(opened.storagePath);
        assertTrue(Files.isRegularFile(opened.storagePath));
        assertEquals(second.length, Files.readAllBytes(opened.storagePath).length);
    }

    @Test
    void mkcol_creates_case_document_and_part_hierarchy() throws Exception {
        TestCtx ctx = createContext();

        webdav_service.Resource createdCase = ctx.service.mkcol(ctx.principal, "/WebDAV Intake Case");
        assertEquals(webdav_service.Kind.CASE, createdCase.kind);
        assertNotNull(createdCase.matter);

        String casePath = "/" + createdCase.canonicalSegments.get(0);
        webdav_service.Resource createdDoc = ctx.service.mkcol(ctx.principal, casePath + "/Pleadings");
        assertEquals(webdav_service.Kind.DOCUMENT, createdDoc.kind);
        assertNotNull(createdDoc.document);

        String docPath = casePath + "/" + createdDoc.canonicalSegments.get(1);
        webdav_service.Resource createdPart = ctx.service.mkcol(ctx.principal, docPath + "/Exhibit Bundle");
        assertEquals(webdav_service.Kind.PART, createdPart.kind);
        assertNotNull(createdPart.part);

        documents.DocumentRec loadedDoc = documents.defaultStore().get(
                ctx.tenantUuid,
                createdCase.matter.uuid,
                createdDoc.document.uuid
        );
        assertNotNull(loadedDoc);

        document_parts.PartRec loadedPart = document_parts.defaultStore().get(
                ctx.tenantUuid,
                createdCase.matter.uuid,
                createdDoc.document.uuid,
                createdPart.part.uuid
        );
        assertNotNull(loadedPart);
    }

    @Test
    void rejects_username_without_tenant_slug_prefix() throws Exception {
        TestCtx ctx = createContext();

        webdav_service.WebDavException ex = assertThrows(
                webdav_service.WebDavException.class,
                () -> ctx.service.authenticate(basicHeader("missing-prefix", PASSWORD))
        );

        assertEquals(401, ex.status);
    }

    private TestCtx createContext() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tenantLabel = "WebDAV Tenant " + suffix;
        String email = "webdav_" + suffix + "@example.com";

        String tenantUuid = tenants.defaultStore().create(tenantLabel, PASSWORD.toCharArray());
        cleanupRoots.add(Paths.get("data", "tenants", tenantUuid).toAbsolutePath());

        users_roles users = users_roles.defaultStore();
        users.ensure(tenantUuid);

        users_roles.RoleRec role = users.createRole(tenantUuid, "WebDAV Role " + suffix, true);
        users.setRolePermission(tenantUuid, role.uuid, "documents.access", "true");
        users.createUser(tenantUuid, email, role.uuid, true, PASSWORD.toCharArray(), true);

        webdav_service service = webdav_service.defaultService();
        String realmUser = webdav_service.tenantSlug(tenantLabel) + "\\" + email;
        webdav_service.Principal principal = service.authenticate(basicHeader(realmUser, PASSWORD));

        return new TestCtx(tenantUuid, service, principal);
    }

    private static String basicHeader(String username, String password) {
        String pair = safe(username) + ":" + safe(password);
        String token = Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static final class TestCtx {
        final String tenantUuid;
        final webdav_service service;
        final webdav_service.Principal principal;

        TestCtx(String tenantUuid, webdav_service service, webdav_service.Principal principal) {
            this.tenantUuid = tenantUuid;
            this.service = service;
            this.principal = principal;
        }
    }
}
