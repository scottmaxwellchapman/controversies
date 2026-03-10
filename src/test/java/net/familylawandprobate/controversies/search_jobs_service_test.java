package net.familylawandprobate.controversies;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class search_jobs_service_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void queues_and_completes_metadata_search_job() throws Exception {
        String tenant = "tenant-search-meta-" + UUID.randomUUID();
        String requestedBy = "search-meta-" + UUID.randomUUID() + "@test.local";
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());
        matters.MatterRec matterRec = matters.defaultStore().create(tenant, "Meta Search Matter", "", "", "", "", "");
        String matter = matterRec.uuid;

        documents.DocumentRec doc = documents.defaultStore().create(
                tenant,
                matter,
                "Motion To Compel Discovery",
                "pleading",
                "motion",
                "draft",
                "Tester",
                "work_product",
                "",
                "",
                ""
        );
        document_parts.PartRec part = document_parts.defaultStore().create(
                tenant,
                matter,
                doc.uuid,
                "Main Part",
                "lead",
                "1",
                "",
                "Tester",
                ""
        );

        Path partFolder = document_parts.defaultStore().partFolder(tenant, matter, doc.uuid, part.uuid);
        Files.createDirectories(partFolder.resolve("version_files"));
        Path versionPath = partFolder.resolve("version_files").resolve("meta-search.txt");
        Files.writeString(versionPath, "No OCR needed for metadata test.", StandardCharsets.UTF_8);

        part_versions.defaultStore().create(
                tenant,
                matter,
                doc.uuid,
                part.uuid,
                "Primary Draft",
                "uploaded",
                "text/plain",
                "",
                String.valueOf(Files.size(versionPath)),
                versionPath.toUri().toString(),
                "Tester",
                "metadata searchable",
                true
        );

        search_jobs_service.SearchJobRequest in = new search_jobs_service.SearchJobRequest();
        in.tenantUuid = tenant;
        in.requestedBy = requestedBy;
        in.searchType = "document_part_versions";
        in.query = "primary draft";
        in.operator = "contains";
        in.caseSensitive = false;
        in.includeMetadata = true;
        in.includeOcr = false;
        in.maxResults = 50;

        String jobId = search_jobs_service.defaultService().enqueue(in);
        search_jobs_service.SearchJobSnapshot done = waitForJob(tenant, requestedBy, jobId, 15_000L);
        assertNotNull(done);
        assertEquals("completed", done.status);
        assertFalse(done.results.isEmpty());
        assertTrue(done.results.get(0).matchedIn.contains("metadata"));
    }

    @Test
    void case_sensitive_search_respects_query_casing() throws Exception {
        String tenant = "tenant-search-case-" + UUID.randomUUID();
        String requestedBy = "search-case-" + UUID.randomUUID() + "@test.local";
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());
        matters.MatterRec matterRec = matters.defaultStore().create(tenant, "Case Search Matter", "", "", "", "", "");
        String matter = matterRec.uuid;

        documents.DocumentRec doc = documents.defaultStore().create(
                tenant,
                matter,
                "OCR Search Doc",
                "pleading",
                "motion",
                "draft",
                "Tester",
                "work_product",
                "",
                "",
                ""
        );
        document_parts.PartRec part = document_parts.defaultStore().create(
                tenant,
                matter,
                doc.uuid,
                "Main Part",
                "lead",
                "1",
                "",
                "Tester",
                ""
        );

        Path partFolder = document_parts.defaultStore().partFolder(tenant, matter, doc.uuid, part.uuid);
        Files.createDirectories(partFolder.resolve("version_files"));
        Path versionPath = partFolder.resolve("version_files").resolve("ocr-source.txt");
        Files.writeString(versionPath, "Exact OCR Token", StandardCharsets.UTF_8);

        part_versions.VersionRec version = part_versions.defaultStore().create(
                tenant,
                matter,
                doc.uuid,
                part.uuid,
                "OCR Candidate",
                "uploaded",
                "text/plain",
                "",
                String.valueOf(Files.size(versionPath)),
                versionPath.toUri().toString(),
                "Tester",
                "ocr searchable",
                true
        );
        assertNotNull(version);

        search_jobs_service.SearchJobRequest insensitive = new search_jobs_service.SearchJobRequest();
        insensitive.tenantUuid = tenant;
        insensitive.requestedBy = requestedBy;
        insensitive.searchType = "document_part_versions";
        insensitive.query = "exact ocr token";
        insensitive.operator = "contains";
        insensitive.caseSensitive = false;
        insensitive.includeMetadata = false;
        insensitive.includeOcr = true;
        insensitive.maxResults = 50;

        String insensitiveJob = search_jobs_service.defaultService().enqueue(insensitive);
        search_jobs_service.SearchJobSnapshot insensitiveDone = waitForJob(tenant, requestedBy, insensitiveJob, 20_000L);
        assertNotNull(insensitiveDone);
        assertEquals("completed", insensitiveDone.status);
        assertFalse(insensitiveDone.results.isEmpty());

        search_jobs_service.SearchJobRequest sensitive = new search_jobs_service.SearchJobRequest();
        sensitive.tenantUuid = tenant;
        sensitive.requestedBy = requestedBy;
        sensitive.searchType = "document_part_versions";
        sensitive.query = "exact ocr token";
        sensitive.operator = "contains";
        sensitive.caseSensitive = true;
        sensitive.includeMetadata = false;
        sensitive.includeOcr = true;
        sensitive.maxResults = 50;

        String sensitiveJob = search_jobs_service.defaultService().enqueue(sensitive);
        search_jobs_service.SearchJobSnapshot sensitiveDone = waitForJob(tenant, requestedBy, sensitiveJob, 20_000L);
        assertNotNull(sensitiveDone);
        assertEquals("completed", sensitiveDone.status);
        assertEquals(0, sensitiveDone.results.size());

        Path companionPath = version_ocr_companion_service.defaultService().companionPath(
                tenant,
                matter,
                doc.uuid,
                part.uuid,
                version.uuid
        );
        assertTrue(Files.isRegularFile(companionPath));
    }

    @Test
    void supports_multi_criteria_and_or_logic() throws Exception {
        String tenant = "tenant-search-logic-" + UUID.randomUUID();
        String requestedBy = "search-logic-" + UUID.randomUUID() + "@test.local";
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());
        matters.MatterRec matterRec = matters.defaultStore().create(tenant, "Logic Matter", "", "", "", "", "");
        String matter = matterRec.uuid;

        documents.DocumentRec doc = documents.defaultStore().create(
                tenant,
                matter,
                "Multi Criteria Document",
                "pleading",
                "motion",
                "draft",
                "Tester",
                "work_product",
                "",
                "",
                ""
        );
        document_parts.PartRec part = document_parts.defaultStore().create(
                tenant,
                matter,
                doc.uuid,
                "Lead Part",
                "lead",
                "1",
                "",
                "Tester",
                ""
        );
        Path partFolder = document_parts.defaultStore().partFolder(tenant, matter, doc.uuid, part.uuid);
        Files.createDirectories(partFolder.resolve("version_files"));
        Path versionPath = partFolder.resolve("version_files").resolve("criteria.txt");
        Files.writeString(versionPath, "Alpha clause and Beta section", StandardCharsets.UTF_8);
        part_versions.defaultStore().create(
                tenant,
                matter,
                doc.uuid,
                part.uuid,
                "Alpha Version",
                "uploaded",
                "text/plain",
                "",
                String.valueOf(Files.size(versionPath)),
                versionPath.toUri().toString(),
                "Tester",
                "",
                true
        );

        search_jobs_service.SearchJobRequest andReq = new search_jobs_service.SearchJobRequest();
        andReq.tenantUuid = tenant;
        andReq.requestedBy = requestedBy;
        andReq.searchType = "document_part_versions";
        andReq.logic = "and";
        andReq.includeMetadata = true;
        andReq.includeOcr = true;
        andReq.caseSensitive = false;
        andReq.maxResults = 50;
        andReq.criteria = new ArrayList<search_jobs_service.SearchCriterion>();
        search_jobs_service.SearchCriterion c1 = new search_jobs_service.SearchCriterion();
        c1.scope = "any";
        c1.operator = "contains";
        c1.query = "alpha";
        search_jobs_service.SearchCriterion c2 = new search_jobs_service.SearchCriterion();
        c2.scope = "any";
        c2.operator = "contains";
        c2.query = "beta";
        andReq.criteria.add(c1);
        andReq.criteria.add(c2);

        String andJob = search_jobs_service.defaultService().enqueue(andReq);
        search_jobs_service.SearchJobSnapshot andDone = waitForJob(tenant, requestedBy, andJob, 20_000L);
        assertNotNull(andDone);
        assertEquals("completed", andDone.status);
        assertFalse(andDone.results.isEmpty());

        search_jobs_service.SearchJobRequest orReq = new search_jobs_service.SearchJobRequest();
        orReq.tenantUuid = tenant;
        orReq.requestedBy = requestedBy;
        orReq.searchType = "document_part_versions";
        orReq.logic = "or";
        orReq.includeMetadata = true;
        orReq.includeOcr = true;
        orReq.caseSensitive = false;
        orReq.maxResults = 50;
        orReq.criteria = new ArrayList<search_jobs_service.SearchCriterion>();
        search_jobs_service.SearchCriterion c3 = new search_jobs_service.SearchCriterion();
        c3.scope = "any";
        c3.operator = "contains";
        c3.query = "gamma";
        search_jobs_service.SearchCriterion c4 = new search_jobs_service.SearchCriterion();
        c4.scope = "any";
        c4.operator = "contains";
        c4.query = "alpha";
        orReq.criteria.add(c3);
        orReq.criteria.add(c4);

        String orJob = search_jobs_service.defaultService().enqueue(orReq);
        search_jobs_service.SearchJobSnapshot orDone = waitForJob(tenant, requestedBy, orJob, 20_000L);
        assertNotNull(orDone);
        assertEquals("completed", orDone.status);
        assertFalse(orDone.results.isEmpty());
    }

    @Test
    void document_search_supports_docx_text_extraction() throws Exception {
        String tenant = "tenant-search-docx-" + UUID.randomUUID();
        String requestedBy = "search-docx-" + UUID.randomUUID() + "@test.local";
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());
        matters.MatterRec matterRec = matters.defaultStore().create(tenant, "DOCX Search Matter", "", "", "", "", "");
        String matter = matterRec.uuid;

        documents.DocumentRec doc = documents.defaultStore().create(
                tenant,
                matter,
                "DOCX Search Doc",
                "pleading",
                "motion",
                "draft",
                "Tester",
                "work_product",
                "",
                "",
                ""
        );
        document_parts.PartRec part = document_parts.defaultStore().create(
                tenant,
                matter,
                doc.uuid,
                "Main Part",
                "lead",
                "1",
                "",
                "Tester",
                ""
        );

        Path partFolder = document_parts.defaultStore().partFolder(tenant, matter, doc.uuid, part.uuid);
        Files.createDirectories(partFolder.resolve("version_files"));
        Path versionPath = partFolder.resolve("version_files").resolve("search-source.docx");
        Files.write(versionPath, minimalDocx("Orion Extraction Phrase"), java.nio.file.StandardOpenOption.CREATE_NEW);

        part_versions.defaultStore().create(
                tenant,
                matter,
                doc.uuid,
                part.uuid,
                "DOCX Candidate",
                "uploaded",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "",
                String.valueOf(Files.size(versionPath)),
                versionPath.toUri().toString(),
                "Tester",
                "docx searchable",
                true
        );

        search_jobs_service.SearchJobRequest req = new search_jobs_service.SearchJobRequest();
        req.tenantUuid = tenant;
        req.requestedBy = requestedBy;
        req.searchType = "document_part_versions";
        req.query = "Orion Extraction Phrase";
        req.operator = "contains";
        req.caseSensitive = false;
        req.includeMetadata = false;
        req.includeOcr = true;
        req.maxResults = 50;

        String jobId = search_jobs_service.defaultService().enqueue(req);
        search_jobs_service.SearchJobSnapshot done = waitForJob(tenant, requestedBy, jobId, 30_000L);
        assertNotNull(done);
        assertEquals("completed", done.status);
        assertFalse(done.results.isEmpty());
    }

    @Test
    void document_search_matches_tracked_image_hash_tokens() throws Exception {
        String tenant = "tenant-search-hash-" + UUID.randomUUID();
        String requestedBy = "search-hash-" + UUID.randomUUID() + "@test.local";
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());
        matters.MatterRec matterRec = matters.defaultStore().create(tenant, "Hash Search Matter", "", "", "", "", "");
        String matter = matterRec.uuid;

        documents.DocumentRec doc = documents.defaultStore().create(
                tenant,
                matter,
                "Hash Search Doc",
                "pleading",
                "motion",
                "draft",
                "Tester",
                "work_product",
                "",
                "",
                ""
        );
        document_parts.PartRec part = document_parts.defaultStore().create(
                tenant,
                matter,
                doc.uuid,
                "Main Part",
                "lead",
                "1",
                "",
                "Tester",
                ""
        );

        Path partFolder = document_parts.defaultStore().partFolder(tenant, matter, doc.uuid, part.uuid);
        Files.createDirectories(partFolder.resolve("version_files"));
        Path versionPath = partFolder.resolve("version_files").resolve("search-hash.docx");
        Files.write(versionPath, minimalDocx("Nebula Hash Phrase"), java.nio.file.StandardOpenOption.CREATE_NEW);

        part_versions.VersionRec version = part_versions.defaultStore().create(
                tenant,
                matter,
                doc.uuid,
                part.uuid,
                "DOCX Hash Candidate",
                "uploaded",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "",
                String.valueOf(Files.size(versionPath)),
                versionPath.toUri().toString(),
                "Tester",
                "hash searchable",
                true
        );

        version_ocr_companion_service.CompanionRec companion = version_ocr_companion_service.defaultService().ensureCompanion(
                tenant,
                matter,
                doc.uuid,
                part.uuid,
                version
        );
        assertNotNull(companion);
        assertNotNull(companion.imageHashes);
        assertFalse(companion.imageHashes.isEmpty());
        String dhash = image_hash_tools.normalizeHex64(companion.imageHashes.get(0).differenceHash64);
        assertFalse(dhash.isBlank());

        search_jobs_service.SearchJobRequest req = new search_jobs_service.SearchJobRequest();
        req.tenantUuid = tenant;
        req.requestedBy = requestedBy;
        req.searchType = "document_part_versions";
        req.query = "image_dhash64:" + dhash;
        req.operator = "contains";
        req.caseSensitive = false;
        req.includeMetadata = true;
        req.includeOcr = true;
        req.maxResults = 50;

        String jobId = search_jobs_service.defaultService().enqueue(req);
        search_jobs_service.SearchJobSnapshot done = waitForJob(tenant, requestedBy, jobId, 30_000L);
        assertNotNull(done);
        assertEquals("completed", done.status);
        assertFalse(done.results.isEmpty());
    }

    @Test
    void case_conflicts_search_type_scans_versions_and_linked_contacts() throws Exception {
        String tenant = "tenant-search-conflicts-" + UUID.randomUUID();
        String requestedBy = "search-conflicts-" + UUID.randomUUID() + "@test.local";
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());
        matters.MatterRec matterRec = matters.defaultStore().create(tenant, "Conflict Search Matter", "", "", "", "", "");
        String matter = matterRec.uuid;

        documents.DocumentRec doc = documents.defaultStore().create(
                tenant,
                matter,
                "Conflict Source Doc",
                "pleading",
                "motion",
                "draft",
                "Tester",
                "work_product",
                "",
                "",
                ""
        );
        document_parts.PartRec part = document_parts.defaultStore().create(
                tenant,
                matter,
                doc.uuid,
                "Main Part",
                "lead",
                "1",
                "",
                "Tester",
                ""
        );

        Path partFolder = document_parts.defaultStore().partFolder(tenant, matter, doc.uuid, part.uuid);
        Files.createDirectories(partFolder.resolve("version_files"));
        Path versionPath = partFolder.resolve("version_files").resolve("conflict-source.txt");
        Files.writeString(
                versionPath,
                "Counsel noted Alice Walker and Chapman Law Group PLLC in this file.",
                StandardCharsets.UTF_8
        );
        part_versions.defaultStore().create(
                tenant,
                matter,
                doc.uuid,
                part.uuid,
                "Conflict Version",
                "uploaded",
                "text/plain",
                "",
                String.valueOf(Files.size(versionPath)),
                versionPath.toUri().toString(),
                "Tester",
                "",
                true
        );

        contacts.ContactInput contactIn = new contacts.ContactInput();
        contactIn.displayName = "Robert Client";
        contactIn.givenName = "Robert";
        contactIn.surname = "Client";
        contactIn.companyName = "Acme Holdings LLC";
        contacts.ContactRec contact = contacts.defaultStore().createNative(tenant, contactIn);
        matter_contacts.defaultStore().replaceNativeLinksForContact(tenant, contact.uuid, List.of(matter));

        search_jobs_service.SearchJobRequest req = new search_jobs_service.SearchJobRequest();
        req.tenantUuid = tenant;
        req.requestedBy = requestedBy;
        req.searchType = "case_conflicts";
        req.query = "Acme Holdings LLC";
        req.operator = "contains";
        req.caseSensitive = false;
        req.includeMetadata = true;
        req.includeOcr = true;
        req.maxResults = 100;

        String jobId = search_jobs_service.defaultService().enqueue(req);
        search_jobs_service.SearchJobSnapshot done = waitForJob(tenant, requestedBy, jobId, 45_000L);
        assertNotNull(done);
        assertEquals("completed", done.status);
        assertFalse(done.results.isEmpty());
        boolean matched = false;
        for (search_jobs_service.SearchResultRec row : done.results) {
            if (row == null) continue;
            if (safe(row.documentTitle).toLowerCase().contains("acme holdings llc")) {
                matched = true;
                break;
            }
        }
        assertTrue(matched);
    }

    private static byte[] minimalDocx(String text) throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream(2048)) {
            doc.createParagraph().createRun().setText(safe(text));
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static search_jobs_service.SearchJobSnapshot waitForJob(String tenantUuid,
                                                                    String requestedBy,
                                                                    String jobId,
                                                                    long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(1_000L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            search_jobs_service.SearchJobSnapshot rec = search_jobs_service.defaultService().getJob(
                    tenantUuid,
                    requestedBy,
                    jobId,
                    true
            );
            if (rec != null && ("completed".equals(rec.status) || "failed".equals(rec.status))) {
                return rec;
            }
            Thread.sleep(120L);
        }
        List<search_jobs_service.SearchJobSnapshot> rows = search_jobs_service.defaultService().listJobs(
                tenantUuid,
                requestedBy,
                20,
                false
        );
        assertFalse(rows.isEmpty(), "Timed out waiting for search job completion.");
        return null;
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
}
