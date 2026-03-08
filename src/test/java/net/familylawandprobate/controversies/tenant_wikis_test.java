package net.familylawandprobate.controversies;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class tenant_wikis_test {

    @Test
    void creates_pages_tracks_revisions_and_generates_diff() throws Exception {
        String tenantUuid = "wiki-test-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            tenant_wikis store = tenant_wikis.defaultStore();
            tenant_wikis.PageRec page = store.createPage(
                    tenantUuid,
                    "Firm Policies",
                    "",
                    "",
                    "",
                    "admin@example.test",
                    "Initial draft",
                    "<p>Policy v1</p>"
            );
            assertNotNull(page);
            assertFalse(page.uuid.isBlank());

            tenant_wikis.RevisionRec rev2 = store.saveRevision(
                    tenantUuid,
                    page.uuid,
                    "<p>Policy v2</p><script>alert('x')</script>",
                    "Updated policy text",
                    "editor@example.test",
                    "user-123"
            );
            assertNotNull(rev2);

            List<tenant_wikis.RevisionRec> revisions = store.listRevisions(tenantUuid, page.uuid);
            assertEquals(2, revisions.size());
            assertTrue(revisions.get(0).current);

            String currentHtml = store.readCurrentHtml(tenantUuid, page.uuid);
            assertFalse(currentHtml.contains("<script"));

            tenant_wikis.DiffResult diff = store.diffRevisions(
                    tenantUuid,
                    page.uuid,
                    revisions.get(1).uuid,
                    revisions.get(0).uuid
            );
            assertNotNull(diff);
            assertTrue(diff.added > 0 || diff.removed > 0);
            assertFalse(diff.lines.isEmpty());
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    @Test
    void stores_and_resolves_attachments() throws Exception {
        String tenantUuid = "wiki-attach-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            tenant_wikis store = tenant_wikis.defaultStore();
            tenant_wikis.PageRec page = store.createPage(
                    tenantUuid,
                    "Knowledge Base",
                    "",
                    "",
                    "",
                    "admin@example.test",
                    "seed",
                    "<p>Root</p>"
            );
            assertNotNull(page);

            byte[] bytes = "attachment data".getBytes(StandardCharsets.UTF_8);
            tenant_wikis.AttachmentRec rec = store.saveAttachment(
                    tenantUuid,
                    page.uuid,
                    "sample file-(v1).txt",
                    "text/plain",
                    bytes,
                    "editor@example.test"
            );
            assertNotNull(rec);
            assertFalse(rec.uuid.isBlank());
            assertTrue(rec.fileName.matches("[A-Za-z0-9._]+"));
            assertFalse(rec.fileName.contains("-"));
            assertFalse(rec.fileName.contains(" "));
            assertFalse(rec.fileName.contains("("));
            assertFalse(rec.fileName.contains(")"));

            List<tenant_wikis.AttachmentRec> attachments = store.listAttachments(tenantUuid, page.uuid);
            assertEquals(1, attachments.size());

            Path attachmentPath = store.resolveAttachmentPath(tenantUuid, page.uuid, rec.uuid);
            assertNotNull(attachmentPath);
            assertTrue(Files.exists(attachmentPath));
            assertEquals("attachment data", Files.readString(attachmentPath, StandardCharsets.UTF_8));
            String storedFileName = attachmentPath.getFileName() == null ? "" : attachmentPath.getFileName().toString();
            assertTrue(storedFileName.matches("[A-Za-z0-9._]+"));
            assertFalse(storedFileName.contains("-"));
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    private static void deleteQuietly(Path root) {
        try {
            if (root == null || !Files.exists(root)) return;
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }
}
